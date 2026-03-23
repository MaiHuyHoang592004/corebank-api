package com.corebank.corebank_api.deposit;

import com.corebank.corebank_api.integration.IdempotencyService;
import com.corebank.corebank_api.integration.OutboxService;
import com.corebank.corebank_api.ledger.LedgerCommandService;
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
public class DepositApplicationService {

	private static final Duration IDEMPOTENCY_TTL = Duration.ofDays(1);

	private final IdempotencyService idempotencyService;
	private final SystemModeService systemModeService;
	private final DepositContractService depositContractService;
	private final AuditService auditService;
	private final OutboxService outboxService;
	private final ObjectMapper objectMapper;

	public DepositApplicationService(
			IdempotencyService idempotencyService,
			SystemModeService systemModeService,
			DepositContractService depositContractService,
			AuditService auditService,
			OutboxService outboxService,
			ObjectMapper objectMapper) {
		this.idempotencyService = idempotencyService;
		this.systemModeService = systemModeService;
		this.depositContractService = depositContractService;
		this.auditService = auditService;
		this.outboxService = outboxService;
		this.objectMapper = objectMapper.copy().findAndRegisterModules();
	}

	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public OpenDepositResponse openDeposit(OpenDepositRequest request) {
		String requestJson = toJson(request);
		IdempotencyService.StartResult startResult = idempotencyService.checkBeforeExecution(
				request.idempotencyKey(),
				requestJson,
				Instant.now().plus(IDEMPOTENCY_TTL));

		if (startResult.replay()) {
			return fromJson(startResult.responseBodyJson(), OpenDepositResponse.class);
		}

		try {
			// Check system mode
			systemModeService.enforceWriteAllowed();

			// Open deposit
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

			OpenDepositResponse response = new OpenDepositResponse(
					contractResponse.contractId(),
					contractResponse.customerAccountId(),
					contractResponse.principalAmountMinor(),
					contractResponse.currency(),
					contractResponse.startDate(),
					contractResponse.maturityDate(),
					contractResponse.interestRate(),
					contractResponse.status());

			String responseJson = toJson(response);

			// Write audit event
			auditService.appendEvent(new AuditService.AuditCommand(
					request.actor(),
					"DEPOSIT_OPENED",
					"DEPOSIT_CONTRACT",
					contractResponse.contractId().toString(),
					request.correlationId(),
					request.requestId(),
					request.sessionId(),
					request.traceId(),
					null,
					responseJson));

			// Write outbox message
			outboxService.appendMessage(
					"DEPOSIT_CONTRACT",
					contractResponse.contractId().toString(),
					"DEPOSIT_OPENED",
					responseJson);

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
	public AccrueInterestResponse accrueInterest(AccrueInterestRequest request) {
		String requestJson = toJson(request);
		IdempotencyService.StartResult startResult = idempotencyService.checkBeforeExecution(
				request.idempotencyKey(),
				requestJson,
				Instant.now().plus(IDEMPOTENCY_TTL));

		if (startResult.replay()) {
			return fromJson(startResult.responseBodyJson(), AccrueInterestResponse.class);
		}

		try {
			// Check system mode
			systemModeService.enforceWriteAllowed();

			// Accrue interest
			DepositContractService.AccrueInterestResponse contractResponse = depositContractService.accrueInterest(
					new DepositContractService.AccrueInterestRequest(
							request.contractId(),
							request.debitLedgerAccountId(),
							request.creditLedgerAccountId(),
							request.actor(),
							request.correlationId()));

			AccrueInterestResponse response = new AccrueInterestResponse(
					contractResponse.contractId(),
					contractResponse.accrualId(),
					contractResponse.accrualDate(),
					contractResponse.accruedInterest(),
					contractResponse.runningBalance(),
					contractResponse.currency());

			String responseJson = toJson(response);

			// Write audit event
			auditService.appendEvent(new AuditService.AuditCommand(
					request.actor(),
					"DEPOSIT_ACCRUED",
					"DEPOSIT_ACCRUAL",
					contractResponse.accrualId().toString(),
					request.correlationId(),
					request.requestId(),
					request.sessionId(),
					request.traceId(),
					null,
					responseJson));

			// Write outbox message
			outboxService.appendMessage(
					"DEPOSIT_ACCRUAL",
					contractResponse.accrualId().toString(),
					"DEPOSIT_ACCRUED",
					responseJson);

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
	public MaturityResponse processMaturity(MaturityRequest request) {
		String requestJson = toJson(request);
		IdempotencyService.StartResult startResult = idempotencyService.checkBeforeExecution(
				request.idempotencyKey(),
				requestJson,
				Instant.now().plus(IDEMPOTENCY_TTL));

		if (startResult.replay()) {
			return fromJson(startResult.responseBodyJson(), MaturityResponse.class);
		}

		try {
			// Check system mode
			systemModeService.enforceWriteAllowed();

			// Process maturity
			DepositContractService.MaturityResponse contractResponse = depositContractService.processMaturity(
					new DepositContractService.MaturityRequest(
							request.contractId(),
							request.debitLedgerAccountId(),
							request.creditLedgerAccountId(),
							request.actor(),
							request.correlationId()));

			MaturityResponse response = new MaturityResponse(
					contractResponse.contractId(),
					contractResponse.customerAccountId(),
					contractResponse.principalAmountMinor(),
					contractResponse.totalAccruedInterest(),
					contractResponse.currency(),
					contractResponse.status());

			String responseJson = toJson(response);

			// Write audit event
			auditService.appendEvent(new AuditService.AuditCommand(
					request.actor(),
					"DEPOSIT_MATURED",
					"DEPOSIT_CONTRACT",
					contractResponse.contractId().toString(),
					request.correlationId(),
					request.requestId(),
					request.sessionId(),
					request.traceId(),
					null,
					responseJson));

			// Write outbox message
			outboxService.appendMessage(
					"DEPOSIT_CONTRACT",
					contractResponse.contractId().toString(),
					"DEPOSIT_MATURED",
					responseJson);

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

	private String toJson(Object value) {
		try {
			return objectMapper.writeValueAsString(value);
		} catch (JsonProcessingException ex) {
			throw new IllegalStateException("Unable to serialize deposit payload", ex);
		}
	}

	private <T> T fromJson(String json, Class<T> targetType) {
		try {
			return objectMapper.readValue(json, targetType);
		} catch (JsonProcessingException ex) {
			throw new IllegalStateException("Unable to deserialize deposit payload", ex);
		}
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