package com.corebank.corebank_api.deposit;

import com.corebank.corebank_api.common.IdempotentMoneyCommandTemplate;
import com.corebank.corebank_api.integration.OutboxMetadata;
import com.corebank.corebank_api.integration.OutboxService;
import com.corebank.corebank_api.ops.audit.AuditService;
import java.time.Duration;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class DepositApplicationService {

	private static final Duration IDEMPOTENCY_TTL = Duration.ofDays(1);

	private final DepositContractService depositContractService;
	private final AuditService auditService;
	private final OutboxService outboxService;
	private final DepositRetryPolicy depositRetryPolicy;
	private final IdempotentMoneyCommandTemplate moneyCommandTemplate;

	public DepositApplicationService(
			DepositContractService depositContractService,
			AuditService auditService,
			OutboxService outboxService,
			DepositRetryPolicy depositRetryPolicy,
			IdempotentMoneyCommandTemplate moneyCommandTemplate) {
		this.depositContractService = depositContractService;
		this.auditService = auditService;
		this.outboxService = outboxService;
		this.depositRetryPolicy = depositRetryPolicy;
		this.moneyCommandTemplate = moneyCommandTemplate;
	}

	public OpenDepositResponse openDeposit(OpenDepositRequest request) {
		return moneyCommandTemplate.execute(
				"openDeposit",
				request.idempotencyKey(),
				request,
				OpenDepositResponse.class,
				IDEMPOTENCY_TTL,
				depositRetryPolicy,
				() -> executeOpenDepositBusiness(request),
				(response, responseJson) -> {
					auditService.appendEvent(new AuditService.AuditCommand(
							request.actor(),
							"DEPOSIT_OPENED",
							"DEPOSIT_CONTRACT",
							response.contractId().toString(),
							request.correlationId(),
							request.requestId(),
							request.sessionId(),
							request.traceId(),
							null,
							responseJson));

					outboxService.appendMessage(
							"DEPOSIT_CONTRACT",
							response.contractId().toString(),
							"DEPOSIT_OPENED",
							response,
							OutboxMetadata.of(request.correlationId(), request.requestId(), request.actor()));
				});
	}

	private OpenDepositResponse executeOpenDepositBusiness(OpenDepositRequest request) {
		DepositContractService.OpenDepositResponse contractResponse = depositContractService.openDeposit(
				new DepositContractService.OpenDepositRequest(
						request.customerAccountId(),
						request.productId(),
						request.productVersionId(),
						request.principalAmountMinor(),
						request.currency(),
						request.interestRate(),
						request.termMonths(),
						request.earlyClosurePenaltyRate(),
						request.autoRenew(),
						request.debitLedgerAccountId(),
						request.creditLedgerAccountId(),
						request.actor(),
						request.correlationId()));

		return new OpenDepositResponse(
				contractResponse.contractId(),
				contractResponse.customerAccountId(),
				contractResponse.principalAmountMinor(),
				contractResponse.currency(),
				contractResponse.startDate(),
				contractResponse.maturityDate(),
				contractResponse.interestRate(),
				contractResponse.status());
	}

	public AccrueInterestResponse accrueInterest(AccrueInterestRequest request) {
		return moneyCommandTemplate.execute(
				"accrueInterest",
				request.idempotencyKey(),
				request,
				AccrueInterestResponse.class,
				IDEMPOTENCY_TTL,
				depositRetryPolicy,
				() -> executeAccrueInterestBusiness(request),
				(response, responseJson) -> {
					auditService.appendEvent(new AuditService.AuditCommand(
							request.actor(),
							"DEPOSIT_ACCRUED",
							"DEPOSIT_ACCRUAL",
							response.accrualId().toString(),
							request.correlationId(),
							request.requestId(),
							request.sessionId(),
							request.traceId(),
							null,
							responseJson));

					outboxService.appendMessage(
							"DEPOSIT_ACCRUAL",
							response.accrualId().toString(),
							"DEPOSIT_ACCRUED",
							response,
							OutboxMetadata.of(request.correlationId(), request.requestId(), request.actor()));
				});
	}

	private AccrueInterestResponse executeAccrueInterestBusiness(AccrueInterestRequest request) {
		DepositContractService.AccrueInterestResponse contractResponse = depositContractService.accrueInterest(
				new DepositContractService.AccrueInterestRequest(
						request.contractId(),
						request.debitLedgerAccountId(),
						request.creditLedgerAccountId(),
						request.actor(),
						request.correlationId()));

		return new AccrueInterestResponse(
				contractResponse.contractId(),
				contractResponse.accrualId(),
				contractResponse.accrualDate(),
				contractResponse.accruedInterest(),
				contractResponse.runningBalance(),
				contractResponse.currency());
	}

	public MaturityResponse processMaturity(MaturityRequest request) {
		return moneyCommandTemplate.execute(
				"processMaturity",
				request.idempotencyKey(),
				request,
				MaturityResponse.class,
				IDEMPOTENCY_TTL,
				depositRetryPolicy,
				() -> executeProcessMaturityBusiness(request),
				(response, responseJson) -> {
					auditService.appendEvent(new AuditService.AuditCommand(
							request.actor(),
							"DEPOSIT_MATURED",
							"DEPOSIT_CONTRACT",
							response.contractId().toString(),
							request.correlationId(),
							request.requestId(),
							request.sessionId(),
							request.traceId(),
							null,
							responseJson));

					outboxService.appendMessage(
							"DEPOSIT_CONTRACT",
							response.contractId().toString(),
							"DEPOSIT_MATURED",
							response,
							OutboxMetadata.of(request.correlationId(), request.requestId(), request.actor()));
				});
	}

	private MaturityResponse executeProcessMaturityBusiness(MaturityRequest request) {
		DepositContractService.MaturityResponse contractResponse = depositContractService.processMaturity(
				new DepositContractService.MaturityRequest(
						request.contractId(),
						request.debitLedgerAccountId(),
						request.creditLedgerAccountId(),
						request.actor(),
						request.correlationId()));

		return new MaturityResponse(
				contractResponse.contractId(),
				contractResponse.customerAccountId(),
				contractResponse.principalAmountMinor(),
				contractResponse.totalAccruedInterest(),
				contractResponse.currency(),
				contractResponse.status());
	}

	public record OpenDepositRequest(
			String idempotencyKey,
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
			UUID correlationId,
			UUID requestId,
			UUID sessionId,
			String traceId) {
	}

	public record OpenDepositResponse(
			UUID contractId,
			UUID customerAccountId,
			long principalAmountMinor,
			String currency,
			java.time.LocalDate startDate,
			java.time.LocalDate maturityDate,
			double interestRate,
			String status) {
	}

	public record AccrueInterestRequest(
			String idempotencyKey,
			UUID contractId,
			UUID debitLedgerAccountId,
			UUID creditLedgerAccountId,
			String actor,
			UUID correlationId,
			UUID requestId,
			UUID sessionId,
			String traceId) {
	}

	public record AccrueInterestResponse(
			UUID contractId,
			Long accrualId,
			java.time.LocalDate accrualDate,
			long accruedInterest,
			long runningBalance,
			String currency) {
	}

	public record MaturityRequest(
			String idempotencyKey,
			UUID contractId,
			UUID debitLedgerAccountId,
			UUID creditLedgerAccountId,
			String actor,
			UUID correlationId,
			UUID requestId,
			UUID sessionId,
			String traceId) {
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
