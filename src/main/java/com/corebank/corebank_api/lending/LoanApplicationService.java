package com.corebank.corebank_api.lending;

import com.corebank.corebank_api.integration.IdempotencyService;
import com.corebank.corebank_api.integration.OutboxMetadata;
import com.corebank.corebank_api.integration.OutboxService;
import com.corebank.corebank_api.ops.audit.AuditService;
import com.corebank.corebank_api.ops.system.SystemModeService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class LoanApplicationService {

	private static final Duration IDEMPOTENCY_TTL = Duration.ofDays(1);

	private final IdempotencyService idempotencyService;
	private final SystemModeService systemModeService;
	private final LoanContractService loanContractService;
	private final AuditService auditService;
	private final OutboxService outboxService;
	private final ObjectMapper objectMapper;

	public LoanApplicationService(
			IdempotencyService idempotencyService,
			SystemModeService systemModeService,
			LoanContractService loanContractService,
			AuditService auditService,
			OutboxService outboxService,
			ObjectMapper objectMapper) {
		this.idempotencyService = idempotencyService;
		this.systemModeService = systemModeService;
		this.loanContractService = loanContractService;
		this.auditService = auditService;
		this.outboxService = outboxService;
		this.objectMapper = objectMapper.copy().findAndRegisterModules();
	}

	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public LoanDisbursementResponse disburseLoan(LoanDisbursementRequest request) {
		String requestJson = toJson(request);
		IdempotencyService.StartResult startResult = idempotencyService.checkBeforeExecution(
				request.idempotencyKey(),
				requestJson,
				Instant.now().plus(IDEMPOTENCY_TTL));

		if (startResult.replay()) {
			return fromJson(startResult.responseBodyJson(), LoanDisbursementResponse.class);
		}

		try {
			systemModeService.enforceWriteAllowed();

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

			LoanDisbursementResponse response = new LoanDisbursementResponse(
					disbursement.contractId(),
					disbursement.journalId(),
					disbursement.borrowerAccountId(),
					disbursement.principalAmountMinor(),
					disbursement.currency(),
					disbursement.disbursedDate(),
					disbursement.firstInstallmentDueDate(),
					disbursement.installmentCount(),
					disbursement.status());

			String responseJson = toJson(response);

			auditService.appendEvent(new AuditService.AuditCommand(
					request.actor(),
					"LOAN_DISBURSED",
					"LOAN_CONTRACT",
					disbursement.contractId().toString(),
					request.correlationId(),
					request.requestId(),
					request.sessionId(),
					request.traceId(),
					null,
					responseJson));

			outboxService.appendMessage(
					"LOAN_CONTRACT",
					disbursement.contractId().toString(),
					"LOAN_DISBURSED",
					response,
					OutboxMetadata.of(request.correlationId(), request.requestId(), request.actor()));

			idempotencyService.markSucceeded(
					request.idempotencyKey(),
					startResult.requestHash(),
					startResult.expiresAt(),
					responseJson);

			return response;
		} catch (RuntimeException ex) {
			idempotencyService.markFailed(request.idempotencyKey(), startResult.requestHash());
			throw ex;
		}
	}

	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public LoanRepaymentResponse repayLoan(LoanRepaymentRequest request) {
		String requestJson = toJson(request);
		IdempotencyService.StartResult startResult = idempotencyService.checkBeforeExecution(
				request.idempotencyKey(),
				requestJson,
				Instant.now().plus(IDEMPOTENCY_TTL));

		if (startResult.replay()) {
			return fromJson(startResult.responseBodyJson(), LoanRepaymentResponse.class);
		}

		try {
			systemModeService.enforceWriteAllowed();

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

			LoanRepaymentResponse response = new LoanRepaymentResponse(
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

			String responseJson = toJson(response);

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

			idempotencyService.markSucceeded(
					request.idempotencyKey(),
					startResult.requestHash(),
					startResult.expiresAt(),
					responseJson);

			return response;
		} catch (RuntimeException ex) {
			idempotencyService.markFailed(request.idempotencyKey(), startResult.requestHash());
			throw ex;
		}
	}

	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public LoanOverdueTransitionResponse markOverdueInstallments(LoanOverdueTransitionRequest request) {
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
				.toList();

		return new LoanOverdueTransitionResponse(
				request.asOfDate(),
				affectedContractIds.size(),
				affectedInstallmentCount,
				affectedContractIds);
	}

	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public LoanDefaultTransitionResponse markContractDefaulted(LoanDefaultTransitionRequest request) {
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

	private <T> T fromJson(String json, Class<T> targetType) {
		try {
			return objectMapper.readValue(json, targetType);
		} catch (JsonProcessingException ex) {
			throw new IllegalStateException("Unable to deserialize loan payload", ex);
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
