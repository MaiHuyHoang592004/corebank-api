package com.corebank.corebank_api.lending;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.corebank.corebank_api.TestcontainersConfiguration;
import com.corebank.corebank_api.common.CoreBankException;
import com.corebank.corebank_api.ops.system.SystemModeService;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
class LoanApplicationServiceIntegrationTest {

	@Autowired
	private LoanApplicationService loanApplicationService;

	@Autowired
	private SystemModeService systemModeService;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@BeforeEach
	void setUp() {
		systemModeService.setMode(SystemModeService.SystemMode.RUNNING, "test");
	}

	@Test
	void disburseLoan_writesContractScheduleJournalAuditOutboxAndBalances() {
		SeededData seeded = seedLoanDisbursementData("VND", 2_000_000L, 2_000_000L);

		LoanApplicationService.LoanDisbursementRequest request = new LoanApplicationService.LoanDisbursementRequest(
				"idem-loan-disburse-1",
				seeded.borrowerAccountId(),
				seeded.productId(),
				UUID.randomUUID(),
				1_200_000L,
				"VND",
				12.0,
				12,
				seeded.loanReceivableLedgerAccountId(),
				seeded.customerSettlementLedgerAccountId(),
				"loan-officer",
				UUID.randomUUID(),
				UUID.randomUUID(),
				UUID.randomUUID(),
				"trace-loan-disburse-1");

		LoanApplicationService.LoanDisbursementResponse response = loanApplicationService.disburseLoan(request);

		assertNotNull(response.contractId());
		assertNotNull(response.journalId());
		assertEquals("ACTIVE", response.status());
		assertEquals(12, response.installmentCount());

		Map<String, Object> account = jdbcTemplate.queryForMap(
				"SELECT posted_balance_minor, available_balance_minor FROM customer_accounts WHERE customer_account_id = ?",
				seeded.borrowerAccountId());
		assertEquals(3_200_000L, ((Number) account.get("posted_balance_minor")).longValue());
		assertEquals(3_200_000L, ((Number) account.get("available_balance_minor")).longValue());

		assertEquals(1, count("SELECT COUNT(*) FROM loan_contracts WHERE contract_id = ?", response.contractId()));
		assertEquals(12, count("SELECT COUNT(*) FROM repayment_schedules WHERE contract_id = ?", response.contractId()));
		assertEquals(1, count("SELECT COUNT(*) FROM loan_events WHERE contract_id = ? AND event_type = 'DISBURSED'", response.contractId()));
		assertEquals(1, count("SELECT COUNT(*) FROM audit_events WHERE action = 'LOAN_DISBURSED' AND resource_id = ?", response.contractId().toString()));
		assertEquals(1, count("SELECT COUNT(*) FROM outbox_events WHERE event_type = 'LOAN_DISBURSED' AND aggregate_id = ?", response.contractId().toString()));
		assertEquals(1, count("SELECT COUNT(*) FROM idempotency_keys WHERE idempotency_key = 'idem-loan-disburse-1' AND status = 'SUCCEEDED'"));

		Map<String, Object> sums = jdbcTemplate.queryForMap(
				"""
				SELECT COALESCE(SUM(CASE WHEN entry_side='D' THEN amount_minor ELSE 0 END), 0) AS total_debit,
				       COALESCE(SUM(CASE WHEN entry_side='C' THEN amount_minor ELSE 0 END), 0) AS total_credit
				FROM ledger_postings
				WHERE journal_id = ?
				""",
				response.journalId());
		assertEquals(1_200_000L, ((Number) sums.get("total_debit")).longValue());
		assertEquals(1_200_000L, ((Number) sums.get("total_credit")).longValue());
	}

	@Test
	void disburseLoan_replayReturnsSameContractWithoutDoubleWrites() {
		SeededData seeded = seedLoanDisbursementData("VND", 1_000_000L, 1_000_000L);

		LoanApplicationService.LoanDisbursementRequest request = new LoanApplicationService.LoanDisbursementRequest(
				"idem-loan-disburse-2",
				seeded.borrowerAccountId(),
				seeded.productId(),
				UUID.randomUUID(),
				500_000L,
				"VND",
				10.0,
				6,
				seeded.loanReceivableLedgerAccountId(),
				seeded.customerSettlementLedgerAccountId(),
				"loan-officer",
				UUID.randomUUID(),
				UUID.randomUUID(),
				UUID.randomUUID(),
				"trace-loan-disburse-2");

		LoanApplicationService.LoanDisbursementResponse first = loanApplicationService.disburseLoan(request);
		LoanApplicationService.LoanDisbursementResponse second = loanApplicationService.disburseLoan(request);

		assertEquals(first.contractId(), second.contractId());
		assertEquals(first.journalId(), second.journalId());
		assertEquals(1, count("SELECT COUNT(*) FROM loan_contracts WHERE contract_id = ?", first.contractId()));
		assertEquals(1, count("SELECT COUNT(*) FROM outbox_events WHERE event_type = 'LOAN_DISBURSED' AND aggregate_id = ?", first.contractId().toString()));
	}

	@Test
	void disburseLoan_blockedWhenEodLock() {
		SeededData seeded = seedLoanDisbursementData("VND", 1_000_000L, 1_000_000L);
		systemModeService.setMode(SystemModeService.SystemMode.EOD_LOCK, "operator");

		LoanApplicationService.LoanDisbursementRequest request = new LoanApplicationService.LoanDisbursementRequest(
				"idem-loan-disburse-3",
				seeded.borrowerAccountId(),
				seeded.productId(),
				UUID.randomUUID(),
				300_000L,
				"VND",
				9.0,
				3,
				seeded.loanReceivableLedgerAccountId(),
				seeded.customerSettlementLedgerAccountId(),
				"loan-officer",
				UUID.randomUUID(),
				UUID.randomUUID(),
				UUID.randomUUID(),
				"trace-loan-disburse-3");

		assertThrows(CoreBankException.class, () -> loanApplicationService.disburseLoan(request));
		assertEquals(0, count("SELECT COUNT(*) FROM loan_contracts"));
		assertEquals(1, count("SELECT COUNT(*) FROM idempotency_keys WHERE idempotency_key = 'idem-loan-disburse-3' AND status = 'FAILED'"));
	}

	private SeededData seedLoanDisbursementData(String currency, long postedBalance, long availableBalance) {
		UUID customerId = UUID.randomUUID();
		UUID productId = UUID.randomUUID();
		UUID borrowerAccountId = UUID.randomUUID();
		UUID loanReceivableLedgerAccountId = UUID.randomUUID();
		UUID customerSettlementLedgerAccountId = UUID.randomUUID();

		jdbcTemplate.update(
				"""
				INSERT INTO customers (customer_id, customer_type, full_name, email, phone, status, risk_band)
				VALUES (?, 'INDIVIDUAL', ?, ?, ?, 'ACTIVE', 'LOW')
				""",
				customerId,
				"Loan Borrower",
				"borrower@example.com",
				"0900000001");

		jdbcTemplate.update(
				"""
				INSERT INTO bank_products (product_id, product_code, product_name, product_type, currency, status)
				VALUES (?, ?, ?, 'LOAN', ?, 'ACTIVE')
				""",
				productId,
				"LN-" + productId.toString().substring(0, 8),
				"Personal Loan",
				currency);

		jdbcTemplate.update(
				"""
				INSERT INTO customer_accounts (
				    customer_account_id, customer_id, product_id, account_number, currency, status,
				    posted_balance_minor, available_balance_minor, version, created_at, updated_at
				) VALUES (?, ?, ?, ?, ?, 'ACTIVE', ?, ?, 0, now(), now())
				""",
				borrowerAccountId,
				customerId,
				productId,
				"LN-ACC-" + borrowerAccountId.toString().substring(0, 8),
				currency,
				postedBalance,
				availableBalance);

		jdbcTemplate.update(
				"""
				INSERT INTO ledger_accounts (
				    ledger_account_id, account_code, account_name, account_type, currency, is_active
				) VALUES (?, ?, ?, 'ASSET', ?, true)
				""",
				loanReceivableLedgerAccountId,
				"LN-RECV-" + loanReceivableLedgerAccountId.toString().substring(0, 8),
				"Loan Receivable",
				currency);

		jdbcTemplate.update(
				"""
				INSERT INTO ledger_accounts (
				    ledger_account_id, account_code, account_name, account_type, currency, is_active
				) VALUES (?, ?, ?, 'LIABILITY', ?, true)
				""",
				customerSettlementLedgerAccountId,
				"LN-CUST-" + customerSettlementLedgerAccountId.toString().substring(0, 8),
				"Customer Settlement",
				currency);

		return new SeededData(productId, borrowerAccountId, loanReceivableLedgerAccountId, customerSettlementLedgerAccountId);
	}

	private int count(String sql, Object... args) {
		Integer value = jdbcTemplate.queryForObject(sql, Integer.class, args);
		return value == null ? 0 : value;
	}

	private record SeededData(
			UUID productId,
			UUID borrowerAccountId,
			UUID loanReceivableLedgerAccountId,
			UUID customerSettlementLedgerAccountId) {
	}
}