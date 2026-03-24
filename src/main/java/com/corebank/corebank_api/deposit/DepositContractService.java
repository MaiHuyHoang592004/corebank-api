package com.corebank.corebank_api.deposit;

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
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class DepositContractService {

	private static final ObjectMapper objectMapper = new ObjectMapper();

	private final JdbcTemplate jdbcTemplate;
	private final AccountBalanceRepository accountBalanceRepository;
	private final LedgerCommandService ledgerCommandService;
	private final DepositContractRepository depositContractRepository;
	private final DepositAccrualRepository depositAccrualRepository;
	private final DepositEventRepository depositEventRepository;

	public DepositContractService(
			JdbcTemplate jdbcTemplate,
			AccountBalanceRepository accountBalanceRepository,
			LedgerCommandService ledgerCommandService,
			DepositContractRepository depositContractRepository,
			DepositAccrualRepository depositAccrualRepository,
			DepositEventRepository depositEventRepository) {
		this.jdbcTemplate = jdbcTemplate;
		this.accountBalanceRepository = accountBalanceRepository;
		this.ledgerCommandService = ledgerCommandService;
		this.depositContractRepository = depositContractRepository;
		this.depositAccrualRepository = depositAccrualRepository;
		this.depositEventRepository = depositEventRepository;
	}

	@Transactional
	public OpenDepositResponse openDeposit(OpenDepositRequest request) {
		// Validate request
		if (request.principalAmountMinor() <= 0) {
			throw new CoreBankException("Principal amount must be positive");
		}

		if (request.termMonths() <= 0) {
			throw new CoreBankException("Term months must be positive");
		}

		if (request.interestRate() < 0 || request.interestRate() > 100.0) {
			throw new CoreBankException("Interest rate must be between 0 and 100");
		}

		// Lock customer account
		CustomerAccount lockedAccount = accountBalanceRepository.lockById(request.customerAccountId())
				.orElseThrow(() -> new CoreBankException("Customer account not found: " + request.customerAccountId()));

		if (!"ACTIVE".equals(lockedAccount.getStatus())) {
			throw new CoreBankException("Customer account is not active");
		}

		if (!lockedAccount.getCurrency().equals(request.currency())) {
			throw new CoreBankException("Account currency does not match deposit currency");
		}

		// Check sufficient funds
		if (lockedAccount.getAvailableBalanceMinor() < request.principalAmountMinor()) {
			throw new CoreBankException("Insufficient available balance for deposit");
		}

		// Calculate dates
		LocalDate startDate = LocalDate.now();
		LocalDate maturityDate = startDate.plusMonths(request.termMonths());

		// Update customer account balance
		long newAvailableBalance = lockedAccount.getAvailableBalanceMinor() - request.principalAmountMinor();
		boolean updated = accountBalanceRepository.updateAvailableBalance(
				lockedAccount.getCustomerAccountId(),
				newAvailableBalance,
				lockedAccount.getVersion());

		if (!updated) {
			throw new CoreBankException("Failed to update customer account balance");
		}

		// Create deposit contract
		DepositContract contract = new DepositContract();
		contract.setContractId(UUID.randomUUID());
		contract.setCustomerAccountId(request.customerAccountId());
		contract.setProductId(request.productId());
		contract.setProductVersionId(request.productVersionId());
		contract.setPrincipalAmount(request.principalAmountMinor());
		contract.setCurrency(request.currency());
		contract.setInterestRate(request.interestRate());
		contract.setTermMonths(request.termMonths());
		contract.setStartDate(startDate);
		contract.setMaturityDate(maturityDate);
		contract.setStatus("ACTIVE");
		contract.setEarlyClosurePenaltyRate(request.earlyClosurePenaltyRate());
		contract.setAutoRenew(request.autoRenew());
		contract.setCreatedAt(java.time.Instant.now());
		contract.setUpdatedAt(java.time.Instant.now());

		insertDepositContract(contract);

		// Post journal for principal movement
		UUID journalId = ledgerCommandService.postJournal(
				new LedgerCommandService.PostJournalCommand(
						"DEPOSIT_OPEN",
						"DEPOSIT_CONTRACT",
						contract.getContractId(),
						request.currency(),
						null,
						request.actor(),
						request.correlationId(),
						List.of(
								new PostingInstruction(
										request.debitLedgerAccountId(),
										request.customerAccountId(),
										"D",
										request.principalAmountMinor(),
										request.currency(),
										true), // Update posted balance for deposits
								new PostingInstruction(
										request.creditLedgerAccountId(),
										null,
										"C",
										request.principalAmountMinor(),
										request.currency(),
										false)))); // No customer account update needed

		// Record deposit event
		insertDepositEvent(
				contract.getContractId(),
				"OPENED",
				request.principalAmountMinor(),
				buildEventMetadata(request));

		return new OpenDepositResponse(
				contract.getContractId(),
				contract.getCustomerAccountId(),
				request.principalAmountMinor(),
				request.currency(),
				startDate,
				maturityDate,
				request.interestRate(),
				"ACTIVE");
	}

	@Transactional
	public AccrueInterestResponse accrueInterest(AccrueInterestRequest request) {
		DepositContract contract = depositContractRepository.findById(request.contractId())
				.orElseThrow(() -> new CoreBankException("Deposit contract not found: " + request.contractId()));

		ensureActiveContract(contract, "accrue interest");

		// Check if accrual already exists for today
		LocalDate today = LocalDate.now();
		if (depositAccrualRepository.findByContractIdAndAccrualDate(contract.getContractId(), today).isPresent()) {
			throw new CoreBankException("Interest already accrued for today");
		}

		// Calculate daily interest
		BigDecimal principal = new BigDecimal(contract.getPrincipalAmount());
		BigDecimal annualRate = new BigDecimal(contract.getInterestRate());
		BigDecimal dailyRate = annualRate.divide(new BigDecimal(365), 10, RoundingMode.HALF_UP);
		BigDecimal dailyInterest = principal.multiply(dailyRate).divide(new BigDecimal(100), 0, RoundingMode.DOWN);

		long accruedInterest = dailyInterest.longValue();

		// Get last accrual to calculate running balance
		List<DepositAccrual> existingAccruals = depositAccrualRepository
				.findByContractIdOrderByAccrualDateDesc(contract.getContractId());

		long runningBalance = existingAccruals.stream()
				.mapToLong(DepositAccrual::getRunningBalance)
				.findFirst()
				.orElse(0L);

		runningBalance += accruedInterest;

		// Create accrual record
		DepositAccrual accrual = new DepositAccrual();
		accrual.setContractId(contract.getContractId());
		accrual.setAccrualDate(today);
		accrual.setAccruedInterest(accruedInterest);
		accrual.setRunningBalance(runningBalance);
		accrual.setCreatedAt(java.time.Instant.now());

		depositAccrualRepository.save(accrual);

		// Post journal for interest accrual
		UUID journalId = ledgerCommandService.postJournal(
				new LedgerCommandService.PostJournalCommand(
						"DEPOSIT_ACCRUAL",
						"DEPOSIT_ACCRUAL",
						UUID.randomUUID(),
						contract.getCurrency(),
						null,
						request.actor(),
						request.correlationId(),
						List.of(
								new PostingInstruction(
										request.debitLedgerAccountId(), // Interest Expense
										null,
										"D",
										accruedInterest,
										contract.getCurrency(),
										false),
								new PostingInstruction(
										request.creditLedgerAccountId(), // Interest Payable
										null,
										"C",
										accruedInterest,
										contract.getCurrency(),
										false))));

		// Record accrual event
		insertDepositEvent(
				contract.getContractId(),
				"ACCURED",
				accruedInterest,
				buildAccrualMetadata(today, accruedInterest, runningBalance));

		return new AccrueInterestResponse(
				contract.getContractId(),
				accrual.getAccrualId(),
				today,
				accruedInterest,
				runningBalance,
				contract.getCurrency());
	}

	@Transactional
	public MaturityResponse processMaturity(MaturityRequest request) {
		DepositContract contract = depositContractRepository.findById(request.contractId())
				.orElseThrow(() -> new CoreBankException("Deposit contract not found: " + request.contractId()));

		ensureActiveContract(contract, "process maturity");

		if (contract.getMaturityDate().isAfter(LocalDate.now())) {
			throw new CoreBankException("Deposit contract has not matured yet");
		}

		if (contract.isAutoRenew()) {
			throw new CoreBankException("Auto-renew deposit maturity processing is not supported in this slice");
		}

		// Get total accrued interest
		List<DepositAccrual> accruals = depositAccrualRepository
				.findByContractIdOrderByAccrualDateDesc(contract.getContractId());

		long totalAccruedInterest = accruals.stream()
				.mapToLong(DepositAccrual::getRunningBalance)
				.findFirst()
				.orElse(0L);

		// Update contract status
		contract.setStatus("MATURED");
		contract.setUpdatedAt(java.time.Instant.now());
		jdbcTemplate.update(
				"UPDATE deposit_contracts SET status = ?, updated_at = ? WHERE contract_id = ?",
				contract.getStatus(),
				Timestamp.from(contract.getUpdatedAt()),
				contract.getContractId());

		// Update customer account balance (return principal + interest)
		CustomerAccount account = accountBalanceRepository.lockById(contract.getCustomerAccountId())
				.orElseThrow(() -> new CoreBankException("Customer account not found"));

		long newAvailableBalance = account.getAvailableBalanceMinor() + contract.getPrincipalAmount() + totalAccruedInterest;
		boolean updated = accountBalanceRepository.updateAvailableBalance(
				account.getCustomerAccountId(),
				newAvailableBalance,
				account.getVersion());

		if (!updated) {
			throw new CoreBankException("Failed to update customer account balance");
		}

		// Post journal for maturity payment
		UUID journalId = ledgerCommandService.postJournal(
				new LedgerCommandService.PostJournalCommand(
						"DEPOSIT_MATURITY",
						"DEPOSIT_CONTRACT",
						contract.getContractId(),
						contract.getCurrency(),
						null,
						request.actor(),
						request.correlationId(),
						List.of(
								new PostingInstruction(
										request.debitLedgerAccountId(), // Interest Payable
										null,
										"D",
										totalAccruedInterest,
										contract.getCurrency(),
										false),
								new PostingInstruction(
										request.debitLedgerAccountId(), // Term Deposits Liability
										null,
										"D",
										contract.getPrincipalAmount(),
										contract.getCurrency(),
										false),
								new PostingInstruction(
										request.creditLedgerAccountId(), // Customer Account
										contract.getCustomerAccountId(),
										"C",
										contract.getPrincipalAmount() + totalAccruedInterest,
										contract.getCurrency(),
										true))));

		// Record maturity event
		insertDepositEvent(
				contract.getContractId(),
				"MATURED",
				contract.getPrincipalAmount() + totalAccruedInterest,
				buildMaturityMetadata(totalAccruedInterest));

		return new MaturityResponse(
				contract.getContractId(),
				contract.getCustomerAccountId(),
				contract.getPrincipalAmount(),
				totalAccruedInterest,
				contract.getCurrency(),
				"MATURED");
	}

	private String buildEventMetadata(OpenDepositRequest request) {
		try {
			return objectMapper.writeValueAsString(request);
		} catch (JsonProcessingException e) {
			return "{}";
		}
	}

	private String buildAccrualMetadata(LocalDate accrualDate, long accruedInterest, long runningBalance) {
		try {
			return objectMapper.writeValueAsString(Map.of(
					"accrualDate", accrualDate,
					"accruedInterest", accruedInterest,
					"runningBalance", runningBalance));
		} catch (JsonProcessingException e) {
			return "{}";
		}
	}

	private String buildMaturityMetadata(long totalAccruedInterest) {
		try {
			return objectMapper.writeValueAsString(Map.of(
					"totalAccruedInterest", totalAccruedInterest));
		} catch (JsonProcessingException e) {
			return "{}";
		}
	}

	private void insertDepositEvent(UUID contractId, String eventType, long amountMinor, String metadataJson) {
		jdbcTemplate.update(
				"""
				INSERT INTO deposit_events (contract_id, event_type, amount_minor, metadata_json, created_at)
				VALUES (?, ?, ?, CAST(? AS jsonb), NOW())
				""",
				contractId,
				eventType,
				amountMinor,
				metadataJson);
	}

	private void insertDepositContract(DepositContract contract) {
		jdbcTemplate.update(
				"""
				INSERT INTO deposit_contracts (
				    contract_id,
				    customer_account_id,
				    product_id,
				    product_version_id,
				    principal_amount,
				    currency,
				    interest_rate,
				    term_months,
				    start_date,
				    maturity_date,
				    status,
				    early_closure_penalty_rate,
				    auto_renew,
				    created_at,
				    updated_at
				) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
				""",
				contract.getContractId(),
				contract.getCustomerAccountId(),
				contract.getProductId(),
				contract.getProductVersionId(),
				contract.getPrincipalAmount(),
				contract.getCurrency(),
				contract.getInterestRate(),
				contract.getTermMonths(),
				contract.getStartDate(),
				contract.getMaturityDate(),
				contract.getStatus(),
				contract.getEarlyClosurePenaltyRate(),
				contract.isAutoRenew(),
				Timestamp.from(contract.getCreatedAt()),
				Timestamp.from(contract.getUpdatedAt()));
	}

	private void ensureActiveContract(DepositContract contract, String action) {
		if (!"ACTIVE".equals(contract.getStatus())) {
			throw new CoreBankException("Deposit contract must be ACTIVE to " + action);
		}
	}

	public record OpenDepositRequest(
			UUID customerAccountId,
			UUID productId,
			UUID productVersionId,
			long principalAmountMinor,
			String currency,
			double interestRate,
			int termMonths,
			double earlyClosurePenaltyRate,
			boolean autoRenew,
			UUID debitLedgerAccountId,
			UUID creditLedgerAccountId,
			String actor,
			UUID correlationId) {
	}

	public record OpenDepositResponse(
			UUID contractId,
			UUID customerAccountId,
			long principalAmountMinor,
			String currency,
			LocalDate startDate,
			LocalDate maturityDate,
			double interestRate,
			String status) {
	}

	public record AccrueInterestRequest(
			UUID contractId,
			UUID debitLedgerAccountId,
			UUID creditLedgerAccountId,
			String actor,
			UUID correlationId) {
	}

	public record AccrueInterestResponse(
			UUID contractId,
			Long accrualId,
			LocalDate accrualDate,
			long accruedInterest,
			long runningBalance,
			String currency) {
	}

	public record MaturityRequest(
			UUID contractId,
			UUID debitLedgerAccountId,
			UUID creditLedgerAccountId,
			String actor,
			UUID correlationId) {
	}

	public record MaturityResponse(
			UUID contractId,
			UUID customerAccountId,
			long principalAmountMinor,
			long totalAccruedInterest,
			String currency,
			String status) {
	}
}
