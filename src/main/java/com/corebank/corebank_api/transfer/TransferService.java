package com.corebank.corebank_api.transfer;

import com.corebank.corebank_api.account.AccountBalanceRepository;
import com.corebank.corebank_api.account.CustomerAccount;
import com.corebank.corebank_api.common.CoreBankException;
import com.corebank.corebank_api.common.IdempotentMoneyCommandTemplate;
import com.corebank.corebank_api.common.InsufficientFundsException;
import com.corebank.corebank_api.integration.OutboxMetadata;
import com.corebank.corebank_api.integration.OutboxService;
import com.corebank.corebank_api.ledger.LedgerCommandService;
import com.corebank.corebank_api.limits.LimitCheckService;
import com.corebank.corebank_api.ops.audit.AuditService;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class TransferService {

	private static final Duration IDEMPOTENCY_TTL = Duration.ofDays(1);

	private final AccountBalanceRepository accountBalanceRepository;
	private final LedgerCommandService ledgerCommandService;
	private final AuditService auditService;
	private final OutboxService outboxService;
	private final LimitCheckService limitCheckService;
	private final TransferRetryPolicy transferRetryPolicy;
	private final IdempotentMoneyCommandTemplate moneyCommandTemplate;

	public TransferService(
			AccountBalanceRepository accountBalanceRepository,
			LedgerCommandService ledgerCommandService,
			AuditService auditService,
			OutboxService outboxService,
			LimitCheckService limitCheckService,
			TransferRetryPolicy transferRetryPolicy,
			IdempotentMoneyCommandTemplate moneyCommandTemplate) {
		this.accountBalanceRepository = accountBalanceRepository;
		this.ledgerCommandService = ledgerCommandService;
		this.auditService = auditService;
		this.outboxService = outboxService;
		this.limitCheckService = limitCheckService;
		this.transferRetryPolicy = transferRetryPolicy;
		this.moneyCommandTemplate = moneyCommandTemplate;
	}

	public TransferResponse transfer(TransferRequest request) {
		return moneyCommandTemplate.execute(
				"transfer",
				request.idempotencyKey(),
				request,
				TransferResponse.class,
				IDEMPOTENCY_TTL,
				transferRetryPolicy,
				() -> executeTransferBusiness(request),
				(response, responseJson) -> {
					auditService.appendEvent(new AuditService.AuditCommand(
							request.actor(),
							"INTERNAL_TRANSFER",
							"TRANSFER",
							response.journalId().toString(),
							request.correlationId(),
							request.requestId(),
							request.sessionId(),
							request.traceId(),
							null,
							responseJson));

					outboxService.appendMessage(
							"TRANSFER",
							response.journalId().toString(),
							"TRANSFER_COMPLETED",
							response,
							OutboxMetadata.of(request.correlationId(), request.requestId(), request.actor()));
				},
				response -> limitCheckService.incrementUsage(
						request.sourceAccountId(),
						request.amountMinor(),
						request.currency()));
	}

	private TransferResponse executeTransferBusiness(TransferRequest request) {
		List<CustomerAccount> lockedAccounts = accountBalanceRepository.lockByIdsInDeterministicOrder(
				List.of(request.sourceAccountId(), request.destinationAccountId()));

		if (lockedAccounts.size() != 2) {
			throw new CoreBankException("Unable to lock both accounts for transfer");
		}

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

		if (!destinationAccount.getCustomerAccountId().equals(request.destinationAccountId())) {
			throw new CoreBankException("Destination account mismatch after locking");
		}

		if (!"ACTIVE".equals(destinationAccount.getStatus())) {
			throw new CoreBankException("Destination account is not active");
		}

		if (!destinationAccount.getCurrency().equals(request.currency())) {
			throw new CoreBankException("Destination account currency does not match transfer currency");
		}

		long sourceAvailableBefore = sourceAccount.getAvailableBalanceMinor();
		if (sourceAvailableBefore < request.amountMinor()) {
			throw new InsufficientFundsException("Insufficient available balance for transfer");
		}

		limitCheckService.enforceLimits(request.sourceAccountId(), request.amountMinor(), request.currency());

		long sourcePostedBefore = sourceAccount.getPostedBalanceMinor();
		long destinationPostedBefore = destinationAccount.getPostedBalanceMinor();
		long sourcePostedAfter = sourcePostedBefore - request.amountMinor();
		long destinationPostedAfter = destinationPostedBefore + request.amountMinor();
		long sourceAvailableAfter = sourceAvailableBefore - request.amountMinor();
		long destinationAvailableAfter = destinationAccount.getAvailableBalanceMinor() + request.amountMinor();

		boolean sourceUpdated = accountBalanceRepository.updateAvailableBalance(
				sourceAccount.getCustomerAccountId(),
				sourceAvailableAfter,
				sourceAccount.getVersion());

		if (!sourceUpdated) {
			throw new CoreBankException("Failed to update source account available balance");
		}

		boolean destinationUpdated = accountBalanceRepository.updateAvailableBalance(
				destinationAccount.getCustomerAccountId(),
				destinationAvailableAfter,
				destinationAccount.getVersion());

		if (!destinationUpdated) {
			throw new CoreBankException("Failed to update destination account available balance");
		}

		UUID journalId = ledgerCommandService.postJournal(
				new LedgerCommandService.PostJournalCommand(
						"INTERNAL_TRANSFER",
						"TRANSFER",
						UUID.randomUUID(),
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

		return new TransferResponse(
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
