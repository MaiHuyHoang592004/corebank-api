package com.corebank.corebank_api.payment;

import com.corebank.corebank_api.integration.IdempotencyService;
import com.corebank.corebank_api.integration.OutboxService;
import com.corebank.corebank_api.ops.audit.AuditService;
import com.corebank.corebank_api.ops.system.SystemModeService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PaymentApplicationService {

	private static final Duration IDEMPOTENCY_TTL = Duration.ofDays(1);

	private final IdempotencyService idempotencyService;
	private final HoldService holdService;
	private final AuditService auditService;
	private final OutboxService outboxService;
	private final SystemModeService systemModeService;
	private final ObjectMapper objectMapper = new ObjectMapper();

	public PaymentApplicationService(
			IdempotencyService idempotencyService,
			HoldService holdService,
			AuditService auditService,
			OutboxService outboxService,
			SystemModeService systemModeService) {
		this.idempotencyService = idempotencyService;
		this.holdService = holdService;
		this.auditService = auditService;
		this.outboxService = outboxService;
		this.systemModeService = systemModeService;
	}

	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public AuthorizeHoldResponse authorizeHold(AuthorizeHoldRequest request) {
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
	public CaptureHoldResponse captureHold(CaptureHoldRequest request) {
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
	public VoidHoldResponse voidHold(VoidHoldRequest request) {
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