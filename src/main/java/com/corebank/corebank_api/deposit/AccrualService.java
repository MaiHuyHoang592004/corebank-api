package com.corebank.corebank_api.deposit;

import com.corebank.corebank_api.common.CoreBankException;
import com.corebank.corebank_api.ledger.LedgerCommandService;
import lombok.extern.slf4j.Slf4j;
import com.corebank.corebank_api.ledger.LedgerCommandService.PostingInstruction;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Daily interest accrual across all active deposit contracts.
 *
 * <p>Note: nothing currently invokes {@link #processDailyAccruals}; the per-contract
 * {@code /api/deposits/accrue} endpoint goes through {@code DepositContractService} instead. This
 * class is kept as the batch entry point and is not yet registered with {@code BatchRunService},
 * so it does not have the cluster-wide single-run guarantee the other batch jobs have.
 */
@Service
@Slf4j
public class AccrualService {

	private static final ObjectMapper objectMapper = new ObjectMapper();

	private final JdbcTemplate jdbcTemplate;
	private final DepositContractRepository depositContractRepository;
	private final DepositAccrualRepository depositAccrualRepository;
	private final DepositEventRepository depositEventRepository;
	private final LedgerCommandService ledgerCommandService;
	private final TransactionTemplate perContractTransaction;

	public AccrualService(
			JdbcTemplate jdbcTemplate,
			DepositContractRepository depositContractRepository,
			DepositAccrualRepository depositAccrualRepository,
			DepositEventRepository depositEventRepository,
			LedgerCommandService ledgerCommandService,
			PlatformTransactionManager transactionManager) {
		this.jdbcTemplate = jdbcTemplate;
		this.depositContractRepository = depositContractRepository;
		this.depositAccrualRepository = depositAccrualRepository;
		this.depositEventRepository = depositEventRepository;
		this.ledgerCommandService = ledgerCommandService;
		this.perContractTransaction = new TransactionTemplate(transactionManager);
		this.perContractTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
	}

	/**
	 * Accrues one day of interest for every ACTIVE contract.
	 *
	 * <p>Each contract commits in its own transaction. The previous version wrapped the whole batch
	 * in a single {@code @Transactional} and caught per-contract exceptions intending to continue —
	 * but a failure inside {@code postJournal} marks the shared transaction rollback-only and a
	 * SQLException leaves the PostgreSQL transaction aborted, so every later contract fails too and
	 * the entire batch is discarded at commit while the returned counters still report success.
	 */
	public AccrualBatchResult processDailyAccruals(AccrualBatchRequest request) {
		LocalDate today = LocalDate.now();
		int processed = 0;
		int skipped = 0;
		int failed = 0;

		// Get all active contracts
		List<DepositContract> activeContracts = depositContractRepository.findByStatus("ACTIVE");

		for (DepositContract contract : activeContracts) {
			try {
				// Skip if accrual already exists for today
				if (depositAccrualRepository.findByContractIdAndAccrualDate(contract.getContractId(), today).isPresent()) {
					skipped++;
					continue;
				}

				boolean accrued = perContractTransaction.execute(status -> accrueOneContract(contract, today, request));

				if (accrued) {
					processed++;
				} else {
					skipped++;
				}
			} catch (Exception ex) {
				failed++;
				log.error(
						"Failed to accrue interest for deposit contract {}",
						contract.getContractId(),
						ex);
			}
		}

		return new AccrualBatchResult(processed, skipped, failed);
	}

	/**
	 * Accrues a single contract inside its own transaction.
	 *
	 * @return {@code true} when interest was posted, {@code false} when the contract rounded to zero
	 *     interest for the day and was skipped
	 */
	private boolean accrueOneContract(DepositContract contract, LocalDate today, AccrualBatchRequest request) {
		// Calculate daily interest
		BigDecimal principal = new BigDecimal(contract.getPrincipalAmount());
		BigDecimal annualRate = new BigDecimal(contract.getInterestRate());
		BigDecimal dailyRate = annualRate.divide(new BigDecimal(365), 10, RoundingMode.HALF_UP);
		BigDecimal dailyInterest = principal.multiply(dailyRate).divide(new BigDecimal(100), 0, RoundingMode.DOWN);

		long accruedInterest = dailyInterest.longValue();

		// A contract whose daily interest rounds down to zero must be skipped, not posted.
		// postJournal rejects non-positive amounts and ledger_postings has a
		// CHECK (amount_minor > 0), so attempting it would abort this contract's transaction.
		if (accruedInterest <= 0) {
			return false;
		}

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
						"DEPOSIT_ACCRUAL_BATCH",
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
		DepositEvent event = new DepositEvent();
		event.setContractId(contract.getContractId());
		event.setEventType("ACCURED");
		event.setAmountMinor(accruedInterest);
		event.setMetadataJson(buildAccrualMetadata(today, accruedInterest, runningBalance));
		event.setCreatedAt(java.time.Instant.now());

		depositEventRepository.save(event);

		return true;
	}

	private String buildAccrualMetadata(LocalDate accrualDate, long accruedInterest, long runningBalance) {
		try {
			return objectMapper.writeValueAsString(java.util.Map.of(
					"accrualDate", accrualDate,
					"accruedInterest", accruedInterest,
					"runningBalance", runningBalance));
		} catch (JsonProcessingException e) {
			return "{}";
		}
	}

	public record AccrualBatchRequest(
			UUID debitLedgerAccountId,
			UUID creditLedgerAccountId,
			String actor,
			UUID correlationId) {
	}

	public record AccrualBatchResult(
			int processed,
			int skipped,
			int failed) {
	}
}