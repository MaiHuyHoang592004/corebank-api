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
}