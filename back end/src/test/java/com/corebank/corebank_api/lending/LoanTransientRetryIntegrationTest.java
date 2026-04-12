package com.corebank.corebank_api.lending;

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
class LoanTransientRetryIntegrationTest {

	@Autowired
	private LoanApplicationService loanApplicationService;

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
	void disburseLoan_retriesTransientLockFailure_thenSucceedsWithSingleSideEffects() {
		SeededData seeded = seedLoanDisbursementData("VND", 2_000_000L, 2_000_000L);
		String idempotencyKey = "idem-loan-transient-disburse-" + UUID.randomUUID();
		AtomicInteger lockInvocation = new AtomicInteger();

		doAnswer(invocation -> {
			if (lockInvocation.incrementAndGet() == 1) {
				throw new CannotAcquireLockException("synthetic lock timeout", new SQLException("lock timeout", "55P03"));
			}
			return invocation.callRealMethod();
		}).when(accountBalanceRepository).lockById(any(UUID.class));

		LoanApplicationService.LoanDisbursementResponse response = loanApplicationService.disburseLoan(
				new LoanApplicationService.LoanDisbursementRequest(
						idempotencyKey,
						seeded.borrowerAccountId(),
						seeded.productId(),
						seeded.productVersionId(),
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
						"trace-loan-transient-disburse"));

		assertNotNull(response.contractId());
		assertNotNull(response.journalId());
		assertEquals(2, lockInvocation.get());
		assertEquals(1, count("SELECT COUNT(*) FROM loan_contracts WHERE contract_id = ?", response.contractId()));
		assertEquals(12, count("SELECT COUNT(*) FROM repayment_schedules WHERE contract_id = ?", response.contractId()));
		assertEquals(1, count("SELECT COUNT(*) FROM loan_events WHERE contract_id = ? AND event_type = 'DISBURSED'", response.contractId()));
		assertEquals(1, count("SELECT COUNT(*) FROM audit_events WHERE action = 'LOAN_DISBURSED' AND resource_id = ?", response.contractId().toString()));
		assertEquals(1, count("SELECT COUNT(*) FROM outbox_events WHERE event_type = 'LOAN_DISBURSED' AND aggregate_id = ?", response.contractId().toString()));
		assertEquals(1, count("SELECT COUNT(*) FROM idempotency_keys WHERE idempotency_key = ? AND status = 'SUCCEEDED'", idempotencyKey));
	}

	@Test
	void repayLoan_retriesTransientDeadlock_thenSucceedsWithSingleSideEffects() {
		SeededData seeded = seedLoanDisbursementData("VND", 2_000_000L, 2_000_000L);
		AtomicInteger repaymentJournalInvocation = new AtomicInteger();
		String repayIdempotencyKey = "idem-loan-transient-repay-" + UUID.randomUUID();

		LoanApplicationService.LoanDisbursementResponse disbursement = loanApplicationService.disburseLoan(
				new LoanApplicationService.LoanDisbursementRequest(
						"idem-loan-transient-repay-disburse-" + UUID.randomUUID(),
						seeded.borrowerAccountId(),
						seeded.productId(),
						seeded.productVersionId(),
						300_000L,
						"VND",
						12.0,
						3,
						seeded.loanReceivableLedgerAccountId(),
						seeded.customerSettlementLedgerAccountId(),
						"loan-officer",
						UUID.randomUUID(),
						UUID.randomUUID(),
						UUID.randomUUID(),
						"trace-loan-transient-repay-disburse"));

		doAnswer(invocation -> {
			LedgerCommandService.PostJournalCommand command = invocation.getArgument(0);
			if ("LOAN_REPAYMENT".equals(command.journalType()) && repaymentJournalInvocation.incrementAndGet() == 1) {
				throw new DeadlockLoserDataAccessException("synthetic deadlock", new SQLException("deadlock", "40P01"));
			}
			return invocation.callRealMethod();
		}).when(ledgerCommandService).postJournal(any(LedgerCommandService.PostJournalCommand.class));

		LoanApplicationService.LoanRepaymentResponse repayment = loanApplicationService.repayLoan(
				new LoanApplicationService.LoanRepaymentRequest(
						repayIdempotencyKey,
						disbursement.contractId(),
						seeded.borrowerAccountId(),
						9_500L,
						"VND",
						seeded.customerSettlementLedgerAccountId(),
						seeded.loanReceivableLedgerAccountId(),
						"loan-officer",
						UUID.randomUUID(),
						UUID.randomUUID(),
						UUID.randomUUID(),
						"trace-loan-transient-repay"));

		assertNotNull(repayment.journalId());
		assertEquals(2, repaymentJournalInvocation.get());
		assertEquals(1, count("SELECT COUNT(*) FROM loan_events WHERE contract_id = ? AND event_type = 'REPAYMENT'", disbursement.contractId()));
		assertEquals(1, count(
				"SELECT COUNT(*) FROM ledger_journals WHERE reference_type = 'LOAN_CONTRACT' AND reference_id = ? AND journal_type = 'LOAN_REPAYMENT'",
				disbursement.contractId()));
		assertEquals(1, count("SELECT COUNT(*) FROM audit_events WHERE action = 'LOAN_REPAID' AND resource_id = ?", disbursement.contractId().toString()));
		assertEquals(1, count("SELECT COUNT(*) FROM outbox_events WHERE event_type = 'LOAN_REPAID' AND aggregate_id = ?", disbursement.contractId().toString()));
		assertEquals(1, count("SELECT COUNT(*) FROM idempotency_keys WHERE idempotency_key = ? AND status = 'SUCCEEDED'", repayIdempotencyKey));
	}

	@Test
	void disburseLoan_doesNotRetryNonTransientFailure_andLeavesIdempotencyFailed() {
		SeededData seeded = seedLoanDisbursementData("VND", 1_000_000L, 1_000_000L);
		String idempotencyKey = "idem-loan-non-transient-disburse-" + UUID.randomUUID();

		doAnswer(invocation -> {
			throw new CoreBankException("synthetic non-transient failure");
		}).when(accountBalanceRepository).lockById(any(UUID.class));

		assertThrows(CoreBankException.class, () -> loanApplicationService.disburseLoan(
				new LoanApplicationService.LoanDisbursementRequest(
						idempotencyKey,
						seeded.borrowerAccountId(),
						seeded.productId(),
						seeded.productVersionId(),
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
						"trace-loan-non-transient-disburse")));

		verify(accountBalanceRepository, times(1)).lockById(any(UUID.class));
		assertEquals(0, count("SELECT COUNT(*) FROM loan_contracts WHERE borrower_account_id = ?", seeded.borrowerAccountId()));
		assertEquals(1, count("SELECT COUNT(*) FROM idempotency_keys WHERE idempotency_key = ? AND status = 'FAILED'", idempotencyKey));
	}

	private SeededData seedLoanDisbursementData(String currency, long postedBalance, long availableBalance) {
		UUID customerId = UUID.randomUUID();
		UUID productId = UUID.randomUUID();
		UUID productVersionId = UUID.randomUUID();
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

		return new SeededData(
				productId,
				productVersionId,
				borrowerAccountId,
				loanReceivableLedgerAccountId,
				customerSettlementLedgerAccountId);
	}

	private int count(String sql, Object... args) {
		Integer value = jdbcTemplate.queryForObject(sql, Integer.class, args);
		return value == null ? 0 : value;
	}

	private record SeededData(
			UUID productId,
			UUID productVersionId,
			UUID borrowerAccountId,
			UUID loanReceivableLedgerAccountId,
			UUID customerSettlementLedgerAccountId) {
	}
}
