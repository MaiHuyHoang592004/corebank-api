package com.corebank.corebank_api.transfer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.corebank.corebank_api.TestcontainersConfiguration;
import com.corebank.corebank_api.common.CoreBankException;
import com.corebank.corebank_api.common.IdempotencyConflictException;
import com.corebank.corebank_api.common.InsufficientFundsException;
import com.corebank.corebank_api.ops.system.SystemModeService;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
class TransferServiceIntegrationTest {

	@Autowired
	private TransferService transferService;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@Autowired
	private SystemModeService systemModeService;

	@BeforeEach
	void setUp() {
		// Reset system mode to RUNNING before each test
		systemModeService.setMode(SystemModeService.SystemMode.RUNNING, "test");
	}

	@Test
	void transferUpdatesPostedAndAvailableBalancesAndWritesAuditAndOutbox() {
		SeededAccounts seededAccounts = seedAccounts(10_000L, 10_000L, 5_000L, 5_000L, "VND");
		SeededLedgerAccounts ledgerAccounts = seedLedgerAccounts("VND");
		UUID correlationId = UUID.randomUUID();
		UUID requestId = UUID.randomUUID();

		TransferService.TransferResponse response = transferService.transfer(
				new TransferService.TransferRequest(
						"idem-transfer-1",
						seededAccounts.sourceAccountId(),
						seededAccounts.destinationAccountId(),
						3_000L,
						"VND",
						ledgerAccounts.debitLedgerAccountId(),
						ledgerAccounts.creditLedgerAccountId(),
						"test transfer",
						"tester",
						correlationId,
						requestId,
						UUID.randomUUID(),
						"trace-transfer-1"));

		assertNotNull(response.journalId());
		assertEquals(seededAccounts.sourceAccountId(), response.sourceAccountId());
		assertEquals(seededAccounts.destinationAccountId(), response.destinationAccountId());
		assertEquals(3_000L, response.amountMinor());
		assertEquals(7_000L, response.sourcePostedBalanceMinor());
		assertEquals(10_000L, response.sourceAvailableBalanceBeforeMinor());
		assertEquals(7_000L, response.sourceAvailableBalanceAfterMinor());
		assertEquals(8_000L, response.destinationPostedBalanceMinor());
		assertEquals(5_000L, response.destinationAvailableBalanceBeforeMinor());
		assertEquals(8_000L, response.destinationAvailableBalanceAfterMinor());
		assertEquals("COMPLETED", response.status());

		// Verify source account balances
		Map<String, Object> sourceAccountRow = jdbcTemplate.queryForMap(
				"SELECT posted_balance_minor, available_balance_minor FROM customer_accounts WHERE customer_account_id = ?",
				seededAccounts.sourceAccountId());

		assertEquals(7_000L, ((Number) sourceAccountRow.get("posted_balance_minor")).longValue());
		assertEquals(7_000L, ((Number) sourceAccountRow.get("available_balance_minor")).longValue());

		// Verify destination account balances
		Map<String, Object> destAccountRow = jdbcTemplate.queryForMap(
				"SELECT posted_balance_minor, available_balance_minor FROM customer_accounts WHERE customer_account_id = ?",
				seededAccounts.destinationAccountId());

		assertEquals(8_000L, ((Number) destAccountRow.get("posted_balance_minor")).longValue());
		assertEquals(8_000L, ((Number) destAccountRow.get("available_balance_minor")).longValue());

		// Verify balanced journal
		Map<String, Object> journalSums = jdbcTemplate.queryForMap(
				"""
				SELECT COALESCE(SUM(CASE WHEN entry_side = 'D' THEN amount_minor ELSE 0 END), 0) AS total_debit,
				       COALESCE(SUM(CASE WHEN entry_side = 'C' THEN amount_minor ELSE 0 END), 0) AS total_credit,
				       COUNT(*) AS posting_count
				FROM ledger_postings
				WHERE journal_id = ?
				""",
				response.journalId());

		assertEquals(3_000L, ((Number) journalSums.get("total_debit")).longValue());
		assertEquals(3_000L, ((Number) journalSums.get("total_credit")).longValue());
		assertEquals(2L, ((Number) journalSums.get("posting_count")).longValue());

		// Verify audit event
		assertEquals(1, count(
				"SELECT COUNT(*) FROM audit_events WHERE action = 'INTERNAL_TRANSFER' AND resource_id = ?",
				response.journalId().toString()));

		// Verify outbox message
		assertEquals(1, count(
				"SELECT COUNT(*) FROM outbox_events WHERE event_type = 'TRANSFER_COMPLETED' AND aggregate_id = ?",
				response.journalId().toString()));
		Map<String, Object> outboxEnvelope = jdbcTemplate.queryForMap(
				"""
				SELECT event_data->>'schemaVersion' AS schema_version,
				       event_data->>'correlationId' AS correlation_id,
				       event_data->>'requestId' AS request_id,
				       event_data->>'actor' AS actor
				FROM outbox_events
				WHERE event_type = 'TRANSFER_COMPLETED' AND aggregate_id = ?
				ORDER BY id DESC
				LIMIT 1
				""",
				response.journalId().toString());
		assertEquals("v1", outboxEnvelope.get("schema_version"));
		assertEquals(correlationId.toString(), outboxEnvelope.get("correlation_id"));
		assertEquals(requestId.toString(), outboxEnvelope.get("request_id"));
		assertEquals("tester", outboxEnvelope.get("actor"));

		// Verify idempotency succeeded
		assertEquals(1, count(
				"SELECT COUNT(*) FROM idempotency_keys WHERE idempotency_key = ? AND status = 'SUCCEEDED'",
				"idem-transfer-1"));
	}

	@Test
	void transferCannotOverspend() {
		SeededAccounts seededAccounts = seedAccounts(10_000L, 1_000L, 5_000L, 5_000L, "VND");
		SeededLedgerAccounts ledgerAccounts = seedLedgerAccounts("VND");

		UUID correlationId = UUID.randomUUID();

		assertThrows(
				InsufficientFundsException.class,
				() -> transferService.transfer(
						new TransferService.TransferRequest(
								"idem-transfer-2",
								seededAccounts.sourceAccountId(),
								seededAccounts.destinationAccountId(),
								3_000L,
								"VND",
								ledgerAccounts.debitLedgerAccountId(),
								ledgerAccounts.creditLedgerAccountId(),
								"insufficient transfer",
								"tester",
								UUID.randomUUID(),
								UUID.randomUUID(),
								correlationId,
								"trace-transfer-2")));

		// Verify source account balances unchanged
		Map<String, Object> sourceAccountRow = jdbcTemplate.queryForMap(
				"SELECT posted_balance_minor, available_balance_minor FROM customer_accounts WHERE customer_account_id = ?",
				seededAccounts.sourceAccountId());

		assertEquals(10_000L, ((Number) sourceAccountRow.get("posted_balance_minor")).longValue());
		assertEquals(1_000L, ((Number) sourceAccountRow.get("available_balance_minor")).longValue());

		// Verify destination account balances unchanged
		Map<String, Object> destAccountRow = jdbcTemplate.queryForMap(
				"SELECT posted_balance_minor, available_balance_minor FROM customer_accounts WHERE customer_account_id = ?",
				seededAccounts.destinationAccountId());

		assertEquals(5_000L, ((Number) destAccountRow.get("posted_balance_minor")).longValue());
		assertEquals(5_000L, ((Number) destAccountRow.get("available_balance_minor")).longValue());

		// Verify no journal was created for this test (filter by correlation_id)
		assertEquals(0, count(
				"SELECT COUNT(*) FROM ledger_journals WHERE reference_type = 'TRANSFER' AND correlation_id = ?",
				correlationId));

		// Verify idempotency failed
		assertEquals(1, count(
				"SELECT COUNT(*) FROM idempotency_keys WHERE idempotency_key = ? AND status = 'FAILED'",
				"idem-transfer-2"));
	}

	@Test
	void transferReplayReturnsSameResultWithoutDoubleWrites() {
		SeededAccounts seededAccounts = seedAccounts(10_000L, 10_000L, 5_000L, 5_000L, "VND");
		SeededLedgerAccounts ledgerAccounts = seedLedgerAccounts("VND");

		TransferService.TransferRequest request = new TransferService.TransferRequest(
				"idem-transfer-3",
				seededAccounts.sourceAccountId(),
				seededAccounts.destinationAccountId(),
				2_500L,
				"VND",
				ledgerAccounts.debitLedgerAccountId(),
				ledgerAccounts.creditLedgerAccountId(),
				"replay transfer",
				"tester",
				UUID.randomUUID(),
				UUID.randomUUID(),
				UUID.randomUUID(),
				"trace-transfer-3");

		TransferService.TransferResponse first = transferService.transfer(request);
		TransferService.TransferResponse second = transferService.transfer(request);

		assertEquals(first.journalId(), second.journalId());
		assertEquals(first.sourceAccountId(), second.sourceAccountId());
		assertEquals(first.destinationAccountId(), second.destinationAccountId());
		assertEquals(first.amountMinor(), second.amountMinor());
		assertEquals(first.sourceAvailableBalanceAfterMinor(), second.sourceAvailableBalanceAfterMinor());
		assertEquals(first.destinationAvailableBalanceAfterMinor(), second.destinationAvailableBalanceAfterMinor());

		// Verify only one journal was created
		assertEquals(1, count(
				"SELECT COUNT(*) FROM ledger_journals WHERE journal_id = ?", first.journalId()));

		// Verify only one audit event
		assertEquals(1, count(
				"SELECT COUNT(*) FROM audit_events WHERE action = 'INTERNAL_TRANSFER' AND resource_id = ?",
				first.journalId().toString()));

		// Verify only one outbox message
		assertEquals(1, count(
				"SELECT COUNT(*) FROM outbox_events WHERE event_type = 'TRANSFER_COMPLETED' AND aggregate_id = ?",
				first.journalId().toString()));
	}

	@Test
	void transferRejectsSameKeyWithDifferentPayload() {
		SeededAccounts seededAccounts = seedAccounts(10_000L, 10_000L, 5_000L, 5_000L, "VND");
		SeededLedgerAccounts ledgerAccounts = seedLedgerAccounts("VND");
		UUID firstCorrelationId = UUID.randomUUID();

		transferService.transfer(
				new TransferService.TransferRequest(
						"idem-transfer-4",
						seededAccounts.sourceAccountId(),
						seededAccounts.destinationAccountId(),
						1_000L,
						"VND",
						ledgerAccounts.debitLedgerAccountId(),
						ledgerAccounts.creditLedgerAccountId(),
						"first transfer",
						"tester",
						firstCorrelationId,
						UUID.randomUUID(),
						UUID.randomUUID(),
						"trace-transfer-4a"));

		assertThrows(
				IdempotencyConflictException.class,
				() -> transferService.transfer(
						new TransferService.TransferRequest(
								"idem-transfer-4",
								seededAccounts.sourceAccountId(),
								seededAccounts.destinationAccountId(),
								2_000L,
								"VND",
								ledgerAccounts.debitLedgerAccountId(),
								ledgerAccounts.creditLedgerAccountId(),
								"second transfer",
								"tester",
								UUID.randomUUID(),
								UUID.randomUUID(),
								UUID.randomUUID(),
								"trace-transfer-4b")));

		// Verify only one transfer was processed
		assertEquals(1, count(
				"SELECT COUNT(*) FROM ledger_journals WHERE reference_type = 'TRANSFER' AND correlation_id = ?",
				firstCorrelationId));

		// Verify idempotency succeeded for first request
		assertEquals(1, count(
				"SELECT COUNT(*) FROM idempotency_keys WHERE idempotency_key = ? AND status = 'SUCCEEDED'",
				"idem-transfer-4"));
	}

	@Test
	void transferPostsBalancedJournal() {
		SeededAccounts seededAccounts = seedAccounts(10_000L, 10_000L, 5_000L, 5_000L, "VND");
		SeededLedgerAccounts ledgerAccounts = seedLedgerAccounts("VND");

		TransferService.TransferResponse response = transferService.transfer(
				new TransferService.TransferRequest(
						"idem-transfer-5",
						seededAccounts.sourceAccountId(),
						seededAccounts.destinationAccountId(),
						1_500L,
						"VND",
						ledgerAccounts.debitLedgerAccountId(),
						ledgerAccounts.creditLedgerAccountId(),
						"balanced journal test",
						"tester",
						UUID.randomUUID(),
						UUID.randomUUID(),
						UUID.randomUUID(),
						"trace-transfer-5"));

		// Verify balanced journal invariant
		Map<String, Object> journalSums = jdbcTemplate.queryForMap(
				"""
				SELECT COALESCE(SUM(CASE WHEN entry_side = 'D' THEN amount_minor ELSE 0 END), 0) AS total_debit,
				       COALESCE(SUM(CASE WHEN entry_side = 'C' THEN amount_minor ELSE 0 END), 0) AS total_credit
				FROM ledger_postings
				WHERE journal_id = ?
				""",
				response.journalId());

		long totalDebit = ((Number) journalSums.get("total_debit")).longValue();
		long totalCredit = ((Number) journalSums.get("total_credit")).longValue();

		assertEquals(totalDebit, totalCredit, "Journal must be balanced: total debit must equal total credit");
		assertEquals(1_500L, totalDebit);
		assertEquals(1_500L, totalCredit);
	}

	@Test
	void transferBlockedWhenEodLock() {
		SeededAccounts seededAccounts = seedAccounts(10_000L, 10_000L, 5_000L, 5_000L, "VND");
		SeededLedgerAccounts ledgerAccounts = seedLedgerAccounts("VND");

		// Set system to EOD_LOCK
		systemModeService.setMode(SystemModeService.SystemMode.EOD_LOCK, "operator1");

		// Try to transfer - should fail
		assertThrows(
				CoreBankException.class,
				() -> transferService.transfer(
						new TransferService.TransferRequest(
								"idem-eod-transfer",
								seededAccounts.sourceAccountId(),
								seededAccounts.destinationAccountId(),
								1_000L,
								"VND",
								ledgerAccounts.debitLedgerAccountId(),
								ledgerAccounts.creditLedgerAccountId(),
								"transfer in EOD",
								"tester",
								UUID.randomUUID(),
								UUID.randomUUID(),
								UUID.randomUUID(),
								"trace-eod-transfer")));

		// Verify source account balances unchanged
		Map<String, Object> sourceAccountRow = jdbcTemplate.queryForMap(
				"SELECT posted_balance_minor, available_balance_minor FROM customer_accounts WHERE customer_account_id = ?",
				seededAccounts.sourceAccountId());

		assertEquals(10_000L, ((Number) sourceAccountRow.get("posted_balance_minor")).longValue());
		assertEquals(10_000L, ((Number) sourceAccountRow.get("available_balance_minor")).longValue());

		// Verify destination account balances unchanged
		Map<String, Object> destAccountRow = jdbcTemplate.queryForMap(
				"SELECT posted_balance_minor, available_balance_minor FROM customer_accounts WHERE customer_account_id = ?",
				seededAccounts.destinationAccountId());

		assertEquals(5_000L, ((Number) destAccountRow.get("posted_balance_minor")).longValue());
		assertEquals(5_000L, ((Number) destAccountRow.get("available_balance_minor")).longValue());

		// Verify idempotency failed
		assertEquals(1, count(
				"SELECT COUNT(*) FROM idempotency_keys WHERE idempotency_key = ? AND status = 'FAILED'",
				"idem-eod-transfer"));
	}

	@Test
	void transferBlockedWhenMaintenance() {
		SeededAccounts seededAccounts = seedAccounts(10_000L, 10_000L, 5_000L, 5_000L, "VND");
		SeededLedgerAccounts ledgerAccounts = seedLedgerAccounts("VND");

		// Set system to MAINTENANCE
		systemModeService.setMode(SystemModeService.SystemMode.MAINTENANCE, "operator1");

		// Try to transfer - should fail
		assertThrows(
				CoreBankException.class,
				() -> transferService.transfer(
						new TransferService.TransferRequest(
								"idem-maint-transfer",
								seededAccounts.sourceAccountId(),
								seededAccounts.destinationAccountId(),
								1_000L,
								"VND",
								ledgerAccounts.debitLedgerAccountId(),
								ledgerAccounts.creditLedgerAccountId(),
								"transfer in MAINTENANCE",
								"tester",
								UUID.randomUUID(),
								UUID.randomUUID(),
								UUID.randomUUID(),
								"trace-maint-transfer")));

		// Verify source account balances unchanged
		Map<String, Object> sourceAccountRow = jdbcTemplate.queryForMap(
				"SELECT posted_balance_minor, available_balance_minor FROM customer_accounts WHERE customer_account_id = ?",
				seededAccounts.sourceAccountId());

		assertEquals(10_000L, ((Number) sourceAccountRow.get("posted_balance_minor")).longValue());
		assertEquals(10_000L, ((Number) sourceAccountRow.get("available_balance_minor")).longValue());

		// Verify destination account balances unchanged
		Map<String, Object> destAccountRow = jdbcTemplate.queryForMap(
				"SELECT posted_balance_minor, available_balance_minor FROM customer_accounts WHERE customer_account_id = ?",
				seededAccounts.destinationAccountId());

		assertEquals(5_000L, ((Number) destAccountRow.get("posted_balance_minor")).longValue());
		assertEquals(5_000L, ((Number) destAccountRow.get("available_balance_minor")).longValue());

		// Verify idempotency failed
		assertEquals(1, count(
				"SELECT COUNT(*) FROM idempotency_keys WHERE idempotency_key = ? AND status = 'FAILED'",
				"idem-maint-transfer"));
	}

	@Test
	void concurrentOppositeDirectionTransfersDoNotDeadlockAndPreserveBalances() throws Exception {
		SeededAccounts seededAccounts = seedAccounts(10_000L, 10_000L, 5_000L, 5_000L, "VND");
		SeededLedgerAccounts ledgerAccounts = seedLedgerAccounts("VND");

		TransferService.TransferRequest firstRequest = new TransferService.TransferRequest(
				"idem-transfer-concurrent-a2b",
				seededAccounts.sourceAccountId(),
				seededAccounts.destinationAccountId(),
				1_000L,
				"VND",
				ledgerAccounts.debitLedgerAccountId(),
				ledgerAccounts.creditLedgerAccountId(),
				"A to B",
				"tester",
				UUID.randomUUID(),
				UUID.randomUUID(),
				UUID.randomUUID(),
				"trace-transfer-concurrent-a2b");

		TransferService.TransferRequest secondRequest = new TransferService.TransferRequest(
				"idem-transfer-concurrent-b2a",
				seededAccounts.destinationAccountId(),
				seededAccounts.sourceAccountId(),
				2_000L,
				"VND",
				ledgerAccounts.debitLedgerAccountId(),
				ledgerAccounts.creditLedgerAccountId(),
				"B to A",
				"tester",
				UUID.randomUUID(),
				UUID.randomUUID(),
				UUID.randomUUID(),
				"trace-transfer-concurrent-b2a");

		List<InvocationOutcome<TransferService.TransferResponse>> outcomes = invokeConcurrently(
				() -> transferService.transfer(firstRequest),
				() -> transferService.transfer(secondRequest));

		assertEquals(2L, outcomes.stream().filter(outcome -> outcome.response() != null).count());
		assertEquals(0L, outcomes.stream().filter(outcome -> outcome.error() != null).count());

		Map<String, Object> sourceAccountRow = jdbcTemplate.queryForMap(
				"SELECT posted_balance_minor, available_balance_minor FROM customer_accounts WHERE customer_account_id = ?",
				seededAccounts.sourceAccountId());
		Map<String, Object> destinationAccountRow = jdbcTemplate.queryForMap(
				"SELECT posted_balance_minor, available_balance_minor FROM customer_accounts WHERE customer_account_id = ?",
				seededAccounts.destinationAccountId());

		assertEquals(11_000L, ((Number) sourceAccountRow.get("posted_balance_minor")).longValue());
		assertEquals(11_000L, ((Number) sourceAccountRow.get("available_balance_minor")).longValue());
		assertEquals(4_000L, ((Number) destinationAccountRow.get("posted_balance_minor")).longValue());
		assertEquals(4_000L, ((Number) destinationAccountRow.get("available_balance_minor")).longValue());

		assertEquals(2, count(
				"SELECT COUNT(*) FROM ledger_journals WHERE correlation_id IN (?, ?)",
				firstRequest.correlationId(),
				secondRequest.correlationId()));
		assertEquals(4, count(
				"""
				SELECT COUNT(*)
				FROM ledger_postings lp
				JOIN ledger_journals lj ON lj.journal_id = lp.journal_id
				WHERE lj.correlation_id IN (?, ?)
				""",
				firstRequest.correlationId(),
				secondRequest.correlationId()));
	}

	@Test
	void concurrentCompetingTransfersCannotOverspendSameSourceAccount() throws Exception {
		SeededAccounts firstPair = seedAccounts(10_000L, 10_000L, 0L, 0L, "VND");
		SeededAccounts secondPair = seedAccounts(0L, 0L, 0L, 0L, "VND");
		SeededLedgerAccounts ledgerAccounts = seedLedgerAccounts("VND");

		UUID sourceAccountId = firstPair.sourceAccountId();
		UUID firstDestinationAccountId = firstPair.destinationAccountId();
		UUID secondDestinationAccountId = secondPair.destinationAccountId();

		TransferService.TransferRequest firstRequest = new TransferService.TransferRequest(
				"idem-transfer-race-1",
				sourceAccountId,
				firstDestinationAccountId,
				7_000L,
				"VND",
				ledgerAccounts.debitLedgerAccountId(),
				ledgerAccounts.creditLedgerAccountId(),
				"race 1",
				"tester",
				UUID.randomUUID(),
				UUID.randomUUID(),
				UUID.randomUUID(),
				"trace-transfer-race-1");

		TransferService.TransferRequest secondRequest = new TransferService.TransferRequest(
				"idem-transfer-race-2",
				sourceAccountId,
				secondDestinationAccountId,
				7_000L,
				"VND",
				ledgerAccounts.debitLedgerAccountId(),
				ledgerAccounts.creditLedgerAccountId(),
				"race 2",
				"tester",
				UUID.randomUUID(),
				UUID.randomUUID(),
				UUID.randomUUID(),
				"trace-transfer-race-2");

		List<InvocationOutcome<TransferService.TransferResponse>> outcomes = invokeConcurrently(
				() -> transferService.transfer(firstRequest),
				() -> transferService.transfer(secondRequest));

		assertEquals(1L, outcomes.stream().filter(outcome -> outcome.response() != null).count());
		assertEquals(1L, outcomes.stream().filter(outcome -> outcome.error() != null).count());
		assertTrue(outcomes.stream()
				.filter(outcome -> outcome.error() != null)
				.anyMatch(outcome -> outcome.error() instanceof InsufficientFundsException));

		Map<String, Object> sourceAccountRow = jdbcTemplate.queryForMap(
				"SELECT posted_balance_minor, available_balance_minor FROM customer_accounts WHERE customer_account_id = ?",
				sourceAccountId);
		assertEquals(3_000L, ((Number) sourceAccountRow.get("posted_balance_minor")).longValue());
		assertEquals(3_000L, ((Number) sourceAccountRow.get("available_balance_minor")).longValue());

		long firstDestinationPosted = ((Number) jdbcTemplate.queryForMap(
				"SELECT posted_balance_minor FROM customer_accounts WHERE customer_account_id = ?",
				firstDestinationAccountId).get("posted_balance_minor")).longValue();
		long secondDestinationPosted = ((Number) jdbcTemplate.queryForMap(
				"SELECT posted_balance_minor FROM customer_accounts WHERE customer_account_id = ?",
				secondDestinationAccountId).get("posted_balance_minor")).longValue();
		assertEquals(7_000L, firstDestinationPosted + secondDestinationPosted);

		assertEquals(1, count(
				"SELECT COUNT(*) FROM ledger_journals WHERE correlation_id IN (?, ?)",
				firstRequest.correlationId(),
				secondRequest.correlationId()));
		assertEquals(1, count("SELECT COUNT(*) FROM idempotency_keys WHERE status = 'SUCCEEDED' AND idempotency_key IN ('idem-transfer-race-1', 'idem-transfer-race-2')"));
		assertEquals(1, count("SELECT COUNT(*) FROM idempotency_keys WHERE status = 'FAILED' AND idempotency_key IN ('idem-transfer-race-1', 'idem-transfer-race-2')"));
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

		// Seed source customer
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
				sourceCustomerId,
				"Source Customer",
				"source@example.com",
				"0123456789",
				"LOW");

		// Seed source product
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
				sourceProductId,
				"CHK-" + sourceProductId.toString().substring(0, 8),
				"Checking",
				"CHECKING",
				currency);

		// Seed source account
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
				sourceAccountId,
				sourceCustomerId,
				sourceProductId,
				"ACCT-" + sourceAccountId.toString().substring(0, 8),
				currency,
				sourcePostedBalanceMinor,
				sourceAvailableBalanceMinor);

		// Seed destination customer
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
				destCustomerId,
				"Destination Customer",
				"dest@example.com",
				"0987654321",
				"LOW");

		// Seed destination product
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
				destProductId,
				"CHK-" + destProductId.toString().substring(0, 8),
				"Checking",
				"CHECKING",
				currency);

		// Seed destination account
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
				"Internal Transfer Asset",
				currency);

		return new SeededLedgerAccounts(debitLedgerAccountId, creditLedgerAccountId);
	}

	private int count(String sql, Object... args) {
		Integer count = jdbcTemplate.queryForObject(sql, Integer.class, args);
		return count == null ? 0 : count;
	}

	private <T> List<InvocationOutcome<T>> invokeConcurrently(Callable<T> first, Callable<T> second) throws Exception {
		ExecutorService executorService = Executors.newFixedThreadPool(2);
		CountDownLatch ready = new CountDownLatch(2);
		CountDownLatch start = new CountDownLatch(1);

		try {
			Future<InvocationOutcome<T>> firstFuture = executorService.submit(() -> executeConcurrentCallable(first, ready, start));
			Future<InvocationOutcome<T>> secondFuture = executorService.submit(() -> executeConcurrentCallable(second, ready, start));

			assertTrue(ready.await(5, TimeUnit.SECONDS));
			start.countDown();

			List<InvocationOutcome<T>> outcomes = new ArrayList<>();
			outcomes.add(firstFuture.get(20, TimeUnit.SECONDS));
			outcomes.add(secondFuture.get(20, TimeUnit.SECONDS));
			return outcomes;
		} finally {
			executorService.shutdownNow();
		}
	}

	private <T> InvocationOutcome<T> executeConcurrentCallable(
			Callable<T> callable,
			CountDownLatch ready,
			CountDownLatch start) {
		ready.countDown();
		try {
			assertTrue(start.await(5, TimeUnit.SECONDS));
			return new InvocationOutcome<>(callable.call(), null);
		} catch (Throwable throwable) {
			return new InvocationOutcome<>(null, throwable);
		}
	}

	private record SeededAccounts(UUID sourceAccountId, UUID destinationAccountId) {
	}

	private record SeededLedgerAccounts(UUID debitLedgerAccountId, UUID creditLedgerAccountId) {
	}

	private record InvocationOutcome<T>(T response, Throwable error) {
	}
}
