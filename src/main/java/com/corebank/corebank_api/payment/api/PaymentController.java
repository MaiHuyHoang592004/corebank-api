package com.corebank.corebank_api.payment.api;

import com.corebank.corebank_api.common.CoreBankException;
import com.corebank.corebank_api.payment.PaymentApplicationService;
import java.util.Locale;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/payments")
public class PaymentController {

	private final PaymentApplicationService paymentApplicationService;

	public PaymentController(PaymentApplicationService paymentApplicationService) {
		this.paymentApplicationService = paymentApplicationService;
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

	private ResponseStatusException toHttpException(CoreBankException exception) {
		String message = exception.getMessage() == null ? "Payment command failed" : exception.getMessage();
		String lowered = message.toLowerCase(Locale.ROOT);
		HttpStatus status = lowered.contains("not found")
				? HttpStatus.NOT_FOUND
				: lowered.contains("writes are not allowed")
						? HttpStatus.CONFLICT
						: HttpStatus.BAD_REQUEST;
		return new ResponseStatusException(status, message, exception);
	}
}

