package com.corebank.corebank_api.deposit;

import com.corebank.corebank_api.common.CoreBankException;
import com.corebank.corebank_api.ledger.LedgerCommandService;
import com.corebank.corebank_api.ledger.LedgerCommandService.PostingInstruction;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class AccrualService {

	private static final Logger log = LoggerFactory.getLogger(AccrualService.class);
	private static final ObjectMapper objectMapper = new ObjectMapper();

	private final JdbcTemplate jdbcTemplate;
	private final DepositContractRepository depositContractRepository;
	private final DepositAccrualRepository depositAccrualRepository;
	private final DepositEventRepository depositEventRepository;
	private final LedgerCommandService ledgerCommandService;
	private final TransactionTemplate perContractTransactionTemplate;

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
		// PROPAGATION_REQUIRES_NEW, driven programmatically (same pattern as
		// IdempotentMoneyCommandTemplate) rather than a declarative @Transactional
		// on a private/self-invoked method — a self-invocation never goes through
		// the Spring AOP proxy, so a per-item @Transactional here would silently
		// do nothing and every contract would still share the caller's transaction.
		this.perContractTransactionTemplate = new TransactionTemplate(transactionManager);
		this.perContractTransactionTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
	}

	/**
	 * Runs with NO surrounding transaction of its own: each contract is posted in
	 * its own REQUIRES_NEW transaction (see {@link #accrueOneContract}), so one
	 * contract's failure marks only that contract's transaction rollback-only and
	 * cannot doom the commits already made for every other contract in the batch.
	 * A single outer @Transactional here would defeat the per-item catch below —
	 * any RuntimeException thrown by a participating (non-REQUIRES_NEW) call marks
	 * the WHOLE ambient transaction rollback-only even when the caller catches it,
	 * so the batch would still throw UnexpectedRollbackException on return and
	 * silently discard every accrual processed that day, not just the failing one.
	 */
	public AccrualBatchResult processDailyAccruals(AccrualBatchRequest request) {
		LocalDate today = LocalDate.now();
		int processed = 0;
		int skipped = 0;
		int failed = 0;

		List<DepositContract> activeContracts = depositContractRepository.findByStatus("ACTIVE");

		for (DepositContract contract : activeContracts) {
			if (depositAccrualRepository.findByContractIdAndAccrualDate(contract.getContractId(), today).isPresent()) {
				skipped++;
				continue;
			}

			try {
				perContractTransactionTemplate.executeWithoutResult(
						status -> accrueOneContract(contract, request, today));
				processed++;
			} catch (Exception ex) {
				failed++;
				log.error(
						"Failed to accrue interest for contract {}: {}",
						contract.getContractId(),
						ex.getMessage(),
						ex);
			}
		}

		return new AccrualBatchResult(processed, skipped, failed);
	}

	private void accrueOneContract(DepositContract contract, AccrualBatchRequest request, LocalDate today) {
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
		ledgerCommandService.postJournal(
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