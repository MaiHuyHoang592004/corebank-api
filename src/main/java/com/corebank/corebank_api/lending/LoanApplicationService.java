package com.corebank.corebank_api.lending;

import com.corebank.corebank_api.common.IdempotentMoneyCommandTemplate;
import com.corebank.corebank_api.integration.OutboxMetadata;
import com.corebank.corebank_api.integration.OutboxService;
import com.corebank.corebank_api.ops.audit.AuditService;
import com.corebank.corebank_api.ops.system.SystemModeService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class LoanApplicationService {

	private static final Logger log = LoggerFactory.getLogger(LoanApplicationService.class);
	private static final Duration IDEMPOTENCY_TTL = Duration.ofDays(1);

	private final SystemModeService systemModeService;
	private final LoanContractService loanContractService;
	private final AuditService auditService;
	private final OutboxService outboxService;
	private final LoanRetryPolicy loanRetryPolicy;
	private final IdempotentMoneyCommandTemplate moneyCommandTemplate;
	private final ObjectMapper objectMapper;
	private final TransactionTemplate batchTransactionTemplate;

	public LoanApplicationService(
			SystemModeService systemModeService,
			LoanContractService loanContractService,
			AuditService auditService,
			OutboxService outboxService,
			ObjectMapper objectMapper,
			LoanRetryPolicy loanRetryPolicy,
			IdempotentMoneyCommandTemplate moneyCommandTemplate,
			PlatformTransactionManager transactionManager) {
		this.systemModeService = systemModeService;
		this.loanContractService = loanContractService;
		this.auditService = auditService;
		this.outboxService = outboxService;
		this.objectMapper = objectMapper;
		this.loanRetryPolicy = loanRetryPolicy;
		this.moneyCommandTemplate = moneyCommandTemplate;
		// Programmatic REQUIRES_NEW + retry, same shape as IdempotentMoneyCommandTemplate,
		// so these two batch jobs get the same transient-deadlock retry every other
		// money-moving path already has — a declarative @Transactional here would need
		// a self-invoked call to retry, which never goes through the AOP proxy and would
		// silently retry without ever opening a fresh transaction.
		this.batchTransactionTemplate = new TransactionTemplate(transactionManager);
		this.batchTransactionTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
	}

	private <T> T executeWithRetry(String operation, java.util.function.Supplier<T> action) {
		int maxAttempts = loanRetryPolicy.maxAttempts();
		for (int attempt = 1; attempt <= maxAttempts; attempt++) {
			try {
				return batchTransactionTemplate.execute(status -> action.get());
			} catch (RuntimeException ex) {
				if (!loanRetryPolicy.isTransient(ex) || attempt >= maxAttempts) {
					throw ex;
				}
				long backoffMillis = loanRetryPolicy.backoffMillisBeforeNextAttempt(attempt);
				log.warn(
						"Transient failure in {} attempt={}/{} sqlState={} retryInMs={}",
						operation,
						attempt,
						maxAttempts,
						loanRetryPolicy.extractSqlState(ex),
						backoffMillis);
				try {
					Thread.sleep(backoffMillis);
				} catch (InterruptedException interrupted) {
					Thread.currentThread().interrupt();
					throw new IllegalStateException(operation + " retry was interrupted", interrupted);
				}
			}
		}
		throw new IllegalStateException(operation + " retry attempts exhausted unexpectedly");
	}

	public LoanDisbursementResponse disburseLoan(LoanDisbursementRequest request) {
		return moneyCommandTemplate.execute(
				"disburseLoan",
				request.idempotencyKey(),
				request,
				LoanDisbursementResponse.class,
				IDEMPOTENCY_TTL,
				loanRetryPolicy,
				() -> executeDisburseLoanBusiness(request),
				(response, responseJson) -> {
					auditService.appendEvent(new AuditService.AuditCommand(
							request.actor(),
							"LOAN_DISBURSED",
							"LOAN_CONTRACT",
							response.contractId().toString(),
							request.correlationId(),
							request.requestId(),
							request.sessionId(),
							request.traceId(),
							null,
							responseJson));

					outboxService.appendMessage(
							"LOAN_CONTRACT",
							response.contractId().toString(),
							"LOAN_DISBURSED",
							response,
							OutboxMetadata.of(request.correlationId(), request.requestId(), request.actor()));
				});
	}

	private LoanDisbursementResponse executeDisburseLoanBusiness(LoanDisbursementRequest request) {
		LoanContractService.DisbursementResponse disbursement = loanContractService.disburseLoan(
				new LoanContractService.DisbursementRequest(
						request.borrowerAccountId(),
						request.productId(),
						request.productVersionId(),
						request.principalAmountMinor(),
						request.currency(),
						request.annualInterestRate(),
						request.termMonths(),
						request.debitLedgerAccountId(),
						request.creditLedgerAccountId(),
						request.actor(),
						request.correlationId()));

		return new LoanDisbursementResponse(
				disbursement.contractId(),
				disbursement.journalId(),
				disbursement.borrowerAccountId(),
				disbursement.principalAmountMinor(),
				disbursement.currency(),
				disbursement.disbursedDate(),
				disbursement.firstInstallmentDueDate(),
				disbursement.installmentCount(),
				disbursement.status());
	}

	public LoanRepaymentResponse repayLoan(LoanRepaymentRequest request) {
		return moneyCommandTemplate.execute(
				"repayLoan",
				request.idempotencyKey(),
				request,
				LoanRepaymentResponse.class,
				IDEMPOTENCY_TTL,
				loanRetryPolicy,
				() -> executeRepayLoanBusiness(request),
				(response, responseJson) -> {
					auditService.appendEvent(new AuditService.AuditCommand(
							request.actor(),
							"LOAN_REPAID",
							"LOAN_CONTRACT",
							request.contractId().toString(),
							request.correlationId(),
							request.requestId(),
							request.sessionId(),
							request.traceId(),
							null,
							responseJson));

					outboxService.appendMessage(
							"LOAN_CONTRACT",
							request.contractId().toString(),
							"LOAN_REPAID",
							response,
							OutboxMetadata.of(request.correlationId(), request.requestId(), request.actor()));
				});
	}

	private LoanRepaymentResponse executeRepayLoanBusiness(LoanRepaymentRequest request) {
		LoanContractService.RepaymentResponse repayment = loanContractService.repayLoan(
				new LoanContractService.RepaymentRequest(
						request.contractId(),
						request.payerAccountId(),
						request.amountMinor(),
						request.currency(),
						request.debitLedgerAccountId(),
						request.creditLedgerAccountId(),
						request.actor(),
						request.correlationId()));

		return new LoanRepaymentResponse(
				repayment.contractId(),
				repayment.journalId(),
				repayment.payerAccountId(),
				repayment.amountMinor(),
				repayment.principalPaidMinor(),
				repayment.interestPaidMinor(),
				repayment.feesPaidMinor(),
				repayment.outstandingPrincipalAfterMinor(),
				repayment.status(),
				repayment.updatedInstallmentCount());
	}

	public LoanOverdueTransitionResponse markOverdueInstallments(LoanOverdueTransitionRequest request) {
		return executeWithRetry("markOverdueInstallments", () -> markOverdueInstallmentsOnce(request));
	}

	private LoanOverdueTransitionResponse markOverdueInstallmentsOnce(LoanOverdueTransitionRequest request) {
		systemModeService.enforceWriteAllowed();

		List<LoanContractService.OverdueTransitionResult> transitioned = loanContractService.markOverdueInstallments(request.asOfDate());
		int affectedInstallmentCount = 0;

		for (LoanContractService.OverdueTransitionResult result : transitioned) {
			affectedInstallmentCount += result.overdueInstallmentCount();

			OverdueTransitionContractPayload payload = new OverdueTransitionContractPayload(
					request.asOfDate(),
					result.contractId(),
					result.overdueInstallmentCount(),
					result.overdueAmountMinor(),
					result.outstandingPrincipalMinor(),
					result.contractStatus());

			String payloadJson = toJson(payload);

			auditService.appendEvent(new AuditService.AuditCommand(
					request.actor(),
					"LOAN_OVERDUE_MARKED",
					"LOAN_CONTRACT",
					result.contractId().toString(),
					request.correlationId(),
					request.requestId(),
					request.sessionId(),
					request.traceId(),
					null,
					payloadJson));

			outboxService.appendMessage(
					"LOAN_CONTRACT",
					result.contractId().toString(),
					"LOAN_OVERDUE",
					payload,
					OutboxMetadata.of(request.correlationId(), request.requestId(), request.actor()));
		}

		List<UUID> affectedContractIds = transitioned.stream()
				.map(LoanContractService.OverdueTransitionResult::contractId)
				.sorted(UUID::compareTo)
				.toList();

		return new LoanOverdueTransitionResponse(
				request.asOfDate(),
				affectedContractIds.size(),
				affectedInstallmentCount,
				affectedContractIds);
	}

	public LoanDefaultTransitionResponse markContractDefaulted(LoanDefaultTransitionRequest request) {
		return executeWithRetry("markContractDefaulted", () -> markContractDefaultedOnce(request));
	}

	private LoanDefaultTransitionResponse markContractDefaultedOnce(LoanDefaultTransitionRequest request) {
		systemModeService.enforceWriteAllowed();

		LoanContractService.DefaultTransitionResult result = loanContractService.markContractDefaulted(
				request.contractId(),
				request.asOfDate());

		if (result.transitioned()) {
			DefaultTransitionPayload payload = new DefaultTransitionPayload(
					request.asOfDate(),
					result.contractId(),
					result.outstandingPrincipalMinor(),
					result.overdueInstallmentCount(),
					result.contractStatus());

			String payloadJson = toJson(payload);

			auditService.appendEvent(new AuditService.AuditCommand(
					request.actor(),
					"LOAN_DEFAULTED",
					"LOAN_CONTRACT",
					result.contractId().toString(),
					request.correlationId(),
					request.requestId(),
					request.sessionId(),
					request.traceId(),
					null,
					payloadJson));

			outboxService.appendMessage(
					"LOAN_CONTRACT",
					result.contractId().toString(),
					"LOAN_DEFAULTED",
					payload,
					OutboxMetadata.of(request.correlationId(), request.requestId(), request.actor()));
		}

		return new LoanDefaultTransitionResponse(
				request.contractId(),
				request.asOfDate(),
				result.outstandingPrincipalMinor(),
				result.overdueInstallmentCount(),
				result.transitioned(),
				result.contractStatus());
	}

	private String toJson(Object value) {
		try {
			return objectMapper.writeValueAsString(value);
		} catch (JsonProcessingException ex) {
			throw new IllegalStateException("Unable to serialize loan payload", ex);
		}
	}

	public record LoanDisbursementRequest(
			String idempotencyKey,
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
			UUID correlationId,
			UUID requestId,
			UUID sessionId,
			String traceId) {
		/** Returns a copy with {@code actor} replaced — used to bind it to the authenticated principal. */
		public LoanDisbursementRequest withActor(String authenticatedActor) {
			return new LoanDisbursementRequest(
					idempotencyKey, borrowerAccountId, productId, productVersionId, principalAmountMinor,
					currency, annualInterestRate, termMonths, debitLedgerAccountId, creditLedgerAccountId,
					authenticatedActor, correlationId, requestId, sessionId, traceId);
		}
	}

	public record LoanDisbursementResponse(
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

	public record LoanRepaymentRequest(
			String idempotencyKey,
			UUID contractId,
			UUID payerAccountId,
			long amountMinor,
			String currency,
			UUID debitLedgerAccountId,
			UUID creditLedgerAccountId,
			String actor,
			UUID correlationId,
			UUID requestId,
			UUID sessionId,
			String traceId) {
		public LoanRepaymentRequest withActor(String authenticatedActor) {
			return new LoanRepaymentRequest(
					idempotencyKey, contractId, payerAccountId, amountMinor, currency,
					debitLedgerAccountId, creditLedgerAccountId, authenticatedActor,
					correlationId, requestId, sessionId, traceId);
		}
	}

	public record LoanRepaymentResponse(
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

	public record LoanOverdueTransitionRequest(
			LocalDate asOfDate,
			String actor,
			UUID correlationId,
			UUID requestId,
			UUID sessionId,
			String traceId) {
	}

	public record LoanOverdueTransitionResponse(
			LocalDate asOfDate,
			int affectedContractCount,
			int affectedInstallmentCount,
			List<UUID> affectedContractIds) {
	}

	public record LoanDefaultTransitionRequest(
			UUID contractId,
			LocalDate asOfDate,
			String actor,
			UUID correlationId,
			UUID requestId,
			UUID sessionId,
			String traceId) {
	}

	public record LoanDefaultTransitionResponse(
			UUID contractId,
			LocalDate asOfDate,
			long outstandingPrincipalMinor,
			int overdueInstallmentCount,
			boolean transitioned,
			String status) {
	}

	private record OverdueTransitionContractPayload(
			LocalDate asOfDate,
			UUID contractId,
			int overdueInstallmentCount,
			long overdueAmountMinor,
			long outstandingPrincipalMinor,
			String status) {
	}

	private record DefaultTransitionPayload(
			LocalDate asOfDate,
			UUID contractId,
			long outstandingPrincipalMinor,
			int overdueInstallmentCount,
			String status) {
	}
}
