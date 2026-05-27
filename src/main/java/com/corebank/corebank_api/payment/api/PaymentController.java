package com.corebank.corebank_api.payment.api;

import com.corebank.corebank_api.common.CoreBankException;
import com.corebank.corebank_api.payment.PaymentApplicationService;
import com.corebank.corebank_api.payment.PaymentQueryService;
import java.time.Instant;
import java.util.Locale;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
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

	private final PaymentApplicationService paymentApplicationService;
	private final PaymentQueryService paymentQueryService;

	public PaymentController(
			PaymentApplicationService paymentApplicationService,
			PaymentQueryService paymentQueryService) {
		this.paymentApplicationService = paymentApplicationService;
		this.paymentQueryService = paymentQueryService;
	}

	@PostMapping("/authorize-hold")
	public ResponseEntity<PaymentApplicationService.AuthorizeHoldResponse> authorizeHold(
			@RequestBody PaymentApplicationService.AuthorizeHoldRequest request) {
		try {
			return ResponseEntity.ok(paymentApplicationService.authorizeHold(request));
		} catch (CoreBankException ex) {
			throw toHttpException(ex);
		}
	}

	@PostMapping("/capture-hold")
	public ResponseEntity<PaymentApplicationService.CaptureHoldResponse> captureHold(
			@RequestBody PaymentApplicationService.CaptureHoldRequest request) {
		try {
			return ResponseEntity.ok(paymentApplicationService.captureHold(request));
		} catch (CoreBankException ex) {
			throw toHttpException(ex);
		}
	}

	@PostMapping("/void-hold")
	public ResponseEntity<PaymentApplicationService.VoidHoldResponse> voidHold(
			@RequestBody PaymentApplicationService.VoidHoldRequest request) {
		try {
			return ResponseEntity.ok(paymentApplicationService.voidHold(request));
		} catch (CoreBankException ex) {
			throw toHttpException(ex);
		}
	}

	@PostMapping("/refund")
	public ResponseEntity<PaymentApplicationService.RefundResponse> refund(
			@RequestBody PaymentApplicationService.RefundRequest request) {
		try {
			return ResponseEntity.ok(paymentApplicationService.refund(request));
		} catch (CoreBankException ex) {
			throw toHttpException(ex);
		}
	}

	@GetMapping("/orders/{paymentOrderId}")
	public ResponseEntity<PaymentQueryService.PaymentOrderView> getPaymentOrder(
			@PathVariable UUID paymentOrderId) {
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
			@RequestParam(defaultValue = "20") int size) {
		try {
			return ResponseEntity.ok(paymentQueryService.listPaymentOrders(
					new PaymentQueryService.ListPaymentOrdersRequest(
							externalOrderRef, payerAccountId, payeeAccountId,
							status, createdFrom, createdTo, page, size)));
		} catch (CoreBankException ex) {
			throw toHttpException(ex);
		}
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
