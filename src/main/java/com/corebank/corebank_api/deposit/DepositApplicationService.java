package com.corebank.corebank_api.deposit;

import com.corebank.corebank_api.common.CoreBankException;
import com.corebank.corebank_api.integration.IdempotencyService;
import com.corebank.corebank_api.integration.OutboxMetadata;
import com.corebank.corebank_api.integration.OutboxService;
import com.corebank.corebank_api.ops.audit.AuditService;
import com.corebank.corebank_api.ops.system.SystemModeService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class DepositApplicationService {

	private static final Logger log = LoggerFactory.getLogger(DepositApplicationService.class);
	private static final Duration IDEMPOTENCY_TTL = Duration.ofDays(1);

	private final IdempotencyService idempotencyService;
	private final SystemModeService systemModeService;
	private final DepositContractService depositContractService;
	private final AuditService auditService;
	private final OutboxService outboxService;
	private final DepositRetryPolicy depositRetryPolicy;
	private final TransactionTemplate transactionTemplate;
	private final ObjectMapper objectMapper;

	public DepositApplicationService(
			IdempotencyService idempotencyService,
			SystemModeService systemModeService,
			DepositContractService depositContractService,
			AuditService auditService,
			OutboxService outboxService,
			ObjectMapper objectMapper,
			DepositRetryPolicy depositRetryPolicy,
			PlatformTransactionManager transactionManager) {
		this.idempotencyService = idempotencyService;
		this.systemModeService = systemModeService;
		this.depositContractService = depositContractService;
		this.auditService = auditService;
		this.outboxService = outboxService;
		this.depositRetryPolicy = depositRetryPolicy;
		this.transactionTemplate = new TransactionTemplate(transactionManager);
		this.transactionTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
		this.objectMapper = objectMapper.copy().findAndRegisterModules();
	}

	public OpenDepositResponse openDeposit(OpenDepositRequest request) {
		return executeWithRetry(
				"openDeposit",
				request.idempotencyKey(),
				() -> Objects.requireNonNull(
						transactionTemplate.execute(status -> executeOpenDepositAttempt(request)),
						"openDeposit attempt returned null response"));
	}

	private OpenDepositResponse executeOpenDepositAttempt(OpenDepositRequest request) {
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

	public AccrueInterestResponse accrueInterest(AccrueInterestRequest request) {
		return executeWithRetry(
				"accrueInterest",
				request.idempotencyKey(),
				() -> Objects.requireNonNull(
						transactionTemplate.execute(status -> executeAccrueInterestAttempt(request)),
						"accrueInterest attempt returned null response"));
	}

	private AccrueInterestResponse executeAccrueInterestAttempt(AccrueInterestRequest request) {
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

	public MaturityResponse processMaturity(MaturityRequest request) {
		return executeWithRetry(
				"processMaturity",
				request.idempotencyKey(),
				() -> Objects.requireNonNull(
						transactionTemplate.execute(status -> executeProcessMaturityAttempt(request)),
						"processMaturity attempt returned null response"));
	}

	private MaturityResponse executeProcessMaturityAttempt(MaturityRequest request) {
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

	private <T> T executeWithRetry(String operation, String idempotencyKey, Supplier<T> action) {
		int maxAttempts = depositRetryPolicy.maxAttempts();
		for (int attempt = 1; attempt <= maxAttempts; attempt++) {
			try {
				return action.get();
			} catch (RuntimeException ex) {
				if (!depositRetryPolicy.isTransient(ex) || attempt >= maxAttempts) {
					throw ex;
				}

				String sqlState = depositRetryPolicy.extractSqlState(ex);
				long backoffMillis = depositRetryPolicy.backoffMillisBeforeNextAttempt(attempt);
				log.warn(
						"Transient deposit failure op={} idempotencyKey={} attempt={}/{} type={} sqlState={} retryInMs={}",
						operation,
						idempotencyKey,
						attempt,
						maxAttempts,
						ex.getClass().getSimpleName(),
						sqlState,
						backoffMillis);
				sleepBackoff(backoffMillis);
			}
		}

		throw new CoreBankException("Deposit retry attempts exhausted unexpectedly");
	}

	private void sleepBackoff(long backoffMillis) {
		try {
			Thread.sleep(backoffMillis);
		} catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
			throw new CoreBankException("Deposit retry was interrupted", ex);
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
