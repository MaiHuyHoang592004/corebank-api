package com.corebank.corebank_api.transfer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.corebank.corebank_api.TestcontainersConfiguration;
import com.corebank.corebank_api.common.IdempotencyConflictException;
import com.corebank.corebank_api.common.InsufficientFundsException;
import java.util.Map;
import java.util.UUID;
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

	@Test
	void transferReducesAvailableBalanceAndWritesAuditAndOutbox() {
		SeededAccounts seededAccounts = seedAccounts(10_000L, 10_000L, 5_000L, 5_000L, "VND");
		SeededLedgerAccounts ledgerAccounts = seedLedgerAccounts("VND");

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
						UUID.randomUUID(),
						UUID.randomUUID(),
						UUID.randomUUID(),
						"trace-transfer-1"));

		assertNotNull(response.journalId());
		assertEquals(seededAccounts.sourceAccountId(), response.sourceAccountId());
		assertEquals(seededAccounts.destinationAccountId(), response.destinationAccountId());
		assertEquals(3_000L, response.amountMinor());
		assertEquals(10_000L, response.sourcePostedBalanceMinor());
		assertEquals(10_000L, response.sourceAvailableBalanceBeforeMinor());
		assertEquals(7_000L, response.sourceAvailableBalanceAfterMinor());
		assertEquals(5_000L, response.destinationPostedBalanceMinor());
		assertEquals(5_000L, response.destinationAvailableBalanceBeforeMinor());
		assertEquals(8_000L, response.destinationAvailableBalanceAfterMinor());
		assertEquals("COMPLETED", response.status());

		// Verify source account balances - posted balance should NOT change for transfers
		Map<String, Object> sourceAccountRow = jdbcTemplate.queryForMap(
				"SELECT posted_balance_minor, available_balance_minor FROM customer_accounts WHERE customer_account_id = ?",
				seededAccounts.sourceAccountId());

		assertEquals(10_000L, ((Number) sourceAccountRow.get("posted_balance_minor")).longValue());
		assertEquals(7_000L, ((Number) sourceAccountRow.get("available_balance_minor")).longValue());

		// Verify destination account balances - posted balance should NOT change for transfers
		Map<String, Object> destAccountRow = jdbcTemplate.queryForMap(
				"SELECT posted_balance_minor, available_balance_minor FROM customer_accounts WHERE customer_account_id = ?",
				seededAccounts.destinationAccountId());

		assertEquals(5_000L, ((Number) destAccountRow.get("posted_balance_minor")).longValue());
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
				"SELECT COUNT(*) FROM outbox_messages WHERE event_type = 'TRANSFER_COMPLETED' AND aggregate_id = ?",
				response.journalId().toString()));

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
				"SELECT COUNT(*) FROM outbox_messages WHERE event_type = 'TRANSFER_COMPLETED' AND aggregate_id = ?",
				first.journalId().toString()));
	}

	@Test
	void transferRejectsSameKeyWithDifferentPayload() {
		SeededAccounts seededAccounts = seedAccounts(10_000L, 10_000L, 5_000L, 5_000L, "VND");
		SeededLedgerAccounts ledgerAccounts = seedLedgerAccounts("VND");

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
						UUID.randomUUID(),
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
				"SELECT COUNT(*) FROM ledger_journals WHERE reference_type = 'TRANSFER'"));

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

	private record SeededAccounts(UUID sourceAccountId, UUID destinationAccountId) {
	}

	private record SeededLedgerAccounts(UUID debitLedgerAccountId, UUID creditLedgerAccountId) {
	}
}