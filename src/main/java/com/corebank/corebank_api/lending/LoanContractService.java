package com.corebank.corebank_api.lending;

import com.corebank.corebank_api.account.AccountBalanceRepository;
import com.corebank.corebank_api.account.CustomerAccount;
import com.corebank.corebank_api.common.CoreBankException;
import com.corebank.corebank_api.ledger.LedgerCommandService;
import com.corebank.corebank_api.ledger.LedgerCommandService.PostingInstruction;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class LoanContractService {

	private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

	private final JdbcTemplate jdbcTemplate;
	private final AccountBalanceRepository accountBalanceRepository;
	private final LedgerCommandService ledgerCommandService;

	private static final org.springframework.jdbc.core.RowMapper<LoanContractSnapshot> LOAN_CONTRACT_ROW_MAPPER =
			new org.springframework.jdbc.core.RowMapper<>() {
				@Override
				public LoanContractSnapshot mapRow(ResultSet rs, int rowNum) throws SQLException {
					return new LoanContractSnapshot(
							rs.getObject("contract_id", UUID.class),
							rs.getObject("borrower_account_id", UUID.class),
							rs.getLong("outstanding_principal_minor"),
							rs.getString("currency"),
							rs.getString("status"));
				}
			};

	private static final org.springframework.jdbc.core.RowMapper<RepaymentScheduleSnapshot> REPAYMENT_SCHEDULE_ROW_MAPPER =
			new org.springframework.jdbc.core.RowMapper<>() {
				@Override
				public RepaymentScheduleSnapshot mapRow(ResultSet rs, int rowNum) throws SQLException {
					return new RepaymentScheduleSnapshot(
							rs.getLong("schedule_id"),
							rs.getInt("installment_no"),
							rs.getLong("principal_due_minor"),
							rs.getLong("interest_due_minor"),
							rs.getLong("fees_due_minor"),
							rs.getLong("principal_paid_minor"),
							rs.getLong("interest_paid_minor"),
							rs.getLong("fees_paid_minor"),
							rs.getString("status"));
				}
			};

	public LoanContractService(
			JdbcTemplate jdbcTemplate,
			AccountBalanceRepository accountBalanceRepository,
			LedgerCommandService ledgerCommandService) {
		this.jdbcTemplate = jdbcTemplate;
		this.accountBalanceRepository = accountBalanceRepository;
		this.ledgerCommandService = ledgerCommandService;
	}

	@Transactional
	public DisbursementResponse disburseLoan(DisbursementRequest request) {
		validate(request);

		CustomerAccount borrower = accountBalanceRepository.lockById(request.borrowerAccountId())
				.orElseThrow(() -> new CoreBankException("Borrower account not found: " + request.borrowerAccountId()));

		if (!"ACTIVE".equals(borrower.getStatus())) {
			throw new CoreBankException("Borrower account is not active");
		}

		if (!borrower.getCurrency().equals(request.currency())) {
			throw new CoreBankException("Borrower account currency does not match loan currency");
		}

		Instant disbursedAt = Instant.now();
		LocalDate disbursedDate = disbursedAt.atZone(ZoneOffset.UTC).toLocalDate();
		UUID contractId = UUID.randomUUID();

		long borrowerAvailableAfter = borrower.getAvailableBalanceMinor() + request.principalAmountMinor();
		boolean availableUpdated = accountBalanceRepository.updateAvailableBalance(
				borrower.getCustomerAccountId(),
				borrowerAvailableAfter,
				borrower.getVersion());

		if (!availableUpdated) {
			throw new CoreBankException("Failed to update borrower available balance during disbursement");
		}

		insertLoanContract(contractId, request, disbursedAt);
		List<RepaymentInstallment> schedule = buildSchedule(request, disbursedDate);
		insertRepaymentSchedule(contractId, schedule);

		UUID journalId = ledgerCommandService.postJournal(
				new LedgerCommandService.PostJournalCommand(
						"LOAN_DISBURSEMENT",
						"LOAN_CONTRACT",
						contractId,
						request.currency(),
						null,
						request.actor(),
						request.correlationId(),
						List.of(
								new PostingInstruction(
										request.debitLedgerAccountId(),
										null,
										"D",
										request.principalAmountMinor(),
										request.currency(),
										false),
								new PostingInstruction(
										request.creditLedgerAccountId(),
										request.borrowerAccountId(),
										"C",
										request.principalAmountMinor(),
										request.currency(),
										true))));

		insertLoanEvent(contractId, request.principalAmountMinor(), buildMetadata(request, schedule, journalId));

		return new DisbursementResponse(
				contractId,
				journalId,
				request.borrowerAccountId(),
				request.principalAmountMinor(),
				request.currency(),
				disbursedDate,
				schedule.get(0).dueDate(),
				request.termMonths(),
				"ACTIVE");
	}

	@Transactional
	public RepaymentResponse repayLoan(RepaymentRequest request) {
		validateRepayment(request);

		LoanContractSnapshot contract = lockLoanContract(request.contractId())
				.orElseThrow(() -> new CoreBankException("Loan contract not found: " + request.contractId()));

		if (!"ACTIVE".equals(contract.status())) {
			throw new CoreBankException("Loan contract is not active");
		}

		if (!contract.currency().equals(request.currency())) {
			throw new CoreBankException("Loan contract currency does not match repayment currency");
		}

		if (!contract.borrowerAccountId().equals(request.payerAccountId())) {
			throw new CoreBankException("Repayment payer account does not match loan borrower account");
		}

		CustomerAccount payer = accountBalanceRepository.lockById(request.payerAccountId())
				.orElseThrow(() -> new CoreBankException("Repayment payer account not found: " + request.payerAccountId()));

		if (!"ACTIVE".equals(payer.getStatus())) {
			throw new CoreBankException("Repayment payer account is not active");
		}

		if (!payer.getCurrency().equals(request.currency())) {
			throw new CoreBankException("Repayment payer account currency does not match repayment currency");
		}

		if (payer.getAvailableBalanceMinor() < request.amountMinor()) {
			throw new CoreBankException("Insufficient available balance for loan repayment");
		}

		List<RepaymentScheduleSnapshot> schedules = lockOpenSchedules(request.contractId());
		if (schedules.isEmpty()) {
			throw new CoreBankException("No open repayment schedule found for contract: " + request.contractId());
		}

		AllocationResult allocation = allocateRepayment(schedules, request.amountMinor());
		if (allocation.unallocatedAmountMinor() > 0) {
			throw new CoreBankException("Repayment amount exceeds total outstanding due amount");
		}

		long payerAvailableAfter = payer.getAvailableBalanceMinor() - request.amountMinor();
		boolean availableUpdated = accountBalanceRepository.updateAvailableBalance(
				payer.getCustomerAccountId(),
				payerAvailableAfter,
				payer.getVersion());

		if (!availableUpdated) {
			throw new CoreBankException("Failed to update payer available balance during repayment");
		}

		UUID journalId = ledgerCommandService.postJournal(
				new LedgerCommandService.PostJournalCommand(
						"LOAN_REPAYMENT",
						"LOAN_CONTRACT",
						request.contractId(),
						request.currency(),
						null,
						request.actor(),
						request.correlationId(),
						List.of(
								new PostingInstruction(
										request.debitLedgerAccountId(),
										request.payerAccountId(),
										"D",
										request.amountMinor(),
										request.currency(),
										true),
								new PostingInstruction(
										request.creditLedgerAccountId(),
										null,
										"C",
										request.amountMinor(),
										request.currency(),
										false))));

		updateSchedules(allocation.updates());

		long outstandingAfter = contract.outstandingPrincipalMinor() - allocation.principalAllocatedMinor();
		if (outstandingAfter < 0) {
			throw new CoreBankException("Repayment principal allocation exceeds outstanding principal");
		}

		boolean allSchedulesPaid = allocation.remainingDueAmountMinor() == 0;
		String contractStatusAfter = (outstandingAfter == 0 && allSchedulesPaid) ? "CLOSED" : "ACTIVE";

		jdbcTemplate.update(
				"""
				UPDATE loan_contracts
				SET outstanding_principal_minor = ?,
				    status = ?,
				    updated_at = now()
				WHERE contract_id = ?
				""",
				outstandingAfter,
				contractStatusAfter,
				request.contractId());

		insertLoanRepaymentEvent(request, allocation, journalId);

		return new RepaymentResponse(
				request.contractId(),
				journalId,
				request.payerAccountId(),
				request.amountMinor(),
				allocation.principalAllocatedMinor(),
				allocation.interestAllocatedMinor(),
				allocation.feesAllocatedMinor(),
				outstandingAfter,
				contractStatusAfter,
				allocation.updatedInstallmentCount());
	}

	@Transactional
	public List<OverdueTransitionResult> markOverdueInstallments(LocalDate asOfDate) {
		if (asOfDate == null) {
			throw new CoreBankException("Overdue transition date is required");
		}

		List<OverdueTransitionResult> transitionedContracts = jdbcTemplate.query(
				"""
				WITH updated AS (
				    UPDATE repayment_schedules rs
				    SET status = 'OVERDUE',
				        updated_at = now()
				    FROM loan_contracts lc
				    WHERE rs.contract_id = lc.contract_id
				      AND lc.status = 'ACTIVE'
				      AND rs.status IN ('PENDING', 'PARTIALLY_PAID')
				      AND rs.due_date < ?
				      AND (
				          (rs.fees_due_minor - rs.fees_paid_minor)
				        + (rs.interest_due_minor - rs.interest_paid_minor)
				        + (rs.principal_due_minor - rs.principal_paid_minor)
				      ) > 0
				    RETURNING rs.contract_id,
				              (
				                  (rs.fees_due_minor - rs.fees_paid_minor)
				                + (rs.interest_due_minor - rs.interest_paid_minor)
				                + (rs.principal_due_minor - rs.principal_paid_minor)
				              ) AS overdue_amount_minor
				)
				SELECT u.contract_id,
				       COUNT(*) AS overdue_installment_count,
				       COALESCE(SUM(u.overdue_amount_minor), 0) AS overdue_amount_minor,
				       lc.outstanding_principal_minor,
				       lc.status
				FROM updated u
				JOIN loan_contracts lc ON lc.contract_id = u.contract_id
				GROUP BY u.contract_id, lc.outstanding_principal_minor, lc.status
				""",
				(rs, rowNum) -> new OverdueTransitionResult(
						rs.getObject("contract_id", UUID.class),
						rs.getInt("overdue_installment_count"),
						rs.getLong("overdue_amount_minor"),
						rs.getLong("outstanding_principal_minor"),
						rs.getString("status")),
				asOfDate);

		for (OverdueTransitionResult result : transitionedContracts) {
			insertLoanOverdueEvent(result.contractId(), result.overdueAmountMinor(), result.overdueInstallmentCount(), asOfDate);
		}

		return transitionedContracts;
	}

	@Transactional
	public DefaultTransitionResult markContractDefaulted(UUID contractId, LocalDate asOfDate) {
		if (contractId == null) {
			throw new CoreBankException("Loan contract id is required");
		}
		if (asOfDate == null) {
			throw new CoreBankException("Default transition date is required");
		}

		LoanContractSnapshot contract = lockLoanContract(contractId)
				.orElseThrow(() -> new CoreBankException("Loan contract not found: " + contractId));

		if ("DEFAULTED".equals(contract.status())) {
			return new DefaultTransitionResult(
					contract.contractId(),
					contract.outstandingPrincipalMinor(),
					countOverdueInstallments(contract.contractId(), asOfDate),
					false,
					contract.status());
		}

		if (!"ACTIVE".equals(contract.status())) {
			throw new CoreBankException("Only ACTIVE loan contracts can transition to DEFAULTED");
		}

		int overdueInstallmentCount = countOverdueInstallments(contract.contractId(), asOfDate);
		if (overdueInstallmentCount == 0) {
			throw new CoreBankException("Loan contract has no overdue installments eligible for default");
		}

		jdbcTemplate.update(
				"""
				UPDATE loan_contracts
				SET status = 'DEFAULTED',
				    updated_at = now()
				WHERE contract_id = ?
				""",
				contract.contractId());

		insertLoanDefaultedEvent(contract.contractId(), contract.outstandingPrincipalMinor(), overdueInstallmentCount, asOfDate);

		return new DefaultTransitionResult(
				contract.contractId(),
				contract.outstandingPrincipalMinor(),
				overdueInstallmentCount,
				true,
				"DEFAULTED");
	}

	private void validate(DisbursementRequest request) {
		if (request.principalAmountMinor() <= 0) {
			throw new CoreBankException("Loan principal must be positive");
		}
		if (request.termMonths() <= 0) {
			throw new CoreBankException("Loan term must be positive");
		}
		if (request.annualInterestRate() < 0 || request.annualInterestRate() > 100) {
			throw new CoreBankException("Loan annual interest rate must be between 0 and 100");
		}
	}

	private void validateRepayment(RepaymentRequest request) {
		if (request.amountMinor() <= 0) {
			throw new CoreBankException("Repayment amount must be positive");
		}
	}

	private java.util.Optional<LoanContractSnapshot> lockLoanContract(UUID contractId) {
		return jdbcTemplate.query(
				"""
				SELECT contract_id,
				       borrower_account_id,
				       outstanding_principal_minor,
				       currency,
				       status
				FROM loan_contracts
				WHERE contract_id = ?
				FOR UPDATE
				""",
				LOAN_CONTRACT_ROW_MAPPER,
				contractId)
			.stream()
			.findFirst();
	}

	private List<RepaymentScheduleSnapshot> lockOpenSchedules(UUID contractId) {
		return jdbcTemplate.query(
				"""
				SELECT schedule_id,
				       installment_no,
				       principal_due_minor,
				       interest_due_minor,
				       fees_due_minor,
				       principal_paid_minor,
				       interest_paid_minor,
				       fees_paid_minor,
				       status
				FROM repayment_schedules
				WHERE contract_id = ?
				  AND status IN ('PENDING', 'PARTIALLY_PAID', 'OVERDUE')
				ORDER BY installment_no ASC
				FOR UPDATE
				""",
				REPAYMENT_SCHEDULE_ROW_MAPPER,
				contractId);
	}

	private int countOverdueInstallments(UUID contractId, LocalDate asOfDate) {
		Integer overdueCount = jdbcTemplate.queryForObject(
				"""
				SELECT COUNT(*)
				FROM repayment_schedules
				WHERE contract_id = ?
				  AND status = 'OVERDUE'
				  AND due_date < ?
				  AND (
				      (fees_due_minor - fees_paid_minor)
				    + (interest_due_minor - interest_paid_minor)
				    + (principal_due_minor - principal_paid_minor)
				  ) > 0
				""",
				Integer.class,
				contractId,
				asOfDate);
		return overdueCount == null ? 0 : overdueCount;
	}

	private AllocationResult allocateRepayment(List<RepaymentScheduleSnapshot> schedules, long amountMinor) {
		long remaining = amountMinor;
		long principalAllocated = 0;
		long interestAllocated = 0;
		long feesAllocated = 0;
		long remainingDueAfter = 0;
		int updatedInstallmentCount = 0;

		List<RepaymentScheduleUpdate> updates = new ArrayList<>();

		for (RepaymentScheduleSnapshot schedule : schedules) {
			long feesRemaining = schedule.feesDueMinor() - schedule.feesPaidMinor();
			long interestRemaining = schedule.interestDueMinor() - schedule.interestPaidMinor();
			long principalRemaining = schedule.principalDueMinor() - schedule.principalPaidMinor();

			long feesPaid = Math.min(remaining, feesRemaining);
			remaining -= feesPaid;
			feesAllocated += feesPaid;

			long interestPaid = Math.min(remaining, interestRemaining);
			remaining -= interestPaid;
			interestAllocated += interestPaid;

			long principalPaid = Math.min(remaining, principalRemaining);
			remaining -= principalPaid;
			principalAllocated += principalPaid;

			long newFeesPaid = schedule.feesPaidMinor() + feesPaid;
			long newInterestPaid = schedule.interestPaidMinor() + interestPaid;
			long newPrincipalPaid = schedule.principalPaidMinor() + principalPaid;

			long scheduleRemainingAfter = (schedule.feesDueMinor() - newFeesPaid)
					+ (schedule.interestDueMinor() - newInterestPaid)
					+ (schedule.principalDueMinor() - newPrincipalPaid);

			if (feesPaid > 0 || interestPaid > 0 || principalPaid > 0) {
				updatedInstallmentCount++;
			}

			String statusAfter;
			if (scheduleRemainingAfter == 0) {
				statusAfter = "PAID";
			} else if (newFeesPaid > 0 || newInterestPaid > 0 || newPrincipalPaid > 0) {
				statusAfter = "PARTIALLY_PAID";
			} else {
				statusAfter = schedule.status();
			}

			updates.add(new RepaymentScheduleUpdate(
					schedule.scheduleId(),
					newPrincipalPaid,
					newInterestPaid,
					newFeesPaid,
					statusAfter));

			remainingDueAfter += scheduleRemainingAfter;
		}

		return new AllocationResult(
				updates,
				principalAllocated,
				interestAllocated,
				feesAllocated,
				remaining,
				remainingDueAfter,
				updatedInstallmentCount);
	}

	private void updateSchedules(List<RepaymentScheduleUpdate> updates) {
		for (RepaymentScheduleUpdate update : updates) {
			jdbcTemplate.update(
					"""
					UPDATE repayment_schedules
					SET principal_paid_minor = ?,
					    interest_paid_minor = ?,
					    fees_paid_minor = ?,
					    status = ?,
					    updated_at = now()
					WHERE schedule_id = ?
					""",
					update.principalPaidMinor(),
					update.interestPaidMinor(),
					update.feesPaidMinor(),
					update.status(),
					update.scheduleId());
		}
	}

	private void insertLoanRepaymentEvent(RepaymentRequest request, AllocationResult allocation, UUID journalId) {
		Map<String, Object> metadata = Map.of(
				"journalId", journalId,
				"principalPaidMinor", allocation.principalAllocatedMinor(),
				"interestPaidMinor", allocation.interestAllocatedMinor(),
				"feesPaidMinor", allocation.feesAllocatedMinor(),
				"updatedInstallmentCount", allocation.updatedInstallmentCount());

		try {
			jdbcTemplate.update(
					"""
					INSERT INTO loan_events (
					    contract_id,
					    event_type,
					    amount_minor,
					    metadata_json,
					    created_at
					) VALUES (?, 'REPAYMENT', ?, ?::jsonb, now())
					""",
					request.contractId(),
					request.amountMinor(),
					OBJECT_MAPPER.writeValueAsString(metadata));
		} catch (JsonProcessingException e) {
			throw new CoreBankException("Unable to serialize repayment event metadata", e);
		}
	}

	private void insertLoanOverdueEvent(UUID contractId, long overdueAmountMinor, int overdueInstallmentCount, LocalDate asOfDate) {
		Map<String, Object> metadata = Map.of(
				"asOfDate", asOfDate.toString(),
				"overdueInstallmentCount", overdueInstallmentCount,
				"overdueAmountMinor", overdueAmountMinor);

		try {
			jdbcTemplate.update(
					"""
					INSERT INTO loan_events (
					    contract_id,
					    event_type,
					    amount_minor,
					    metadata_json,
					    created_at
					) VALUES (?, 'OVERDUE', ?, ?::jsonb, now())
					""",
					contractId,
					overdueAmountMinor,
					OBJECT_MAPPER.writeValueAsString(metadata));
		} catch (JsonProcessingException e) {
			throw new CoreBankException("Unable to serialize overdue event metadata", e);
		}
	}

	private void insertLoanDefaultedEvent(UUID contractId, long outstandingPrincipalMinor, int overdueInstallmentCount, LocalDate asOfDate) {
		Map<String, Object> metadata = Map.of(
				"asOfDate", asOfDate.toString(),
				"overdueInstallmentCount", overdueInstallmentCount,
				"outstandingPrincipalMinor", outstandingPrincipalMinor);

		try {
			jdbcTemplate.update(
					"""
					INSERT INTO loan_events (
					    contract_id,
					    event_type,
					    amount_minor,
					    metadata_json,
					    created_at
					) VALUES (?, 'DEFAULTED', ?, ?::jsonb, now())
					""",
					contractId,
					outstandingPrincipalMinor,
					OBJECT_MAPPER.writeValueAsString(metadata));
		} catch (JsonProcessingException e) {
			throw new CoreBankException("Unable to serialize defaulted event metadata", e);
		}
	}

	private void insertLoanContract(UUID contractId, DisbursementRequest request, Instant disbursedAt) {
		jdbcTemplate.update(
				"""
				INSERT INTO loan_contracts (
				    contract_id,
				    borrower_account_id,
				    product_id,
				    product_version_id,
				    principal_amount_minor,
				    outstanding_principal_minor,
				    currency,
				    annual_interest_rate,
				    term_months,
				    disbursed_at,
				    status,
				    created_at,
				    updated_at
				) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'ACTIVE', now(), now())
				""",
				contractId,
				request.borrowerAccountId(),
				request.productId(),
				request.productVersionId(),
				request.principalAmountMinor(),
				request.principalAmountMinor(),
				request.currency(),
				request.annualInterestRate(),
				request.termMonths(),
				Timestamp.from(disbursedAt));
	}

	private List<RepaymentInstallment> buildSchedule(DisbursementRequest request, LocalDate disbursedDate) {
		List<RepaymentInstallment> installments = new ArrayList<>();
		long basePrincipal = request.principalAmountMinor() / request.termMonths();
		long remainderPrincipal = request.principalAmountMinor() % request.termMonths();

		long outstanding = request.principalAmountMinor();
		BigDecimal annualRate = BigDecimal.valueOf(request.annualInterestRate());
		BigDecimal monthlyRate = annualRate
				.divide(BigDecimal.valueOf(100), 10, RoundingMode.HALF_UP)
				.divide(BigDecimal.valueOf(12), 10, RoundingMode.HALF_UP);

		for (int i = 1; i <= request.termMonths(); i++) {
			long principalDue = (i == request.termMonths()) ? basePrincipal + remainderPrincipal : basePrincipal;
			long interestDue = BigDecimal.valueOf(outstanding)
					.multiply(monthlyRate)
					.setScale(0, RoundingMode.HALF_UP)
					.longValue();

			installments.add(new RepaymentInstallment(i, disbursedDate.plusMonths(i), principalDue, interestDue));
			outstanding -= principalDue;
		}

		return installments;
	}

	private void insertRepaymentSchedule(UUID contractId, List<RepaymentInstallment> schedule) {
		for (RepaymentInstallment installment : schedule) {
			jdbcTemplate.update(
					"""
					INSERT INTO repayment_schedules (
					    contract_id,
					    installment_no,
					    due_date,
					    principal_due_minor,
					    interest_due_minor,
					    fees_due_minor,
					    principal_paid_minor,
					    interest_paid_minor,
					    fees_paid_minor,
					    status,
					    created_at,
					    updated_at
					) VALUES (?, ?, ?, ?, ?, 0, 0, 0, 0, 'PENDING', now(), now())
					""",
					contractId,
					installment.installmentNo(),
					installment.dueDate(),
					installment.principalDueMinor(),
					installment.interestDueMinor());
		}
	}

	private void insertLoanEvent(UUID contractId, long amountMinor, String metadataJson) {
		jdbcTemplate.update(
				"""
				INSERT INTO loan_events (
				    contract_id,
				    event_type,
				    amount_minor,
				    metadata_json,
				    created_at
				) VALUES (?, 'DISBURSED', ?, ?::jsonb, now())
				""",
				contractId,
				amountMinor,
				metadataJson);
	}

	private String buildMetadata(DisbursementRequest request, List<RepaymentInstallment> schedule, UUID journalId) {
		try {
			return OBJECT_MAPPER.writeValueAsString(Map.of(
					"productVersionId", request.productVersionId(),
					"termMonths", request.termMonths(),
					"annualInterestRate", request.annualInterestRate(),
					"journalId", journalId,
					"installmentCount", schedule.size()));
		} catch (JsonProcessingException e) {
			return "{}";
		}
	}

	public record DisbursementRequest(
			UUID borrowerAccountId,
			UUID productId,
			UUID productVersionId,
			long principalAmountMinor,
			String currency,
			double annualInterestRate,
			int termMonths,
			UUID debitLedgerAccountId,
			UUID creditLedgerAccountId,
			String actor,
			UUID correlationId) {
	}

	public record DisbursementResponse(
			UUID contractId,
			UUID journalId,
			UUID borrowerAccountId,
			long principalAmountMinor,
			String currency,
			LocalDate disbursedDate,
			LocalDate firstInstallmentDueDate,
			int installmentCount,
			String status) {
	}

	private record RepaymentInstallment(
			int installmentNo,
			LocalDate dueDate,
			long principalDueMinor,
			long interestDueMinor) {
	}

	public record RepaymentRequest(
			UUID contractId,
			UUID payerAccountId,
			long amountMinor,
			String currency,
			UUID debitLedgerAccountId,
			UUID creditLedgerAccountId,
			String actor,
			UUID correlationId) {
	}

	public record RepaymentResponse(
			UUID contractId,
			UUID journalId,
			UUID payerAccountId,
			long amountMinor,
			long principalPaidMinor,
			long interestPaidMinor,
			long feesPaidMinor,
			long outstandingPrincipalAfterMinor,
			String status,
			int updatedInstallmentCount) {
	}

	public record OverdueTransitionResult(
			UUID contractId,
			int overdueInstallmentCount,
			long overdueAmountMinor,
			long outstandingPrincipalMinor,
			String contractStatus) {
	}

	public record DefaultTransitionResult(
			UUID contractId,
			long outstandingPrincipalMinor,
			int overdueInstallmentCount,
			boolean transitioned,
			String contractStatus) {
	}

	private record LoanContractSnapshot(
			UUID contractId,
			UUID borrowerAccountId,
			long outstandingPrincipalMinor,
			String currency,
			String status) {
	}

	private record RepaymentScheduleSnapshot(
			long scheduleId,
			int installmentNo,
			long principalDueMinor,
			long interestDueMinor,
			long feesDueMinor,
			long principalPaidMinor,
			long interestPaidMinor,
			long feesPaidMinor,
			String status) {
	}

	private record RepaymentScheduleUpdate(
			long scheduleId,
			long principalPaidMinor,
			long interestPaidMinor,
			long feesPaidMinor,
			String status) {
	}

	private record AllocationResult(
			List<RepaymentScheduleUpdate> updates,
			long principalAllocatedMinor,
			long interestAllocatedMinor,
			long feesAllocatedMinor,
			long unallocatedAmountMinor,
			long remainingDueAmountMinor,
			int updatedInstallmentCount) {
	}
}
