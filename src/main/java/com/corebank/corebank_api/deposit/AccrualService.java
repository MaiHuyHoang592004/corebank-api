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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AccrualService {

	private static final ObjectMapper objectMapper = new ObjectMapper();

	private final JdbcTemplate jdbcTemplate;
	private final DepositContractRepository depositContractRepository;
	private final DepositAccrualRepository depositAccrualRepository;
	private final DepositEventRepository depositEventRepository;
	private final LedgerCommandService ledgerCommandService;

	public AccrualService(
			JdbcTemplate jdbcTemplate,
			DepositContractRepository depositContractRepository,
			DepositAccrualRepository depositAccrualRepository,
			DepositEventRepository depositEventRepository,
			LedgerCommandService ledgerCommandService) {
		this.jdbcTemplate = jdbcTemplate;
		this.depositContractRepository = depositContractRepository;
		this.depositAccrualRepository = depositAccrualRepository;
		this.depositEventRepository = depositEventRepository;
		this.ledgerCommandService = ledgerCommandService;
	}

	@Transactional
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

				processed++;

			} catch (Exception ex) {
				failed++;
				// Log error but continue processing other contracts
				System.err.println("Failed to accrue interest for contract " + contract.getContractId() + ": " + ex.getMessage());
			}
		}

		return new AccrualBatchResult(processed, skipped, failed);
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