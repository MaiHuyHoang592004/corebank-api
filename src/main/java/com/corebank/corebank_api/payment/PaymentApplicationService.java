package com.corebank.corebank_api.payment;

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
public class PaymentApplicationService {

	private static final Logger log = LoggerFactory.getLogger(PaymentApplicationService.class);
	private static final Duration IDEMPOTENCY_TTL = Duration.ofDays(1);

	private final IdempotencyService idempotencyService;
	private final HoldService holdService;
	private final AuditService auditService;
	private final OutboxService outboxService;
	private final SystemModeService systemModeService;
	private final PaymentRetryPolicy paymentRetryPolicy;
	private final TransactionTemplate transactionTemplate;
	private final ObjectMapper objectMapper = new ObjectMapper();

	public PaymentApplicationService(
			IdempotencyService idempotencyService,
			HoldService holdService,
			AuditService auditService,
			OutboxService outboxService,
			SystemModeService systemModeService,
			PaymentRetryPolicy paymentRetryPolicy,
			PlatformTransactionManager transactionManager) {
		this.idempotencyService = idempotencyService;
		this.holdService = holdService;
		this.auditService = auditService;
		this.outboxService = outboxService;
		this.systemModeService = systemModeService;
		this.paymentRetryPolicy = paymentRetryPolicy;
		this.transactionTemplate = new TransactionTemplate(transactionManager);
		this.transactionTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
	}

	public AuthorizeHoldResponse authorizeHold(AuthorizeHoldRequest request) {
		return executeWithRetry(
				"authorizeHold",
				request.idempotencyKey(),
				() -> Objects.requireNonNull(
						transactionTemplate.execute(status -> executeAuthorizeHoldAttempt(request)),
						"authorizeHold attempt returned null response"));
	}

	private AuthorizeHoldResponse executeAuthorizeHoldAttempt(AuthorizeHoldRequest request) {
		String requestJson = toJson(request);
		IdempotencyService.StartResult startResult = idempotencyService.checkBeforeExecution(
				request.idempotencyKey(),
				requestJson,
				Instant.now().plus(IDEMPOTENCY_TTL));

		if (startResult.replay()) {
			return fromJson(startResult.responseBodyJson(), AuthorizeHoldResponse.class);
		}

		try {
			systemModeService.enforceWriteAllowed();

			HoldService.AuthorizationResult authorization = holdService.authorizeHold(
					new HoldService.AuthorizeHoldCommand(
							request.payerAccountId(),
							request.payeeAccountId(),
							request.amountMinor(),
							request.currency(),
							request.paymentType(),
							request.description()));

			AuthorizeHoldResponse response = new AuthorizeHoldResponse(
					authorization.paymentOrderId(),
					authorization.holdId(),
					authorization.payerAccountId(),
					authorization.postedBalanceMinor(),
					authorization.availableBalanceBeforeMinor(),
					authorization.availableBalanceAfterMinor(),
					authorization.holdAmountMinor(),
					authorization.currency(),
					authorization.status());

			String responseJson = toJson(response);

			auditService.appendEvent(new AuditService.AuditCommand(
					request.actor(),
					"PAYMENT_HOLD_AUTHORIZED",
					"PAYMENT_ORDER",
					authorization.paymentOrderId().toString(),
					request.correlationId(),
					request.requestId(),
					request.sessionId(),
					request.traceId(),
					null,
					responseJson));

			outboxService.appendMessage(
					"PAYMENT_ORDER",
					authorization.paymentOrderId().toString(),
					"PAYMENT_AUTHORIZED",
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

	public CaptureHoldResponse captureHold(CaptureHoldRequest request) {
		return executeWithRetry(
				"captureHold",
				request.idempotencyKey(),
				() -> Objects.requireNonNull(
						transactionTemplate.execute(status -> executeCaptureHoldAttempt(request)),
						"captureHold attempt returned null response"));
	}

	private CaptureHoldResponse executeCaptureHoldAttempt(CaptureHoldRequest request) {
		String requestJson = toJson(request);
		IdempotencyService.StartResult startResult = idempotencyService.checkBeforeExecution(
				request.idempotencyKey(),
				requestJson,
				Instant.now().plus(IDEMPOTENCY_TTL));

		if (startResult.replay()) {
			return fromJson(startResult.responseBodyJson(), CaptureHoldResponse.class);
		}

		try {
			systemModeService.enforceWriteAllowed();

			HoldService.CaptureResult capture = holdService.captureHold(
					new HoldService.CaptureHoldCommand(
							request.holdId(),
							request.amountMinor(),
							request.debitLedgerAccountId(),
							request.creditLedgerAccountId(),
							request.beneficiaryCustomerAccountId(),
							request.actor(),
							request.correlationId()));

			CaptureHoldResponse response = new CaptureHoldResponse(
					capture.paymentOrderId(),
					capture.holdId(),
					capture.journalId(),
					capture.capturedAmountMinor(),
					capture.remainingAmountMinor(),
					capture.holdStatus(),
					capture.paymentStatus(),
					capture.currency());

			String responseJson = toJson(response);

			auditService.appendEvent(new AuditService.AuditCommand(
					request.actor(),
					"PAYMENT_HOLD_CAPTURED",
					"HOLD",
					capture.holdId().toString(),
					request.correlationId(),
					request.requestId(),
					request.sessionId(),
					request.traceId(),
					null,
					responseJson));

			outboxService.appendMessage(
					"HOLD",
					capture.holdId().toString(),
					"PAYMENT_CAPTURED",
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

	public VoidHoldResponse voidHold(VoidHoldRequest request) {
		return executeWithRetry(
				"voidHold",
				request.idempotencyKey(),
				() -> Objects.requireNonNull(
						transactionTemplate.execute(status -> executeVoidHoldAttempt(request)),
						"voidHold attempt returned null response"));
	}

	private VoidHoldResponse executeVoidHoldAttempt(VoidHoldRequest request) {
		String requestJson = toJson(request);
		IdempotencyService.StartResult startResult = idempotencyService.checkBeforeExecution(
				request.idempotencyKey(),
				requestJson,
				Instant.now().plus(IDEMPOTENCY_TTL));

		if (startResult.replay()) {
			return fromJson(startResult.responseBodyJson(), VoidHoldResponse.class);
		}

		try {
			systemModeService.enforceWriteAllowed();

			HoldService.VoidResult voidResult = holdService.voidHold(new HoldService.VoidHoldCommand(request.holdId()));

			VoidHoldResponse response = new VoidHoldResponse(
					voidResult.paymentOrderId(),
					voidResult.holdId(),
					voidResult.restoredAmountMinor(),
					voidResult.availableBalanceBeforeMinor(),
					voidResult.availableBalanceAfterMinor(),
					voidResult.currency(),
					voidResult.status());

			String responseJson = toJson(response);

			auditService.appendEvent(new AuditService.AuditCommand(
					request.actor(),
					"PAYMENT_HOLD_VOIDED",
					"HOLD",
					voidResult.holdId().toString(),
					request.correlationId(),
					request.requestId(),
					request.sessionId(),
					request.traceId(),
					null,
					responseJson));

			outboxService.appendMessage(
					"HOLD",
					voidResult.holdId().toString(),
					"PAYMENT_VOIDED",
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
		int maxAttempts = paymentRetryPolicy.maxAttempts();
		for (int attempt = 1; attempt <= maxAttempts; attempt++) {
			try {
				return action.get();
			} catch (RuntimeException ex) {
				if (!paymentRetryPolicy.isTransient(ex) || attempt >= maxAttempts) {
					throw ex;
				}

				String sqlState = paymentRetryPolicy.extractSqlState(ex);
				long backoffMillis = paymentRetryPolicy.backoffMillisBeforeNextAttempt(attempt);
				log.warn(
						"Transient payment failure op={} idempotencyKey={} attempt={}/{} type={} sqlState={} retryInMs={}",
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

		throw new CoreBankException("Payment retry attempts exhausted unexpectedly");
	}

	private void sleepBackoff(long backoffMillis) {
		try {
			Thread.sleep(backoffMillis);
		} catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
			throw new CoreBankException("Payment retry was interrupted", ex);
		}
	}

	private String toJson(Object value) {
		try {
			return objectMapper.writeValueAsString(value);
		} catch (JsonProcessingException ex) {
			throw new IllegalStateException("Unable to serialize payment payload", ex);
		}
	}

	private <T> T fromJson(String json, Class<T> targetType) {
		try {
			return objectMapper.readValue(json, targetType);
		} catch (JsonProcessingException ex) {
			throw new IllegalStateException("Unable to deserialize payment payload", ex);
		}
	}

	public record AuthorizeHoldRequest(
			String idempotencyKey,
			UUID payerAccountId,
			UUID payeeAccountId,
			long amountMinor,
			String currency,
			String paymentType,
			String description,
			String actor,
			UUID correlationId,
			UUID requestId,
			UUID sessionId,
			String traceId) {
	}

	public record AuthorizeHoldResponse(
			UUID paymentOrderId,
			UUID holdId,
			UUID payerAccountId,
			long postedBalanceMinor,
			long availableBalanceBeforeMinor,
			long availableBalanceAfterMinor,
			long holdAmountMinor,
			String currency,
			String status) {
	}

	public record CaptureHoldRequest(
			String idempotencyKey,
			UUID holdId,
			long amountMinor,
			UUID debitLedgerAccountId,
			UUID creditLedgerAccountId,
			UUID beneficiaryCustomerAccountId,
			String actor,
			UUID correlationId,
			UUID requestId,
			UUID sessionId,
			String traceId) {
	}

	public record CaptureHoldResponse(
			UUID paymentOrderId,
			UUID holdId,
			UUID journalId,
			long capturedAmountMinor,
			long remainingAmountMinor,
			String holdStatus,
			String paymentStatus,
			String currency) {
	}

	public record VoidHoldRequest(
			String idempotencyKey,
			UUID holdId,
			String actor,
			UUID correlationId,
			UUID requestId,
			UUID sessionId,
			String traceId) {
	}

	public record VoidHoldResponse(
			UUID paymentOrderId,
			UUID holdId,
			long restoredAmountMinor,
			long availableBalanceBeforeMinor,
			long availableBalanceAfterMinor,
			String currency,
			String status) {
	}
}
