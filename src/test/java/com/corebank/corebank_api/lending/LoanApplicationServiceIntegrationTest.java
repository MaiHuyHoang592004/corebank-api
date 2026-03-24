package com.corebank.corebank_api.lending;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.corebank.corebank_api.TestcontainersConfiguration;
import com.corebank.corebank_api.common.CoreBankException;
import com.corebank.corebank_api.common.IdempotencyConflictException;
import java.time.LocalDate;
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
		UUID correlationId = UUID.randomUUID();
		UUID requestId = UUID.randomUUID();

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
				correlationId,
				requestId,
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
		Map<String, Object> outboxEnvelope = jdbcTemplate.queryForMap(
				"""
				SELECT event_data->>'schemaVersion' AS schema_version,
				       event_data->>'correlationId' AS correlation_id,
				       event_data->>'requestId' AS request_id,
				       event_data->>'actor' AS actor
				FROM outbox_events
				WHERE event_type = 'LOAN_DISBURSED' AND aggregate_id = ?
				ORDER BY id DESC
				LIMIT 1
				""",
				response.contractId().toString());
		assertEquals("v1", outboxEnvelope.get("schema_version"));
		assertEquals(correlationId.toString(), outboxEnvelope.get("correlation_id"));
		assertEquals(requestId.toString(), outboxEnvelope.get("request_id"));
		assertEquals("loan-officer", outboxEnvelope.get("actor"));
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
		assertEquals(0, count("SELECT COUNT(*) FROM loan_contracts WHERE borrower_account_id = ?", seeded.borrowerAccountId()));
		assertEquals(1, count("SELECT COUNT(*) FROM idempotency_keys WHERE idempotency_key = 'idem-loan-disburse-3' AND status = 'FAILED'"));
	}

	@Test
	void repayLoan_allocatesFeesBeforeInterestAndPrincipalWithinInstallment_withNonZeroFeeInterestPrincipal() {
		SeededData seeded = seedLoanDisbursementData("VND", 1_000_000L, 1_000_000L);

		LoanApplicationService.LoanDisbursementResponse disbursement = loanApplicationService.disburseLoan(
				new LoanApplicationService.LoanDisbursementRequest(
						"idem-loan-disburse-r1",
						seeded.borrowerAccountId(),
						seeded.productId(),
						UUID.randomUUID(),
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
						"trace-loan-disburse-r1"));

		jdbcTemplate.update(
				"""
				UPDATE repayment_schedules
				SET fees_due_minor = ?
				WHERE contract_id = ?
				  AND installment_no = 1
				""",
				4_000L,
				disbursement.contractId());

		LoanApplicationService.LoanRepaymentResponse repayment = loanApplicationService.repayLoan(
				new LoanApplicationService.LoanRepaymentRequest(
						"idem-loan-repay-r1",
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
						"trace-loan-repay-r1"));

		assertEquals(9_500L, repayment.amountMinor());
		assertEquals(2_500L, repayment.principalPaidMinor());
		assertEquals(3_000L, repayment.interestPaidMinor());
		assertEquals(4_000L, repayment.feesPaidMinor());
		assertEquals(297_500L, repayment.outstandingPrincipalAfterMinor());
		assertEquals("ACTIVE", repayment.status());
		assertEquals(1, repayment.updatedInstallmentCount());

		Map<String, Object> contract = jdbcTemplate.queryForMap(
				"SELECT outstanding_principal_minor, status FROM loan_contracts WHERE contract_id = ?",
				disbursement.contractId());
		assertEquals(297_500L, ((Number) contract.get("outstanding_principal_minor")).longValue());
		assertEquals("ACTIVE", contract.get("status"));

		Map<String, Object> firstInstallment = jdbcTemplate.queryForMap(
				"""
				SELECT principal_paid_minor, interest_paid_minor, fees_paid_minor, status
				FROM repayment_schedules
				WHERE contract_id = ? AND installment_no = 1
				""",
				disbursement.contractId());
		assertEquals(2_500L, ((Number) firstInstallment.get("principal_paid_minor")).longValue());
		assertEquals(3_000L, ((Number) firstInstallment.get("interest_paid_minor")).longValue());
		assertEquals(4_000L, ((Number) firstInstallment.get("fees_paid_minor")).longValue());
		assertEquals("PARTIALLY_PAID", firstInstallment.get("status"));

		Map<String, Object> secondInstallment = jdbcTemplate.queryForMap(
				"""
				SELECT principal_paid_minor, interest_paid_minor, fees_paid_minor
				FROM repayment_schedules
				WHERE contract_id = ? AND installment_no = 2
				""",
				disbursement.contractId());
		assertEquals(0L, ((Number) secondInstallment.get("principal_paid_minor")).longValue());
		assertEquals(0L, ((Number) secondInstallment.get("interest_paid_minor")).longValue());
		assertEquals(0L, ((Number) secondInstallment.get("fees_paid_minor")).longValue());

		assertEquals(1, count("SELECT COUNT(*) FROM loan_events WHERE contract_id = ? AND event_type = 'REPAYMENT'", disbursement.contractId()));
		assertEquals(1, count("SELECT COUNT(*) FROM audit_events WHERE action = 'LOAN_REPAID' AND resource_id = ?", disbursement.contractId().toString()));
		assertEquals(1, count("SELECT COUNT(*) FROM outbox_events WHERE event_type = 'LOAN_REPAID' AND aggregate_id = ?", disbursement.contractId().toString()));
		assertEquals(1, count("SELECT COUNT(*) FROM idempotency_keys WHERE idempotency_key = 'idem-loan-repay-r1' AND status = 'SUCCEEDED'"));

		Map<String, Object> sums = jdbcTemplate.queryForMap(
				"""
				SELECT COALESCE(SUM(CASE WHEN entry_side='D' THEN amount_minor ELSE 0 END), 0) AS total_debit,
				       COALESCE(SUM(CASE WHEN entry_side='C' THEN amount_minor ELSE 0 END), 0) AS total_credit
				FROM ledger_postings
				WHERE journal_id = ?
				""",
				repayment.journalId());
		assertEquals(9_500L, ((Number) sums.get("total_debit")).longValue());
		assertEquals(9_500L, ((Number) sums.get("total_credit")).longValue());
	}

	@Test
	void repayLoan_replayReturnsSameResponseWithoutDoubleWrites() {
		SeededData seeded = seedLoanDisbursementData("VND", 1_000_000L, 1_000_000L);
		LoanApplicationService.LoanDisbursementResponse disbursement = disburseLoanForRepayment(seeded, "repay-replay");

		jdbcTemplate.update(
				"""
				UPDATE repayment_schedules
				SET fees_due_minor = ?
				WHERE contract_id = ?
				  AND installment_no = 1
				""",
				4_000L,
				disbursement.contractId());

		LoanApplicationService.LoanRepaymentRequest request = new LoanApplicationService.LoanRepaymentRequest(
				"idem-loan-repay-replay-1",
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
				"trace-loan-repay-replay-1");

		LoanApplicationService.LoanRepaymentResponse first = loanApplicationService.repayLoan(request);

		int loanEventsBefore = count(
				"SELECT COUNT(*) FROM loan_events WHERE contract_id = ? AND event_type = 'REPAYMENT'",
				disbursement.contractId());
		int auditEventsBefore = count(
				"SELECT COUNT(*) FROM audit_events WHERE action = 'LOAN_REPAID' AND resource_id = ?",
				disbursement.contractId().toString());
		int outboxEventsBefore = count(
				"SELECT COUNT(*) FROM outbox_events WHERE event_type = 'LOAN_REPAID' AND aggregate_id = ?",
				disbursement.contractId().toString());
		int postingsBefore = count("SELECT COUNT(*) FROM ledger_postings WHERE journal_id = ?", first.journalId());

		LoanApplicationService.LoanRepaymentResponse second = loanApplicationService.repayLoan(request);

		assertEquals(first.contractId(), second.contractId());
		assertEquals(first.journalId(), second.journalId());
		assertEquals(first.principalPaidMinor(), second.principalPaidMinor());
		assertEquals(first.interestPaidMinor(), second.interestPaidMinor());
		assertEquals(first.feesPaidMinor(), second.feesPaidMinor());
		assertEquals(
				loanEventsBefore,
				count("SELECT COUNT(*) FROM loan_events WHERE contract_id = ? AND event_type = 'REPAYMENT'", disbursement.contractId()));
		assertEquals(
				auditEventsBefore,
				count("SELECT COUNT(*) FROM audit_events WHERE action = 'LOAN_REPAID' AND resource_id = ?", disbursement.contractId().toString()));
		assertEquals(
				outboxEventsBefore,
				count("SELECT COUNT(*) FROM outbox_events WHERE event_type = 'LOAN_REPAID' AND aggregate_id = ?", disbursement.contractId().toString()));
		assertEquals(postingsBefore, count("SELECT COUNT(*) FROM ledger_postings WHERE journal_id = ?", first.journalId()));
		assertEquals(1, count("SELECT COUNT(*) FROM idempotency_keys WHERE idempotency_key = 'idem-loan-repay-replay-1' AND status = 'SUCCEEDED'"));
	}

	@Test
	void repayLoan_sameIdempotencyKeyDifferentPayload_isRejectedWithoutDoubleWrites() {
		SeededData seeded = seedLoanDisbursementData("VND", 1_000_000L, 1_000_000L);
		LoanApplicationService.LoanDisbursementResponse disbursement = disburseLoanForRepayment(seeded, "repay-mismatch");

		jdbcTemplate.update(
				"""
				UPDATE repayment_schedules
				SET fees_due_minor = ?
				WHERE contract_id = ?
				  AND installment_no = 1
				""",
				4_000L,
				disbursement.contractId());

		String idempotencyKey = "idem-loan-repay-mismatch-1";
		LoanApplicationService.LoanRepaymentRequest firstRequest = new LoanApplicationService.LoanRepaymentRequest(
				idempotencyKey,
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
				"trace-loan-repay-mismatch-1");

		LoanApplicationService.LoanRepaymentResponse first = loanApplicationService.repayLoan(firstRequest);

		int loanEventsBefore = count(
				"SELECT COUNT(*) FROM loan_events WHERE contract_id = ? AND event_type = 'REPAYMENT'",
				disbursement.contractId());
		int auditEventsBefore = count(
				"SELECT COUNT(*) FROM audit_events WHERE action = 'LOAN_REPAID' AND resource_id = ?",
				disbursement.contractId().toString());
		int outboxEventsBefore = count(
				"SELECT COUNT(*) FROM outbox_events WHERE event_type = 'LOAN_REPAID' AND aggregate_id = ?",
				disbursement.contractId().toString());
		int postingsBefore = count("SELECT COUNT(*) FROM ledger_postings WHERE journal_id = ?", first.journalId());

		LoanApplicationService.LoanRepaymentRequest mismatchRequest = new LoanApplicationService.LoanRepaymentRequest(
				idempotencyKey,
				disbursement.contractId(),
				seeded.borrowerAccountId(),
				9_600L,
				"VND",
				seeded.customerSettlementLedgerAccountId(),
				seeded.loanReceivableLedgerAccountId(),
				"loan-officer",
				UUID.randomUUID(),
				UUID.randomUUID(),
				UUID.randomUUID(),
				"trace-loan-repay-mismatch-2");

		assertThrows(IdempotencyConflictException.class, () -> loanApplicationService.repayLoan(mismatchRequest));
		assertEquals(
				loanEventsBefore,
				count("SELECT COUNT(*) FROM loan_events WHERE contract_id = ? AND event_type = 'REPAYMENT'", disbursement.contractId()));
		assertEquals(
				auditEventsBefore,
				count("SELECT COUNT(*) FROM audit_events WHERE action = 'LOAN_REPAID' AND resource_id = ?", disbursement.contractId().toString()));
		assertEquals(
				outboxEventsBefore,
				count("SELECT COUNT(*) FROM outbox_events WHERE event_type = 'LOAN_REPAID' AND aggregate_id = ?", disbursement.contractId().toString()));
		assertEquals(postingsBefore, count("SELECT COUNT(*) FROM ledger_postings WHERE journal_id = ?", first.journalId()));
		assertEquals(1, count("SELECT COUNT(*) FROM idempotency_keys WHERE idempotency_key = ? AND status = 'SUCCEEDED'", idempotencyKey));
	}

	@Test
	void markOverdueInstallments_marksPendingInstallmentAndAppendsEvents() {
		SeededData seeded = seedLoanDisbursementData("VND", 1_000_000L, 1_000_000L);
		LoanApplicationService.LoanDisbursementResponse disbursement = disburseLoanForRepayment(seeded, "overdue-happy");

		LocalDate asOfDate = LocalDate.now();
		jdbcTemplate.update(
				"""
				UPDATE repayment_schedules
				SET due_date = ?
				WHERE contract_id = ?
				  AND installment_no = 1
				""",
				asOfDate.minusDays(1),
				disbursement.contractId());

		LoanApplicationService.LoanOverdueTransitionResponse response = loanApplicationService.markOverdueInstallments(
				new LoanApplicationService.LoanOverdueTransitionRequest(
						asOfDate,
						"loan-collector",
						UUID.randomUUID(),
						UUID.randomUUID(),
						UUID.randomUUID(),
						"trace-loan-overdue-1"));

		assertEquals(asOfDate, response.asOfDate());
		assertEquals(1, response.affectedContractCount());
		assertEquals(1, response.affectedInstallmentCount());
		assertEquals(1, response.affectedContractIds().size());
		assertEquals(disbursement.contractId(), response.affectedContractIds().get(0));

		Map<String, Object> installment = jdbcTemplate.queryForMap(
				"""
				SELECT status
				FROM repayment_schedules
				WHERE contract_id = ? AND installment_no = 1
				""",
				disbursement.contractId());
		assertEquals("OVERDUE", installment.get("status"));

		Map<String, Object> contract = jdbcTemplate.queryForMap(
				"SELECT status, outstanding_principal_minor FROM loan_contracts WHERE contract_id = ?",
				disbursement.contractId());
		assertEquals("ACTIVE", contract.get("status"));
		assertTrue(((Number) contract.get("outstanding_principal_minor")).longValue() > 0);

		assertEquals(1, count("SELECT COUNT(*) FROM loan_events WHERE contract_id = ? AND event_type = 'OVERDUE'", disbursement.contractId()));
		assertEquals(1, count("SELECT COUNT(*) FROM audit_events WHERE action = 'LOAN_OVERDUE_MARKED' AND resource_id = ?", disbursement.contractId().toString()));
		assertEquals(1, count("SELECT COUNT(*) FROM outbox_events WHERE event_type = 'LOAN_OVERDUE' AND aggregate_id = ?", disbursement.contractId().toString()));
	}

	@Test
	void markOverdueInstallments_skipsAlreadyPaidInstallmentWithoutSideEffects() {
		SeededData seeded = seedLoanDisbursementData("VND", 1_000_000L, 1_000_000L);
		LoanApplicationService.LoanDisbursementResponse disbursement = disburseLoanForRepayment(seeded, "overdue-paid");
		LocalDate asOfDate = LocalDate.now();

		Map<String, Object> due = jdbcTemplate.queryForMap(
				"""
				SELECT principal_due_minor, interest_due_minor, fees_due_minor
				FROM repayment_schedules
				WHERE contract_id = ? AND installment_no = 1
				""",
				disbursement.contractId());

		jdbcTemplate.update(
				"""
				UPDATE repayment_schedules
				SET due_date = ?,
				    principal_paid_minor = ?,
				    interest_paid_minor = ?,
				    fees_paid_minor = ?,
				    status = 'PAID'
				WHERE contract_id = ?
				  AND installment_no = 1
				""",
				asOfDate.minusDays(1),
				((Number) due.get("principal_due_minor")).longValue(),
				((Number) due.get("interest_due_minor")).longValue(),
				((Number) due.get("fees_due_minor")).longValue(),
				disbursement.contractId());

		LoanApplicationService.LoanOverdueTransitionResponse response = loanApplicationService.markOverdueInstallments(
				new LoanApplicationService.LoanOverdueTransitionRequest(
						asOfDate,
						"loan-collector",
						UUID.randomUUID(),
						UUID.randomUUID(),
						UUID.randomUUID(),
						"trace-loan-overdue-2"));

		assertEquals(0, response.affectedContractCount());
		assertEquals(0, response.affectedInstallmentCount());
		assertTrue(response.affectedContractIds().isEmpty());
		assertEquals(0, count("SELECT COUNT(*) FROM loan_events WHERE contract_id = ? AND event_type = 'OVERDUE'", disbursement.contractId()));
		assertEquals(0, count("SELECT COUNT(*) FROM audit_events WHERE action = 'LOAN_OVERDUE_MARKED' AND resource_id = ?", disbursement.contractId().toString()));
		assertEquals(0, count("SELECT COUNT(*) FROM outbox_events WHERE event_type = 'LOAN_OVERDUE' AND aggregate_id = ?", disbursement.contractId().toString()));
	}

	@Test
	void markOverdueInstallments_rerunIsIdempotentWithoutDoubleWrites() {
		SeededData seeded = seedLoanDisbursementData("VND", 1_000_000L, 1_000_000L);
		LoanApplicationService.LoanDisbursementResponse disbursement = disburseLoanForRepayment(seeded, "overdue-rerun");
		LocalDate asOfDate = LocalDate.now();

		jdbcTemplate.update(
				"""
				UPDATE repayment_schedules
				SET due_date = ?
				WHERE contract_id = ?
				  AND installment_no = 1
				""",
				asOfDate.minusDays(1),
				disbursement.contractId());

		LoanApplicationService.LoanOverdueTransitionRequest request = new LoanApplicationService.LoanOverdueTransitionRequest(
				asOfDate,
				"loan-collector",
				UUID.randomUUID(),
				UUID.randomUUID(),
				UUID.randomUUID(),
				"trace-loan-overdue-3");

		LoanApplicationService.LoanOverdueTransitionResponse first = loanApplicationService.markOverdueInstallments(request);
		assertEquals(1, first.affectedContractCount());
		assertEquals(1, first.affectedInstallmentCount());

		int loanEventsAfterFirst = count("SELECT COUNT(*) FROM loan_events WHERE contract_id = ? AND event_type = 'OVERDUE'", disbursement.contractId());
		int auditEventsAfterFirst = count("SELECT COUNT(*) FROM audit_events WHERE action = 'LOAN_OVERDUE_MARKED' AND resource_id = ?", disbursement.contractId().toString());
		int outboxAfterFirst = count("SELECT COUNT(*) FROM outbox_events WHERE event_type = 'LOAN_OVERDUE' AND aggregate_id = ?", disbursement.contractId().toString());

		LoanApplicationService.LoanOverdueTransitionResponse second = loanApplicationService.markOverdueInstallments(request);
		assertEquals(0, second.affectedContractCount());
		assertEquals(0, second.affectedInstallmentCount());
		assertTrue(second.affectedContractIds().isEmpty());
		assertEquals(loanEventsAfterFirst, count("SELECT COUNT(*) FROM loan_events WHERE contract_id = ? AND event_type = 'OVERDUE'", disbursement.contractId()));
		assertEquals(auditEventsAfterFirst, count("SELECT COUNT(*) FROM audit_events WHERE action = 'LOAN_OVERDUE_MARKED' AND resource_id = ?", disbursement.contractId().toString()));
		assertEquals(outboxAfterFirst, count("SELECT COUNT(*) FROM outbox_events WHERE event_type = 'LOAN_OVERDUE' AND aggregate_id = ?", disbursement.contractId().toString()));
	}

	@Test
	void repayLoan_succeedsWhenScheduleWasOverdueAndJournalRemainsBalanced() {
		SeededData seeded = seedLoanDisbursementData("VND", 1_000_000L, 1_000_000L);
		LoanApplicationService.LoanDisbursementResponse disbursement = disburseLoanForRepayment(seeded, "overdue-repay");
		LocalDate asOfDate = LocalDate.now();

		jdbcTemplate.update(
				"""
				UPDATE repayment_schedules
				SET due_date = ?,
				    fees_due_minor = ?
				WHERE contract_id = ?
				  AND installment_no = 1
				""",
				asOfDate.minusDays(1),
				4_000L,
				disbursement.contractId());

		LoanApplicationService.LoanOverdueTransitionResponse overdue = loanApplicationService.markOverdueInstallments(
				new LoanApplicationService.LoanOverdueTransitionRequest(
						asOfDate,
						"loan-collector",
						UUID.randomUUID(),
						UUID.randomUUID(),
						UUID.randomUUID(),
						"trace-loan-overdue-4"));
		assertEquals(1, overdue.affectedContractCount());

		LoanApplicationService.LoanRepaymentResponse repayment = loanApplicationService.repayLoan(
				new LoanApplicationService.LoanRepaymentRequest(
						"idem-loan-repay-overdue-1",
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
						"trace-loan-repay-overdue-1"));

		assertEquals(2_500L, repayment.principalPaidMinor());
		assertEquals(3_000L, repayment.interestPaidMinor());
		assertEquals(4_000L, repayment.feesPaidMinor());

		Map<String, Object> installment = jdbcTemplate.queryForMap(
				"""
				SELECT status, principal_paid_minor, interest_paid_minor, fees_paid_minor
				FROM repayment_schedules
				WHERE contract_id = ? AND installment_no = 1
				""",
				disbursement.contractId());
		assertEquals("PARTIALLY_PAID", installment.get("status"));
		assertEquals(2_500L, ((Number) installment.get("principal_paid_minor")).longValue());
		assertEquals(3_000L, ((Number) installment.get("interest_paid_minor")).longValue());
		assertEquals(4_000L, ((Number) installment.get("fees_paid_minor")).longValue());

		Map<String, Object> sums = jdbcTemplate.queryForMap(
				"""
				SELECT COALESCE(SUM(CASE WHEN entry_side='D' THEN amount_minor ELSE 0 END), 0) AS total_debit,
				       COALESCE(SUM(CASE WHEN entry_side='C' THEN amount_minor ELSE 0 END), 0) AS total_credit
				FROM ledger_postings
				WHERE journal_id = ?
				""",
				repayment.journalId());
		assertEquals(9_500L, ((Number) sums.get("total_debit")).longValue());
		assertEquals(9_500L, ((Number) sums.get("total_credit")).longValue());
	}

	@Test
	void markContractDefaulted_marksEligibleContractAndAppendsSideEffects() {
		SeededData seeded = seedLoanDisbursementData("VND", 1_000_000L, 1_000_000L);
		LoanApplicationService.LoanDisbursementResponse disbursement = disburseLoanForRepayment(seeded, "default-happy");
		LocalDate asOfDate = LocalDate.now();

		jdbcTemplate.update(
				"""
				UPDATE repayment_schedules
				SET due_date = ?
				WHERE contract_id = ?
				  AND installment_no = 1
				""",
				asOfDate.minusDays(2),
				disbursement.contractId());

		loanApplicationService.markOverdueInstallments(
				new LoanApplicationService.LoanOverdueTransitionRequest(
						asOfDate,
						"loan-collector",
						UUID.randomUUID(),
						UUID.randomUUID(),
						UUID.randomUUID(),
						"trace-loan-overdue-default-happy"));

		LoanApplicationService.LoanDefaultTransitionResponse response = loanApplicationService.markContractDefaulted(
				new LoanApplicationService.LoanDefaultTransitionRequest(
						disbursement.contractId(),
						asOfDate,
						"loan-manager",
						UUID.randomUUID(),
						UUID.randomUUID(),
						UUID.randomUUID(),
						"trace-loan-default-1"));

		assertEquals(disbursement.contractId(), response.contractId());
		assertTrue(response.transitioned());
		assertEquals("DEFAULTED", response.status());
		assertEquals(1, response.overdueInstallmentCount());

		Map<String, Object> contract = jdbcTemplate.queryForMap(
				"SELECT status, outstanding_principal_minor FROM loan_contracts WHERE contract_id = ?",
				disbursement.contractId());
		assertEquals("DEFAULTED", contract.get("status"));
		assertTrue(((Number) contract.get("outstanding_principal_minor")).longValue() > 0);

		assertEquals(1, count("SELECT COUNT(*) FROM loan_events WHERE contract_id = ? AND event_type = 'DEFAULTED'", disbursement.contractId()));
		assertEquals(1, count("SELECT COUNT(*) FROM audit_events WHERE action = 'LOAN_DEFAULTED' AND resource_id = ?", disbursement.contractId().toString()));
		assertEquals(1, count("SELECT COUNT(*) FROM outbox_events WHERE event_type = 'LOAN_DEFAULTED' AND aggregate_id = ?", disbursement.contractId().toString()));
	}

	@Test
	void markContractDefaulted_replayDoesNotAppendDuplicateSideEffects() {
		SeededData seeded = seedLoanDisbursementData("VND", 1_000_000L, 1_000_000L);
		LoanApplicationService.LoanDisbursementResponse disbursement = disburseLoanForRepayment(seeded, "default-replay");
		LocalDate asOfDate = LocalDate.now();

		jdbcTemplate.update(
				"""
				UPDATE repayment_schedules
				SET due_date = ?
				WHERE contract_id = ?
				  AND installment_no = 1
				""",
				asOfDate.minusDays(2),
				disbursement.contractId());

		loanApplicationService.markOverdueInstallments(
				new LoanApplicationService.LoanOverdueTransitionRequest(
						asOfDate,
						"loan-collector",
						UUID.randomUUID(),
						UUID.randomUUID(),
						UUID.randomUUID(),
						"trace-loan-overdue-default-replay"));

		LoanApplicationService.LoanDefaultTransitionRequest request = new LoanApplicationService.LoanDefaultTransitionRequest(
				disbursement.contractId(),
				asOfDate,
				"loan-manager",
				UUID.randomUUID(),
				UUID.randomUUID(),
				UUID.randomUUID(),
				"trace-loan-default-2");

		LoanApplicationService.LoanDefaultTransitionResponse first = loanApplicationService.markContractDefaulted(request);
		LoanApplicationService.LoanDefaultTransitionResponse second = loanApplicationService.markContractDefaulted(request);

		assertTrue(first.transitioned());
		assertTrue(!second.transitioned());
		assertEquals("DEFAULTED", second.status());
		assertEquals(1, count("SELECT COUNT(*) FROM loan_events WHERE contract_id = ? AND event_type = 'DEFAULTED'", disbursement.contractId()));
		assertEquals(1, count("SELECT COUNT(*) FROM audit_events WHERE action = 'LOAN_DEFAULTED' AND resource_id = ?", disbursement.contractId().toString()));
		assertEquals(1, count("SELECT COUNT(*) FROM outbox_events WHERE event_type = 'LOAN_DEFAULTED' AND aggregate_id = ?", disbursement.contractId().toString()));
	}

	@Test
	void markContractDefaulted_rejectsContractWithoutOverdueInstallment() {
		SeededData seeded = seedLoanDisbursementData("VND", 1_000_000L, 1_000_000L);
		LoanApplicationService.LoanDisbursementResponse disbursement = disburseLoanForRepayment(seeded, "default-no-overdue");
		LocalDate asOfDate = LocalDate.now();

		assertThrows(
				CoreBankException.class,
				() -> loanApplicationService.markContractDefaulted(
						new LoanApplicationService.LoanDefaultTransitionRequest(
								disbursement.contractId(),
								asOfDate,
								"loan-manager",
								UUID.randomUUID(),
								UUID.randomUUID(),
								UUID.randomUUID(),
								"trace-loan-default-3")));

		Map<String, Object> contract = jdbcTemplate.queryForMap(
				"SELECT status FROM loan_contracts WHERE contract_id = ?",
				disbursement.contractId());
		assertEquals("ACTIVE", contract.get("status"));
		assertEquals(0, count("SELECT COUNT(*) FROM loan_events WHERE contract_id = ? AND event_type = 'DEFAULTED'", disbursement.contractId()));
		assertEquals(0, count("SELECT COUNT(*) FROM audit_events WHERE action = 'LOAN_DEFAULTED' AND resource_id = ?", disbursement.contractId().toString()));
		assertEquals(0, count("SELECT COUNT(*) FROM outbox_events WHERE event_type = 'LOAN_DEFAULTED' AND aggregate_id = ?", disbursement.contractId().toString()));
	}

	private LoanApplicationService.LoanDisbursementResponse disburseLoanForRepayment(SeededData seeded, String suffix) {
		return loanApplicationService.disburseLoan(new LoanApplicationService.LoanDisbursementRequest(
				"idem-loan-disburse-" + suffix,
				seeded.borrowerAccountId(),
				seeded.productId(),
				UUID.randomUUID(),
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
				"trace-loan-disburse-" + suffix));
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
