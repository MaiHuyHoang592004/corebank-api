package com.corebank.corebank_api.payment;

import com.corebank.corebank_api.common.IdempotentMoneyCommandTemplate;
import com.corebank.corebank_api.integration.OutboxMetadata;
import com.corebank.corebank_api.integration.OutboxService;
import com.corebank.corebank_api.ops.audit.AuditService;
import java.time.Duration;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class PaymentApplicationService {

	private static final Duration IDEMPOTENCY_TTL = Duration.ofDays(1);

	private final HoldService holdService;
	private final AuditService auditService;
	private final OutboxService outboxService;
	private final PaymentRetryPolicy paymentRetryPolicy;
	private final IdempotentMoneyCommandTemplate moneyCommandTemplate;

	public PaymentApplicationService(
			HoldService holdService,
			AuditService auditService,
			OutboxService outboxService,
			PaymentRetryPolicy paymentRetryPolicy,
			IdempotentMoneyCommandTemplate moneyCommandTemplate) {
		this.holdService = holdService;
		this.auditService = auditService;
		this.outboxService = outboxService;
		this.paymentRetryPolicy = paymentRetryPolicy;
		this.moneyCommandTemplate = moneyCommandTemplate;
	}

	public AuthorizeHoldResponse authorizeHold(AuthorizeHoldRequest request) {
		return moneyCommandTemplate.execute(
				"authorizeHold",
				request.idempotencyKey(),
				request,
				AuthorizeHoldResponse.class,
				IDEMPOTENCY_TTL,
				paymentRetryPolicy,
				() -> executeAuthorizeHoldBusiness(request),
				(response, responseJson) -> {
					auditService.appendEvent(new AuditService.AuditCommand(
							request.actor(),
							"PAYMENT_HOLD_AUTHORIZED",
							"PAYMENT_ORDER",
							response.paymentOrderId().toString(),
							request.correlationId(),
							request.requestId(),
							request.sessionId(),
							request.traceId(),
							null,
							responseJson));

					outboxService.appendMessage(
							"PAYMENT_ORDER",
							response.paymentOrderId().toString(),
							"PAYMENT_AUTHORIZED",
							response,
							OutboxMetadata.of(request.correlationId(), request.requestId(), request.actor()));
				});
	}

	private AuthorizeHoldResponse executeAuthorizeHoldBusiness(AuthorizeHoldRequest request) {
		HoldService.AuthorizationResult authorization = holdService.authorizeHold(
				new HoldService.AuthorizeHoldCommand(
						request.payerAccountId(),
						request.payeeAccountId(),
						request.amountMinor(),
						request.currency(),
						request.paymentType(),
						request.description()));

		return new AuthorizeHoldResponse(
				authorization.paymentOrderId(),
				authorization.holdId(),
				authorization.payerAccountId(),
				authorization.postedBalanceMinor(),
				authorization.availableBalanceBeforeMinor(),
				authorization.availableBalanceAfterMinor(),
				authorization.holdAmountMinor(),
				authorization.currency(),
				authorization.status());
	}

	public CaptureHoldResponse captureHold(CaptureHoldRequest request) {
		return moneyCommandTemplate.execute(
				"captureHold",
				request.idempotencyKey(),
				request,
				CaptureHoldResponse.class,
				IDEMPOTENCY_TTL,
				paymentRetryPolicy,
				() -> executeCaptureHoldBusiness(request),
				(response, responseJson) -> {
					auditService.appendEvent(new AuditService.AuditCommand(
							request.actor(),
							"PAYMENT_HOLD_CAPTURED",
							"HOLD",
							response.holdId().toString(),
							request.correlationId(),
							request.requestId(),
							request.sessionId(),
							request.traceId(),
							null,
							responseJson));

					outboxService.appendMessage(
							"HOLD",
							response.holdId().toString(),
							"PAYMENT_CAPTURED",
							response,
							OutboxMetadata.of(request.correlationId(), request.requestId(), request.actor()));
				});
	}

	private CaptureHoldResponse executeCaptureHoldBusiness(CaptureHoldRequest request) {
		HoldService.CaptureResult capture = holdService.captureHold(
				new HoldService.CaptureHoldCommand(
						request.holdId(),
						request.amountMinor(),
						request.debitLedgerAccountId(),
						request.creditLedgerAccountId(),
						request.beneficiaryCustomerAccountId(),
						request.actor(),
						request.correlationId()));

		return new CaptureHoldResponse(
				capture.paymentOrderId(),
				capture.holdId(),
				capture.journalId(),
				capture.capturedAmountMinor(),
				capture.remainingAmountMinor(),
				capture.holdStatus(),
				capture.paymentStatus(),
				capture.currency());
	}

	public VoidHoldResponse voidHold(VoidHoldRequest request) {
		return moneyCommandTemplate.execute(
				"voidHold",
				request.idempotencyKey(),
				request,
				VoidHoldResponse.class,
				IDEMPOTENCY_TTL,
				paymentRetryPolicy,
				() -> executeVoidHoldBusiness(request),
				(response, responseJson) -> {
					auditService.appendEvent(new AuditService.AuditCommand(
							request.actor(),
							"PAYMENT_HOLD_VOIDED",
							"HOLD",
							response.holdId().toString(),
							request.correlationId(),
							request.requestId(),
							request.sessionId(),
							request.traceId(),
							null,
							responseJson));

					outboxService.appendMessage(
							"HOLD",
							response.holdId().toString(),
							"PAYMENT_VOIDED",
							response,
							OutboxMetadata.of(request.correlationId(), request.requestId(), request.actor()));
				});
	}

	private VoidHoldResponse executeVoidHoldBusiness(VoidHoldRequest request) {
		HoldService.VoidResult voidResult = holdService.voidHold(new HoldService.VoidHoldCommand(request.holdId()));

		return new VoidHoldResponse(
				voidResult.paymentOrderId(),
				voidResult.holdId(),
				voidResult.restoredAmountMinor(),
				voidResult.availableBalanceBeforeMinor(),
				voidResult.availableBalanceAfterMinor(),
				voidResult.currency(),
				voidResult.status());
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
