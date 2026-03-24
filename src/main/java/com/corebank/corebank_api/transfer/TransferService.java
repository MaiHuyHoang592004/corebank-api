package com.corebank.corebank_api.transfer;

import com.corebank.corebank_api.account.AccountBalanceRepository;
import com.corebank.corebank_api.account.CustomerAccount;
import com.corebank.corebank_api.common.CoreBankException;
import com.corebank.corebank_api.common.InsufficientFundsException;
import com.corebank.corebank_api.integration.IdempotencyService;
import com.corebank.corebank_api.integration.OutboxService;
import com.corebank.corebank_api.ledger.LedgerCommandService;
import com.corebank.corebank_api.limits.LimitCheckService;
import com.corebank.corebank_api.ops.audit.AuditService;
import com.corebank.corebank_api.ops.system.SystemModeService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class TransferService {

	private static final Duration IDEMPOTENCY_TTL = Duration.ofDays(1);

	private final AccountBalanceRepository accountBalanceRepository;
	private final LedgerCommandService ledgerCommandService;
	private final IdempotencyService idempotencyService;
	private final AuditService auditService;
	private final OutboxService outboxService;
	private final SystemModeService systemModeService;
	private final LimitCheckService limitCheckService;
	private final ObjectMapper objectMapper = new ObjectMapper();

	public TransferService(
			AccountBalanceRepository accountBalanceRepository,
			LedgerCommandService ledgerCommandService,
			IdempotencyService idempotencyService,
			AuditService auditService,
			OutboxService outboxService,
			SystemModeService systemModeService,
			LimitCheckService limitCheckService) {
		this.accountBalanceRepository = accountBalanceRepository;
		this.ledgerCommandService = ledgerCommandService;
		this.idempotencyService = idempotencyService;
		this.auditService = auditService;
		this.outboxService = outboxService;
		this.systemModeService = systemModeService;
		this.limitCheckService = limitCheckService;
	}

	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public TransferResponse transfer(TransferRequest request) {
		String requestJson = toJson(request);
		IdempotencyService.StartResult startResult = idempotencyService.checkBeforeExecution(
				request.idempotencyKey(),
				requestJson,
				Instant.now().plus(IDEMPOTENCY_TTL));

		if (startResult.replay()) {
			return fromJson(startResult.responseBodyJson(), TransferResponse.class);
		}

		try {
			// Check system mode
			systemModeService.enforceWriteAllowed();

			// Lock accounts in deterministic order to prevent deadlocks
			List<CustomerAccount> lockedAccounts = accountBalanceRepository.lockByIdsInDeterministicOrder(
					List.of(request.sourceAccountId(), request.destinationAccountId()));

			if (lockedAccounts.size() != 2) {
				throw new CoreBankException("Unable to lock both accounts for transfer");
			}

			// Find source and destination accounts by ID (accounts are locked in UUID order)
			CustomerAccount sourceAccount = null;
			CustomerAccount destinationAccount = null;

			for (CustomerAccount account : lockedAccounts) {
				if (account.getCustomerAccountId().equals(request.sourceAccountId())) {
					sourceAccount = account;
				} else if (account.getCustomerAccountId().equals(request.destinationAccountId())) {
					destinationAccount = account;
				}
			}

			if (sourceAccount == null || destinationAccount == null) {
				throw new CoreBankException("Unable to find both accounts after locking");
			}

			if (!"ACTIVE".equals(sourceAccount.getStatus())) {
				throw new CoreBankException("Source account is not active");
			}

			if (!sourceAccount.getCurrency().equals(request.currency())) {
				throw new CoreBankException("Source account currency does not match transfer currency");
			}

			// Validate destination account
			if (!destinationAccount.getCustomerAccountId().equals(request.destinationAccountId())) {
				throw new CoreBankException("Destination account mismatch after locking");
			}

			if (!"ACTIVE".equals(destinationAccount.getStatus())) {
				throw new CoreBankException("Destination account is not active");
			}

			if (!destinationAccount.getCurrency().equals(request.currency())) {
				throw new CoreBankException("Destination account currency does not match transfer currency");
			}

			// Check sufficient funds
			long sourceAvailableBefore = sourceAccount.getAvailableBalanceMinor();
			if (sourceAvailableBefore < request.amountMinor()) {
				throw new InsufficientFundsException("Insufficient available balance for transfer");
			}

			// Check limits for source account
			limitCheckService.enforceLimits(request.sourceAccountId(), request.amountMinor(), request.currency());

			// Calculate new balances
			long sourcePostedBefore = sourceAccount.getPostedBalanceMinor();
			long destinationPostedBefore = destinationAccount.getPostedBalanceMinor();
			long sourcePostedAfter = sourcePostedBefore - request.amountMinor();
			long destinationPostedAfter = destinationPostedBefore + request.amountMinor();
			long sourceAvailableAfter = sourceAvailableBefore - request.amountMinor();
			long destinationAvailableAfter = destinationAccount.getAvailableBalanceMinor() + request.amountMinor();

			// Update source account available balance
			boolean sourceUpdated = accountBalanceRepository.updateAvailableBalance(
					sourceAccount.getCustomerAccountId(),
					sourceAvailableAfter,
					sourceAccount.getVersion());

			if (!sourceUpdated) {
				throw new CoreBankException("Failed to update source account available balance");
			}

			// Update destination account available balance
			boolean destinationUpdated = accountBalanceRepository.updateAvailableBalance(
					destinationAccount.getCustomerAccountId(),
					destinationAvailableAfter,
					destinationAccount.getVersion());

			if (!destinationUpdated) {
				throw new CoreBankException("Failed to update destination account available balance");
			}

			// Post balanced journal
			// Note: completed transfer updates posted balance via ledger posting, while
			// available balance has already been updated above.
			UUID journalId = ledgerCommandService.postJournal(
					new LedgerCommandService.PostJournalCommand(
							"INTERNAL_TRANSFER",
							"TRANSFER",
							UUID.randomUUID(), // transfer reference ID
							request.currency(),
							null,
							request.actor(),
							request.correlationId(),
							List.of(
									new LedgerCommandService.PostingInstruction(
											request.debitLedgerAccountId(),
											request.sourceAccountId(),
											"D",
											request.amountMinor(),
											request.currency(),
											true),
									new LedgerCommandService.PostingInstruction(
											request.creditLedgerAccountId(),
											request.destinationAccountId(),
											"C",
											request.amountMinor(),
											request.currency(),
											true))));

			// Build response
			TransferResponse response = new TransferResponse(
					journalId,
					request.sourceAccountId(),
					request.destinationAccountId(),
					request.amountMinor(),
					request.currency(),
					sourcePostedAfter,
					sourceAvailableBefore,
					sourceAvailableAfter,
					destinationPostedAfter,
					destinationAccount.getAvailableBalanceMinor(),
					destinationAvailableAfter,
					"COMPLETED");

			String responseJson = toJson(response);

			// Write audit event
			auditService.appendEvent(new AuditService.AuditCommand(
					request.actor(),
					"INTERNAL_TRANSFER",
					"TRANSFER",
					journalId.toString(),
					request.correlationId(),
					request.requestId(),
					request.sessionId(),
					request.traceId(),
					null,
					responseJson));

			// Write outbox message
			outboxService.appendMessage(
					"TRANSFER",
					journalId.toString(),
					"TRANSFER_COMPLETED",
					responseJson);

			// Mark idempotency as succeeded
			idempotencyService.markSucceeded(
					request.idempotencyKey(),
					startResult.requestHash(),
					startResult.expiresAt(),
					responseJson);

			// Increment usage counter for limit tracking
			limitCheckService.incrementUsage(request.sourceAccountId(), request.amountMinor(), request.currency());

			return response;
		} catch (RuntimeException ex) {
			idempotencyService.markFailed(request.idempotencyKey(), startResult.requestHash());
			throw ex;
		}
	}

	private String toJson(Object value) {
		try {
			return objectMapper.writeValueAsString(value);
		} catch (JsonProcessingException ex) {
			throw new IllegalStateException("Unable to serialize transfer payload", ex);
		}
	}

	private <T> T fromJson(String json, Class<T> targetType) {
		try {
			return objectMapper.readValue(json, targetType);
		} catch (JsonProcessingException ex) {
			throw new IllegalStateException("Unable to deserialize transfer payload", ex);
		}
	}

	public record TransferRequest(
			String idempotencyKey,
			UUID sourceAccountId,
			UUID destinationAccountId,
			long amountMinor,
			String currency,
			UUID debitLedgerAccountId,
			UUID creditLedgerAccountId,
			String description,
			String actor,
			UUID correlationId,
			UUID requestId,
			UUID sessionId,
			String traceId) {
	}

	public record TransferResponse(
			UUID journalId,
			UUID sourceAccountId,
			UUID destinationAccountId,
			long amountMinor,
			String currency,
			long sourcePostedBalanceMinor,
			long sourceAvailableBalanceBeforeMinor,
			long sourceAvailableBalanceAfterMinor,
			long destinationPostedBalanceMinor,
			long destinationAvailableBalanceBeforeMinor,
			long destinationAvailableBalanceAfterMinor,
			String status) {
	}
}