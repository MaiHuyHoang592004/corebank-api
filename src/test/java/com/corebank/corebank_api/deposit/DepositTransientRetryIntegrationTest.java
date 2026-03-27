package com.corebank.corebank_api.deposit;

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
class DepositTransientRetryIntegrationTest {

	@Autowired
	private DepositApplicationService depositApplicationService;

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
	void openDeposit_retriesTransientLockFailure_thenSucceedsWithSingleSideEffects() {
		SeededContext ctx = seedContext("VND", 10_000_000L);
		AtomicInteger lockInvocation = new AtomicInteger();
		String idempotencyKey = "idem-deposit-transient-open-" + UUID.randomUUID();

		doAnswer(invocation -> {
			if (lockInvocation.incrementAndGet() == 1) {
				throw new CannotAcquireLockException("synthetic lock timeout", new SQLException("lock timeout", "55P03"));
			}
			return invocation.callRealMethod();
		}).when(accountBalanceRepository).lockById(any(UUID.class));

		DepositApplicationService.OpenDepositResponse response = depositApplicationService.openDeposit(
				new DepositApplicationService.OpenDepositRequest(
						idempotencyKey,
						ctx.customerAccountId(),
						ctx.productId(),
						ctx.productVersionId(),
						5_000_000L,
						"VND",
						6.5,
						12,
						1.0,
						false,
						ctx.debitLedgerAccountId(),
						ctx.creditLedgerAccountId(),
						"tester",
						UUID.randomUUID(),
						UUID.randomUUID(),
						UUID.randomUUID(),
						"trace-deposit-transient-open"));

		assertNotNull(response.contractId());
		assertEquals(2, lockInvocation.get());
		assertEquals(1, count("SELECT COUNT(*) FROM deposit_contracts WHERE contract_id = ?", response.contractId()));
		assertEquals(1, count("SELECT COUNT(*) FROM deposit_events WHERE contract_id = ? AND event_type = 'OPENED'", response.contractId()));
		assertEquals(1, count(
				"SELECT COUNT(*) FROM audit_events WHERE action = 'DEPOSIT_OPENED' AND resource_id = ?",
				response.contractId().toString()));
		assertEquals(1, count(
				"SELECT COUNT(*) FROM outbox_events WHERE event_type = 'DEPOSIT_OPENED' AND aggregate_id = ?",
				response.contractId().toString()));
		assertEquals(1, count(
				"SELECT COUNT(*) FROM idempotency_keys WHERE idempotency_key = ? AND status = 'SUCCEEDED'",
				idempotencyKey));
	}

	@Test
	void accrueInterest_retriesTransientDeadlock_thenSucceedsWithSingleSideEffects() {
		SeededContext ctx = seedContext("VND", 10_000_000L);
		AtomicInteger accrualJournalInvocation = new AtomicInteger();
		String idempotencyKey = "idem-deposit-transient-accrue-" + UUID.randomUUID();

		DepositApplicationService.OpenDepositResponse opened = depositApplicationService.openDeposit(
				new DepositApplicationService.OpenDepositRequest(
						"idem-deposit-transient-accrue-open-" + UUID.randomUUID(),
						ctx.customerAccountId(),
						ctx.productId(),
						ctx.productVersionId(),
						2_000_000L,
						"VND",
						6.5,
						12,
						1.0,
						false,
						ctx.debitLedgerAccountId(),
						ctx.creditLedgerAccountId(),
						"tester",
						UUID.randomUUID(),
						UUID.randomUUID(),
						UUID.randomUUID(),
						"trace-deposit-transient-accrue-open"));

		int accrualJournalBefore = count("SELECT COUNT(*) FROM ledger_journals WHERE journal_type = 'DEPOSIT_ACCRUAL'");

		doAnswer(invocation -> {
			LedgerCommandService.PostJournalCommand command = invocation.getArgument(0);
			if ("DEPOSIT_ACCRUAL".equals(command.journalType()) && accrualJournalInvocation.incrementAndGet() == 1) {
				throw new DeadlockLoserDataAccessException("synthetic deadlock", new SQLException("deadlock", "40P01"));
			}
			return invocation.callRealMethod();
		}).when(ledgerCommandService).postJournal(any(LedgerCommandService.PostJournalCommand.class));

		DepositApplicationService.AccrueInterestResponse response = depositApplicationService.accrueInterest(
				new DepositApplicationService.AccrueInterestRequest(
						idempotencyKey,
						opened.contractId(),
						ctx.debitLedgerAccountId(),
						ctx.creditLedgerAccountId(),
						"tester",
						UUID.randomUUID(),
						UUID.randomUUID(),
						UUID.randomUUID(),
						"trace-deposit-transient-accrue"));

		assertNotNull(response.accrualId());
		assertEquals(2, accrualJournalInvocation.get());
		assertEquals(1, count(
				"SELECT COUNT(*) FROM deposit_accruals WHERE contract_id = ? AND accrual_date = CURRENT_DATE",
				opened.contractId()));
		assertEquals(1, count(
				"SELECT COUNT(*) FROM deposit_events WHERE contract_id = ? AND event_type = 'ACCURED'",
				opened.contractId()));
		assertEquals(1, count(
				"SELECT COUNT(*) FROM ledger_journals WHERE journal_type = 'DEPOSIT_ACCRUAL'") - accrualJournalBefore);
		assertEquals(1, count(
				"SELECT COUNT(*) FROM audit_events WHERE action = 'DEPOSIT_ACCRUED' AND resource_id = ?",
				response.accrualId().toString()));
		assertEquals(1, count(
				"SELECT COUNT(*) FROM outbox_events WHERE event_type = 'DEPOSIT_ACCRUED' AND aggregate_id = ?",
				response.accrualId().toString()));
		assertEquals(1, count(
				"SELECT COUNT(*) FROM idempotency_keys WHERE idempotency_key = ? AND status = 'SUCCEEDED'",
				idempotencyKey));
	}

	@Test
	void processMaturity_retriesTransientDeadlock_thenSucceedsWithSingleSideEffects() {
		SeededContext ctx = seedContext("VND", 10_000_000L);
		AtomicInteger maturityJournalInvocation = new AtomicInteger();
		String idempotencyKey = "idem-deposit-transient-maturity-" + UUID.randomUUID();

		DepositApplicationService.OpenDepositResponse opened = depositApplicationService.openDeposit(
				new DepositApplicationService.OpenDepositRequest(
						"idem-deposit-transient-maturity-open-" + UUID.randomUUID(),
						ctx.customerAccountId(),
						ctx.productId(),
						ctx.productVersionId(),
						2_000_000L,
						"VND",
						6.5,
						1,
						1.0,
						false,
						ctx.debitLedgerAccountId(),
						ctx.creditLedgerAccountId(),
						"tester",
						UUID.randomUUID(),
						UUID.randomUUID(),
						UUID.randomUUID(),
						"trace-deposit-transient-maturity-open"));

		depositApplicationService.accrueInterest(
				new DepositApplicationService.AccrueInterestRequest(
						"idem-deposit-transient-maturity-accrue-" + UUID.randomUUID(),
						opened.contractId(),
						ctx.debitLedgerAccountId(),
						ctx.creditLedgerAccountId(),
						"tester",
						UUID.randomUUID(),
						UUID.randomUUID(),
						UUID.randomUUID(),
						"trace-deposit-transient-maturity-accrue"));

		jdbcTemplate.update(
				"UPDATE deposit_contracts SET start_date = CURRENT_DATE - INTERVAL '2 day', maturity_date = CURRENT_DATE - INTERVAL '1 day' WHERE contract_id = ?",
				opened.contractId());

		int maturityJournalBefore = count("SELECT COUNT(*) FROM ledger_journals WHERE journal_type = 'DEPOSIT_MATURITY'");

		doAnswer(invocation -> {
			LedgerCommandService.PostJournalCommand command = invocation.getArgument(0);
			if ("DEPOSIT_MATURITY".equals(command.journalType()) && maturityJournalInvocation.incrementAndGet() == 1) {
				throw new DeadlockLoserDataAccessException("synthetic deadlock", new SQLException("deadlock", "40P01"));
			}
			return invocation.callRealMethod();
		}).when(ledgerCommandService).postJournal(any(LedgerCommandService.PostJournalCommand.class));

		DepositApplicationService.MaturityResponse response = depositApplicationService.processMaturity(
				new DepositApplicationService.MaturityRequest(
						idempotencyKey,
						opened.contractId(),
						ctx.debitLedgerAccountId(),
						ctx.creditLedgerAccountId(),
						"tester",
						UUID.randomUUID(),
						UUID.randomUUID(),
						UUID.randomUUID(),
						"trace-deposit-transient-maturity"));

		assertEquals("MATURED", response.status());
		assertEquals(2, maturityJournalInvocation.get());
		assertEquals(1, count(
				"SELECT COUNT(*) FROM deposit_events WHERE contract_id = ? AND event_type = 'MATURED'",
				opened.contractId()));
		assertEquals(1, count(
				"SELECT COUNT(*) FROM ledger_journals WHERE journal_type = 'DEPOSIT_MATURITY'") - maturityJournalBefore);
		assertEquals(1, count(
				"SELECT COUNT(*) FROM audit_events WHERE action = 'DEPOSIT_MATURED' AND resource_id = ?",
				opened.contractId().toString()));
		assertEquals(1, count(
				"SELECT COUNT(*) FROM outbox_events WHERE event_type = 'DEPOSIT_MATURED' AND aggregate_id = ?",
				opened.contractId().toString()));
		assertEquals(1, count(
				"SELECT COUNT(*) FROM idempotency_keys WHERE idempotency_key = ? AND status = 'SUCCEEDED'",
				idempotencyKey));
	}

	@Test
	void openDeposit_doesNotRetryNonTransientFailure_andLeavesIdempotencyFailed() {
		SeededContext ctx = seedContext("VND", 10_000_000L);
		String idempotencyKey = "idem-deposit-non-transient-open-" + UUID.randomUUID();

		doAnswer(invocation -> {
			throw new CoreBankException("synthetic non-transient failure");
		}).when(accountBalanceRepository).lockById(any(UUID.class));

		assertThrows(CoreBankException.class, () -> depositApplicationService.openDeposit(
				new DepositApplicationService.OpenDepositRequest(
						idempotencyKey,
						ctx.customerAccountId(),
						ctx.productId(),
						ctx.productVersionId(),
						1_000_000L,
						"VND",
						6.5,
						12,
						1.0,
						false,
						ctx.debitLedgerAccountId(),
						ctx.creditLedgerAccountId(),
						"tester",
						UUID.randomUUID(),
						UUID.randomUUID(),
						UUID.randomUUID(),
						"trace-deposit-non-transient-open")));

		verify(accountBalanceRepository, times(1)).lockById(any(UUID.class));
		assertEquals(0, count(
				"SELECT COUNT(*) FROM deposit_contracts WHERE customer_account_id = ?",
				ctx.customerAccountId()));
		assertEquals(1, count(
				"SELECT COUNT(*) FROM idempotency_keys WHERE idempotency_key = ? AND status = 'FAILED'",
				idempotencyKey));
	}

	private SeededContext seedContext(String currency, long postedAndAvailableBalance) {
		UUID customerId = UUID.randomUUID();
		UUID productId = UUID.randomUUID();
		UUID productVersionId = UUID.randomUUID();
		UUID customerAccountId = UUID.randomUUID();
		UUID debitLedgerAccountId = UUID.randomUUID();
		UUID creditLedgerAccountId = UUID.randomUUID();

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
				"Deposit Retry Customer",
				"deposit-retry@example.com",
				"0910000000",
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
				) VALUES (?, ?, ?, 'TERM_DEPOSIT', ?, 'ACTIVE')
				""",
				productId,
				"TD-" + productId.toString().substring(0, 8),
				"Term Deposit",
				currency);

		jdbcTemplate.update(
				"""
				INSERT INTO bank_product_versions (
				    product_version_id,
				    product_id,
				    version_no,
				    effective_from,
				    effective_to,
				    status,
				    configuration_json,
				    created_at
				) VALUES (?, ?, 1, now() - interval '1 day', NULL, 'ACTIVE', '{}'::jsonb, now())
				""",
				productVersionId,
				productId);

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
				"DEP-DEBIT-" + debitLedgerAccountId.toString().substring(0, 8),
				"Deposit Funding Liability",
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
				"DEP-CREDIT-" + creditLedgerAccountId.toString().substring(0, 8),
				"Deposit Funding Asset",
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
				postedAndAvailableBalance,
				postedAndAvailableBalance);

		return new SeededContext(
				customerId,
				productId,
				productVersionId,
				customerAccountId,
				debitLedgerAccountId,
				creditLedgerAccountId);
	}

	private int count(String sql, Object... args) {
		Integer count = jdbcTemplate.queryForObject(sql, Integer.class, args);
		return count == null ? 0 : count;
	}

	private record SeededContext(
			UUID customerId,
			UUID productId,
			UUID productVersionId,
			UUID customerAccountId,
			UUID debitLedgerAccountId,
			UUID creditLedgerAccountId) {
	}
}
