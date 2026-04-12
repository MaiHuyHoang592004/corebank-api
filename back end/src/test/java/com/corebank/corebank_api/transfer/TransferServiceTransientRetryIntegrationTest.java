package com.corebank.corebank_api.transfer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.corebank.corebank_api.TestcontainersConfiguration;
import com.corebank.corebank_api.account.AccountBalanceRepository;
import com.corebank.corebank_api.common.CoreBankException;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
class TransferServiceTransientRetryIntegrationTest {

	@Autowired
	private TransferService transferService;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@Autowired
	private SystemModeService systemModeService;

	@MockitoSpyBean
	private AccountBalanceRepository accountBalanceRepository;

	@BeforeEach
	void setUp() {
		reset(accountBalanceRepository);
		systemModeService.setMode(SystemModeService.SystemMode.RUNNING, "test");
	}

	@Test
	void transfer_retriesTransientLockFailure_thenSucceedsWithSingleSideEffects() {
		SeededAccounts seededAccounts = seedAccounts(10_000L, 10_000L, 5_000L, 5_000L, "VND");
		SeededLedgerAccounts ledgerAccounts = seedLedgerAccounts("VND");
		UUID correlationId = UUID.randomUUID();
		String idempotencyKey = "idem-transfer-transient-retry-" + UUID.randomUUID();
		AtomicInteger lockInvocation = new AtomicInteger();

		doAnswer(invocation -> {
			if (lockInvocation.incrementAndGet() == 1) {
				throw new CannotAcquireLockException("synthetic lock timeout", new SQLException("lock timeout", "55P03"));
			}
			return invocation.callRealMethod();
		}).when(accountBalanceRepository).lockByIdsInDeterministicOrder(anyCollection());

		TransferService.TransferResponse response = transferService.transfer(
				new TransferService.TransferRequest(
						idempotencyKey,
						seededAccounts.sourceAccountId(),
						seededAccounts.destinationAccountId(),
						3_000L,
						"VND",
						ledgerAccounts.debitLedgerAccountId(),
						ledgerAccounts.creditLedgerAccountId(),
						"transient retry",
						"tester",
						correlationId,
						UUID.randomUUID(),
						UUID.randomUUID(),
						"trace-transfer-transient-retry"));

		assertNotNull(response.journalId());
		assertEquals(3, lockInvocation.get());

		assertEquals(1, count(
				"SELECT COUNT(*) FROM ledger_journals WHERE correlation_id = ?",
				correlationId));
		assertEquals(1, count(
				"SELECT COUNT(*) FROM audit_events WHERE action = 'INTERNAL_TRANSFER' AND resource_id = ?",
				response.journalId().toString()));
		assertEquals(1, count(
				"SELECT COUNT(*) FROM outbox_events WHERE event_type = 'TRANSFER_COMPLETED' AND aggregate_id = ?",
				response.journalId().toString()));

		String idemStatus = jdbcTemplate.queryForObject(
				"SELECT status FROM idempotency_keys WHERE idempotency_key = ?",
				String.class,
				idempotencyKey);
		assertEquals("SUCCEEDED", idemStatus);
	}

	@Test
	void transfer_doesNotRetryNonTransientFailure_andLeavesIdempotencyFailed() {
		SeededAccounts seededAccounts = seedAccounts(10_000L, 10_000L, 5_000L, 5_000L, "VND");
		SeededLedgerAccounts ledgerAccounts = seedLedgerAccounts("VND");
		String idempotencyKey = "idem-transfer-non-transient-" + UUID.randomUUID();
		UUID correlationId = UUID.randomUUID();

		doThrow(new CoreBankException("synthetic non-transient failure"))
				.when(accountBalanceRepository)
				.lockByIdsInDeterministicOrder(anyCollection());

		assertThrows(CoreBankException.class, () -> transferService.transfer(
				new TransferService.TransferRequest(
						idempotencyKey,
						seededAccounts.sourceAccountId(),
						seededAccounts.destinationAccountId(),
						1_000L,
						"VND",
						ledgerAccounts.debitLedgerAccountId(),
						ledgerAccounts.creditLedgerAccountId(),
						"non transient",
						"tester",
						correlationId,
						UUID.randomUUID(),
						UUID.randomUUID(),
						"trace-transfer-non-transient")));

		verify(accountBalanceRepository, times(1)).lockByIdsInDeterministicOrder(anyCollection());
		assertEquals(0, count(
				"SELECT COUNT(*) FROM ledger_journals WHERE reference_type = 'TRANSFER' AND correlation_id = ?",
				correlationId));

		String idemStatus = jdbcTemplate.queryForObject(
				"SELECT status FROM idempotency_keys WHERE idempotency_key = ?",
				String.class,
				idempotencyKey);
		assertEquals("FAILED", idemStatus);
	}

	private SeededAccounts seedAccounts(
			long sourcePostedBalanceMinor,
			long sourceAvailableBalanceMinor,
			long destPostedBalanceMinor,
			long destAvailableBalanceMinor,
			String currency) {
		UUID sourceCustomerId = UUID.randomUUID();
		UUID sourceProductId = UUID.randomUUID();
		UUID sourceAccountId = UUID.randomUUID();

		UUID destCustomerId = UUID.randomUUID();
		UUID destProductId = UUID.randomUUID();
		UUID destAccountId = UUID.randomUUID();

		jdbcTemplate.update(
				"""
				INSERT INTO customers (customer_id, customer_type, full_name, email, phone, status, risk_band)
				VALUES (?, 'INDIVIDUAL', ?, ?, ?, 'ACTIVE', 'LOW')
				""",
				sourceCustomerId,
				"Source Customer",
				"source-" + sourceCustomerId + "@example.com",
				"090-" + sourceCustomerId.toString().substring(0, 8));

		jdbcTemplate.update(
				"""
				INSERT INTO bank_products (product_id, product_code, product_name, product_type, currency, status)
				VALUES (?, ?, ?, 'CHECKING', ?, 'ACTIVE')
				""",
				sourceProductId,
				"CHK-" + sourceProductId.toString().substring(0, 8),
				"Checking",
				currency);

		jdbcTemplate.update(
				"""
				INSERT INTO customer_accounts (
				    customer_account_id, customer_id, product_id, account_number, currency, status,
				    posted_balance_minor, available_balance_minor, version
				) VALUES (?, ?, ?, ?, ?, 'ACTIVE', ?, ?, 0)
				""",
				sourceAccountId,
				sourceCustomerId,
				sourceProductId,
				"ACCT-" + sourceAccountId.toString().substring(0, 8),
				currency,
				sourcePostedBalanceMinor,
				sourceAvailableBalanceMinor);

		jdbcTemplate.update(
				"""
				INSERT INTO customers (customer_id, customer_type, full_name, email, phone, status, risk_band)
				VALUES (?, 'INDIVIDUAL', ?, ?, ?, 'ACTIVE', 'LOW')
				""",
				destCustomerId,
				"Destination Customer",
				"dest-" + destCustomerId + "@example.com",
				"091-" + destCustomerId.toString().substring(0, 8));

		jdbcTemplate.update(
				"""
				INSERT INTO bank_products (product_id, product_code, product_name, product_type, currency, status)
				VALUES (?, ?, ?, 'CHECKING', ?, 'ACTIVE')
				""",
				destProductId,
				"CHK-" + destProductId.toString().substring(0, 8),
				"Checking",
				currency);

		jdbcTemplate.update(
				"""
				INSERT INTO customer_accounts (
				    customer_account_id, customer_id, product_id, account_number, currency, status,
				    posted_balance_minor, available_balance_minor, version
				) VALUES (?, ?, ?, ?, ?, 'ACTIVE', ?, ?, 0)
				""",
				destAccountId,
				destCustomerId,
				destProductId,
				"ACCT-" + destAccountId.toString().substring(0, 8),
				currency,
				destPostedBalanceMinor,
				destAvailableBalanceMinor);

		return new SeededAccounts(sourceAccountId, destAccountId);
	}

	private SeededLedgerAccounts seedLedgerAccounts(String currency) {
		UUID debitLedgerAccountId = UUID.randomUUID();
		UUID creditLedgerAccountId = UUID.randomUUID();

		jdbcTemplate.update(
				"""
				INSERT INTO ledger_accounts (
				    ledger_account_id, account_code, account_name, account_type, currency, is_active
				) VALUES (?, ?, ?, 'LIABILITY', ?, true)
				""",
				debitLedgerAccountId,
				"DEBIT-" + debitLedgerAccountId.toString().substring(0, 8),
				"Customer Deposit Liability",
				currency);

		jdbcTemplate.update(
				"""
				INSERT INTO ledger_accounts (
				    ledger_account_id, account_code, account_name, account_type, currency, is_active
				) VALUES (?, ?, ?, 'ASSET', ?, true)
				""",
				creditLedgerAccountId,
				"CREDIT-" + creditLedgerAccountId.toString().substring(0, 8),
				"Internal Transfer Asset",
				currency);

		return new SeededLedgerAccounts(debitLedgerAccountId, creditLedgerAccountId);
	}

	private int count(String sql, Object... args) {
		Integer count = jdbcTemplate.queryForObject(sql, Integer.class, args);
		return count == null ? 0 : count;
	}

	private record SeededAccounts(
			UUID sourceAccountId,
			UUID destinationAccountId) {
	}

	private record SeededLedgerAccounts(
			UUID debitLedgerAccountId,
			UUID creditLedgerAccountId) {
	}
}
