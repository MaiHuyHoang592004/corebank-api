package com.corebank.corebank_api.payment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.corebank.corebank_api.TestcontainersConfiguration;
import com.corebank.corebank_api.account.AccountBalanceRepository;
import com.corebank.corebank_api.common.CoreBankException;
import com.corebank.corebank_api.ledger.LedgerCommandService;
import com.corebank.corebank_api.ops.system.SystemModeService;
import java.sql.SQLException;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DeadlockLoserDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
class PaymentTransientRetryIntegrationTest {

	@Autowired
	private PaymentApplicationService paymentApplicationService;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@Autowired
	private SystemModeService systemModeService;

	@MockitoSpyBean
	private AccountBalanceRepository accountBalanceRepository;

	@MockitoSpyBean
	private LedgerCommandService ledgerCommandService;

	@BeforeEach
	void setUp() {
		reset(accountBalanceRepository, ledgerCommandService);
		systemModeService.setMode(SystemModeService.SystemMode.RUNNING, "test");
	}

	@Test
	void authorizeHold_retriesTransientLockFailure_thenSucceedsWithSingleSideEffects() {
		SeededAccount seededAccount = seedAccount(10_000L, 10_000L, "VND");
		AtomicInteger lockInvocation = new AtomicInteger();
		String idempotencyKey = "idem-payment-transient-auth-" + UUID.randomUUID();

		doAnswer(invocation -> {
			if (lockInvocation.incrementAndGet() == 1) {
				throw new CannotAcquireLockException("synthetic lock timeout", new SQLException("lock timeout", "55P03"));
			}
			return invocation.callRealMethod();
		}).when(accountBalanceRepository).lockById(any(UUID.class));

		PaymentApplicationService.AuthorizeHoldResponse response = paymentApplicationService.authorizeHold(
				new PaymentApplicationService.AuthorizeHoldRequest(
						idempotencyKey,
						seededAccount.customerAccountId(),
						null,
						3_000L,
						"VND",
						"MERCHANT_PAYMENT",
						"transient authorize",
						"tester",
						UUID.randomUUID(),
						UUID.randomUUID(),
						UUID.randomUUID(),
						"trace-payment-transient-auth"));

		assertNotNull(response.paymentOrderId());
		assertNotNull(response.holdId());
		assertEquals(2, lockInvocation.get());
		assertEquals(1, count("SELECT COUNT(*) FROM payment_orders WHERE payment_order_id = ?", response.paymentOrderId()));
		assertEquals(1, count("SELECT COUNT(*) FROM funds_holds WHERE hold_id = ?", response.holdId()));
		assertEquals(1, count(
				"SELECT COUNT(*) FROM audit_events WHERE action = 'PAYMENT_HOLD_AUTHORIZED' AND resource_id = ?",
				response.paymentOrderId().toString()));
		assertEquals(1, count(
				"SELECT COUNT(*) FROM outbox_events WHERE event_type = 'PAYMENT_AUTHORIZED' AND aggregate_id = ?",
				response.paymentOrderId().toString()));
		assertEquals(1, count(
				"SELECT COUNT(*) FROM idempotency_keys WHERE idempotency_key = ? AND status = 'SUCCEEDED'",
				idempotencyKey));
	}

	@Test
	void captureHold_retriesTransientDeadlock_thenSucceedsWithSingleSideEffects() {
		SeededAccount seededAccount = seedAccount(10_000L, 10_000L, "VND");
		SeededLedgerAccounts ledgerAccounts = seedLedgerAccounts("VND");
		AtomicInteger journalInvocation = new AtomicInteger();
		String captureIdempotencyKey = "idem-payment-transient-capture-" + UUID.randomUUID();

		PaymentApplicationService.AuthorizeHoldResponse authorization = paymentApplicationService.authorizeHold(
				new PaymentApplicationService.AuthorizeHoldRequest(
						"idem-payment-transient-capture-auth-" + UUID.randomUUID(),
						seededAccount.customerAccountId(),
						null,
						3_000L,
						"VND",
						"MERCHANT_PAYMENT",
						"transient capture setup",
						"tester",
						UUID.randomUUID(),
						UUID.randomUUID(),
						UUID.randomUUID(),
						"trace-payment-transient-capture-auth"));

		doAnswer(invocation -> {
			if (journalInvocation.incrementAndGet() == 1) {
				throw new DeadlockLoserDataAccessException("synthetic deadlock", new SQLException("deadlock", "40P01"));
			}
			return invocation.callRealMethod();
		}).when(ledgerCommandService).postJournal(any(LedgerCommandService.PostJournalCommand.class));

		PaymentApplicationService.CaptureHoldResponse capture = paymentApplicationService.captureHold(
				new PaymentApplicationService.CaptureHoldRequest(
						captureIdempotencyKey,
						authorization.holdId(),
						3_000L,
						ledgerAccounts.debitLedgerAccountId(),
						ledgerAccounts.creditLedgerAccountId(),
						null,
						"tester",
						UUID.randomUUID(),
						UUID.randomUUID(),
						UUID.randomUUID(),
						"trace-payment-transient-capture"));

		assertNotNull(capture.journalId());
		assertEquals(2, journalInvocation.get());
		assertEquals(1, count(
				"SELECT COUNT(*) FROM ledger_journals WHERE reference_type = 'PAYMENT_ORDER' AND reference_id = ?",
				authorization.paymentOrderId()));
		assertEquals(1, count(
				"SELECT COUNT(*) FROM audit_events WHERE action = 'PAYMENT_HOLD_CAPTURED' AND resource_id = ?",
				authorization.holdId().toString()));
		assertEquals(1, count(
				"SELECT COUNT(*) FROM outbox_events WHERE event_type = 'PAYMENT_CAPTURED' AND aggregate_id = ?",
				authorization.holdId().toString()));
		assertEquals(1, count(
				"SELECT COUNT(*) FROM idempotency_keys WHERE idempotency_key = ? AND status = 'SUCCEEDED'",
				captureIdempotencyKey));
	}

	@Test
	void authorizeHold_doesNotRetryNonTransientFailure_andLeavesIdempotencyFailed() {
		SeededAccount seededAccount = seedAccount(10_000L, 10_000L, "VND");
		String idempotencyKey = "idem-payment-non-transient-auth-" + UUID.randomUUID();

		doAnswer(invocation -> {
			throw new CoreBankException("synthetic non-transient failure");
		}).when(accountBalanceRepository).lockById(any(UUID.class));

		assertThrows(CoreBankException.class, () -> paymentApplicationService.authorizeHold(
				new PaymentApplicationService.AuthorizeHoldRequest(
						idempotencyKey,
						seededAccount.customerAccountId(),
						null,
						1_000L,
						"VND",
						"MERCHANT_PAYMENT",
						"non transient authorize",
						"tester",
						UUID.randomUUID(),
						UUID.randomUUID(),
						UUID.randomUUID(),
						"trace-payment-non-transient-auth")));

		verify(accountBalanceRepository, times(1)).lockById(any(UUID.class));
		assertEquals(0, count(
				"SELECT COUNT(*) FROM payment_orders WHERE payer_account_id = ?",
				seededAccount.customerAccountId()));
		assertEquals(1, count(
				"SELECT COUNT(*) FROM idempotency_keys WHERE idempotency_key = ? AND status = 'FAILED'",
				idempotencyKey));
	}

	private SeededAccount seedAccount(long postedBalanceMinor, long availableBalanceMinor, String currency) {
		UUID customerId = UUID.randomUUID();
		UUID productId = UUID.randomUUID();
		UUID customerAccountId = UUID.randomUUID();

		jdbcTemplate.update(
				"""
				INSERT INTO customers (
				    customer_id,
				    customer_type,
				    full_name,
				    email,
				    phone,
				    status,
				    risk_band
				) VALUES (?, 'INDIVIDUAL', ?, ?, ?, 'ACTIVE', ?)
				""",
				customerId,
				"Test Customer",
				"customer@example.com",
				"0123456789",
				"LOW");

		jdbcTemplate.update(
				"""
				INSERT INTO bank_products (
				    product_id,
				    product_code,
				    product_name,
				    product_type,
				    currency,
				    status
				) VALUES (?, ?, ?, ?, ?, 'ACTIVE')
				""",
				productId,
				"CHK-" + productId.toString().substring(0, 8),
				"Checking",
				"CHECKING",
				currency);

		jdbcTemplate.update(
				"""
				INSERT INTO customer_accounts (
				    customer_account_id,
				    customer_id,
				    product_id,
				    account_number,
				    currency,
				    status,
				    posted_balance_minor,
				    available_balance_minor,
				    version
				) VALUES (?, ?, ?, ?, ?, 'ACTIVE', ?, ?, 0)
				""",
				customerAccountId,
				customerId,
				productId,
				"ACCT-" + customerAccountId.toString().substring(0, 8),
				currency,
				postedBalanceMinor,
				availableBalanceMinor);

		return new SeededAccount(customerId, productId, customerAccountId);
	}

	private SeededLedgerAccounts seedLedgerAccounts(String currency) {
		UUID debitLedgerAccountId = UUID.randomUUID();
		UUID creditLedgerAccountId = UUID.randomUUID();

		jdbcTemplate.update(
				"""
				INSERT INTO ledger_accounts (
				    ledger_account_id,
				    account_code,
				    account_name,
				    account_type,
				    currency,
				    is_active
				) VALUES (?, ?, ?, 'LIABILITY', ?, true)
				""",
				debitLedgerAccountId,
				"DEBIT-" + debitLedgerAccountId.toString().substring(0, 8),
				"Customer Deposit Liability",
				currency);

		jdbcTemplate.update(
				"""
				INSERT INTO ledger_accounts (
				    ledger_account_id,
				    account_code,
				    account_name,
				    account_type,
				    currency,
				    is_active
				) VALUES (?, ?, ?, 'ASSET', ?, true)
				""",
				creditLedgerAccountId,
				"CREDIT-" + creditLedgerAccountId.toString().substring(0, 8),
				"Merchant Settlement Asset",
				currency);

		return new SeededLedgerAccounts(debitLedgerAccountId, creditLedgerAccountId);
	}

	private int count(String sql, Object... args) {
		Integer count = jdbcTemplate.queryForObject(sql, Integer.class, args);
		return count == null ? 0 : count;
	}

	private record SeededAccount(UUID customerId, UUID productId, UUID customerAccountId) {
	}

	private record SeededLedgerAccounts(UUID debitLedgerAccountId, UUID creditLedgerAccountId) {
	}
}
