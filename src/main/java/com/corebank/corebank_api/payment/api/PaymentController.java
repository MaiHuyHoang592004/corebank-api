package com.corebank.corebank_api.payment.api;

import com.corebank.corebank_api.common.CoreBankException;
import com.corebank.corebank_api.ops.iam.IamAuthorizationService;
import com.corebank.corebank_api.payment.PaymentApplicationService;
import com.corebank.corebank_api.payment.PaymentQueryService;
import java.time.Instant;
import java.util.Locale;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/payments")
public class PaymentController {

	private static final String[] MONEY_MOVEMENT_ROLES = {"ROLE_USER", "ROLE_OPS", "ROLE_ADMIN"};

	private final PaymentApplicationService paymentApplicationService;
	private final PaymentQueryService paymentQueryService;
	private final IamAuthorizationService iamAuthorizationService;

	public PaymentController(
			PaymentApplicationService paymentApplicationService,
			PaymentQueryService paymentQueryService,
			IamAuthorizationService iamAuthorizationService) {
		this.paymentApplicationService = paymentApplicationService;
		this.paymentQueryService = paymentQueryService;
		this.iamAuthorizationService = iamAuthorizationService;
	}

	@PostMapping("/authorize-hold")
	public ResponseEntity<PaymentApplicationService.AuthorizeHoldResponse> authorizeHold(
			@RequestBody PaymentApplicationService.AuthorizeHoldRequest request,
			Authentication authentication) {
		iamAuthorizationService.requireAnyRole(authentication, MONEY_MOVEMENT_ROLES);
		try {
			return ResponseEntity.ok(
					paymentApplicationService.authorizeHold(request.withActor(actor(authentication))));
		} catch (CoreBankException ex) {
			throw toHttpException(ex);
		}
	}

	@PostMapping("/capture-hold")
	public ResponseEntity<PaymentApplicationService.CaptureHoldResponse> captureHold(
			@RequestBody PaymentApplicationService.CaptureHoldRequest request,
			Authentication authentication) {
		iamAuthorizationService.requireAnyRole(authentication, MONEY_MOVEMENT_ROLES);
		try {
			return ResponseEntity.ok(
					paymentApplicationService.captureHold(request.withActor(actor(authentication))));
		} catch (CoreBankException ex) {
			throw toHttpException(ex);
		}
	}

	@PostMapping("/void-hold")
	public ResponseEntity<PaymentApplicationService.VoidHoldResponse> voidHold(
			@RequestBody PaymentApplicationService.VoidHoldRequest request,
			Authentication authentication) {
		iamAuthorizationService.requireAnyRole(authentication, MONEY_MOVEMENT_ROLES);
		try {
			return ResponseEntity.ok(
					paymentApplicationService.voidHold(request.withActor(actor(authentication))));
		} catch (CoreBankException ex) {
			throw toHttpException(ex);
		}
	}

	@PostMapping("/refund")
	public ResponseEntity<PaymentApplicationService.RefundResponse> refund(
			@RequestBody PaymentApplicationService.RefundRequest request,
			Authentication authentication) {
		iamAuthorizationService.requireAnyRole(authentication, MONEY_MOVEMENT_ROLES);
		try {
			return ResponseEntity.ok(
					paymentApplicationService.refund(request.withActor(actor(authentication))));
		} catch (CoreBankException ex) {
			throw toHttpException(ex);
		}
	}

	@GetMapping("/orders/{paymentOrderId}")
	public ResponseEntity<PaymentQueryService.PaymentOrderView> getPaymentOrder(
			@PathVariable UUID paymentOrderId,
			Authentication authentication) {
		iamAuthorizationService.requireAnyRole(authentication, MONEY_MOVEMENT_ROLES);
		try {
			return ResponseEntity.ok(paymentQueryService.getPaymentOrder(paymentOrderId));
		} catch (CoreBankException ex) {
			throw toHttpException(ex);
		}
	}

	@GetMapping("/orders")
	public ResponseEntity<PaymentQueryService.PaymentOrderListView> listPaymentOrders(
			@RequestParam(required = false) String externalOrderRef,
			@RequestParam(required = false) UUID payerAccountId,
			@RequestParam(required = false) UUID payeeAccountId,
			@RequestParam(required = false) String status,
			@RequestParam(required = false) Instant createdFrom,
			@RequestParam(required = false) Instant createdTo,
			@RequestParam(defaultValue = "0") int page,
			@RequestParam(defaultValue = "20") int size,
			Authentication authentication) {
		iamAuthorizationService.requireAnyRole(authentication, MONEY_MOVEMENT_ROLES);
		try {
			return ResponseEntity.ok(paymentQueryService.listPaymentOrders(
					new PaymentQueryService.ListPaymentOrdersRequest(
							externalOrderRef, payerAccountId, payeeAccountId,
							status, createdFrom, createdTo, page, size)));
		} catch (CoreBankException ex) {
			throw toHttpException(ex);
		}
	}

	/** Never trust a client-supplied actor for a money-moving/audited action — always the authenticated principal. */
	private String actor(Authentication authentication) {
		return authentication == null ? "system" : authentication.getName();
	}

	private ResponseStatusException toHttpException(CoreBankException exception) {
		String message = exception.getMessage() == null ? "Payment command failed" : exception.getMessage();
		String lowered = message.toLowerCase(Locale.ROOT);
		HttpStatus status = lowered.contains("not found")
				? HttpStatus.NOT_FOUND
				: lowered.contains("writes are not allowed") || lowered.contains("idempotency conflict")
						? HttpStatus.CONFLICT
						: HttpStatus.BAD_REQUEST;
		return new ResponseStatusException(status, message, exception);
	}
}
