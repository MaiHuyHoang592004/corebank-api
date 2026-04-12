package com.corebank.corebank_api.demo.api;

import com.corebank.corebank_api.common.CoreBankException;
import com.corebank.corebank_api.demo.application.DemoSetupService;
import com.corebank.corebank_api.ops.iam.IamAuthorizationService;
import com.corebank.corebank_api.payment.PaymentApplicationService;
import java.util.UUID;
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
import static org.springframework.http.HttpStatus.BAD_REQUEST;

/**
 * Demo-only payment hold facade.
 *
 * <p>Accepts major-unit amounts from the frontend (e.g. "500000" as long).
 * Converts to minor units internally, auto-fills idempotency key and trace IDs,
 * restricts to demo accounts, and delegates to the real PaymentApplicationService.
 *
 * <p>All amounts are in VND. Only demo accounts are permitted.
 */
@RestController
@RequestMapping("/api/demo/payments")
public class DemoPaymentController {

	private static final String VND = "VND";

	private final DemoSetupService demoSetupService;
	private final DemoPaymentFacadeService demoPaymentFacadeService;
	private final PaymentApplicationService paymentApplicationService;
	private final IamAuthorizationService iamAuthorizationService;

	public DemoPaymentController(
			DemoSetupService demoSetupService,
			DemoPaymentFacadeService demoPaymentFacadeService,
			PaymentApplicationService paymentApplicationService,
			IamAuthorizationService iamAuthorizationService) {
		this.demoSetupService = demoSetupService;
		this.demoPaymentFacadeService = demoPaymentFacadeService;
		this.paymentApplicationService = paymentApplicationService;
		this.iamAuthorizationService = iamAuthorizationService;
	}

	// -------------------------------------------------------------------------
	// Request / Response records
	// -------------------------------------------------------------------------

	public record DemoAuthorizeRequest(
			UUID payerAccountId,
			UUID payeeAccountId,
			long amountMajor,
			String paymentType,
			String description) {
	}

	public record DemoCaptureRequest(
			UUID holdId,
			long amountMajor,
			String description) {
	}

	public record DemoVoidRequest(
			UUID holdId,
			String description) {
	}

	// -------------------------------------------------------------------------
	// Query endpoints
	// -------------------------------------------------------------------------

	@GetMapping("/accounts/{accountId}/holds")
	public ResponseEntity<DemoPaymentFacadeService.DemoHoldPage> listHolds(
			@PathVariable UUID accountId,
			@RequestParam(defaultValue = "0") int page,
			@RequestParam(defaultValue = "20") int size,
			Authentication authentication) {
		iamAuthorizationService.requireAuthenticated(authentication);
		verifyDemoAccount(accountId);
		return ResponseEntity.ok(demoPaymentFacadeService.listActiveHolds(accountId, page, size));
	}

	@GetMapping("/holds/{holdId}")
	public ResponseEntity<DemoPaymentFacadeService.DemoHoldDto> getHold(
			@PathVariable UUID holdId,
			Authentication authentication) {
		iamAuthorizationService.requireAuthenticated(authentication);
		DemoPaymentFacadeService.DemoHoldDto hold = demoPaymentFacadeService.getHoldById(holdId);
		verifyDemoAccount(hold.payerAccountId());
		return ResponseEntity.ok(hold);
	}

	// -------------------------------------------------------------------------
	// Command endpoints
	// -------------------------------------------------------------------------

	@PostMapping("/authorize")
	public ResponseEntity<DemoAuthorizeResponse> authorizeHold(
			@RequestBody DemoAuthorizeRequest request,
			Authentication authentication) {
		iamAuthorizationService.requireAuthenticated(authentication);
		String actor = authentication.getName();

		verifyDemoAccount(request.payerAccountId());
		verifyDemoAccount(request.payeeAccountId());

		long amountMinor = request.amountMajor();

		String idempotencyKey = UUID.randomUUID().toString();
		UUID correlationId = UUID.randomUUID();
		UUID requestId = UUID.randomUUID();
		UUID sessionId = UUID.randomUUID();

		var rawRequest = new PaymentApplicationService.AuthorizeHoldRequest(
				idempotencyKey,
				request.payerAccountId(),
				request.payeeAccountId(),
				amountMinor,
				VND,
				request.paymentType() != null ? request.paymentType() : "INTERNAL",
				request.description() != null ? request.description() : "Demo hold",
				actor,
				correlationId,
				requestId,
				sessionId,
				null);

		try {
			PaymentApplicationService.AuthorizeHoldResponse result =
					paymentApplicationService.authorizeHold(rawRequest);
			return ResponseEntity.ok(new DemoAuthorizeResponse(
					result.paymentOrderId(),
					result.holdId(),
					result.payerAccountId(),
					result.postedBalanceMinor(),
					result.availableBalanceBeforeMinor(),
					result.availableBalanceAfterMinor(),
					result.holdAmountMinor(),
					result.currency(),
					result.status()));
		} catch (CoreBankException ex) {
			throw new ResponseStatusException(BAD_REQUEST, ex.getMessage(), ex);
		}
	}

	@PostMapping("/capture")
	public ResponseEntity<DemoCaptureResponse> captureHold(
			@RequestBody DemoCaptureRequest request,
			Authentication authentication) {
		iamAuthorizationService.requireAuthenticated(authentication);
		String actor = authentication.getName();

		var hold = demoPaymentFacadeService.getHoldById(request.holdId());
		verifyDemoAccount(hold.payerAccountId());

		long amountMinor = request.amountMajor();
		String idempotencyKey = UUID.randomUUID().toString();
		UUID correlationId = UUID.randomUUID();
		UUID requestId = UUID.randomUUID();
		UUID sessionId = UUID.randomUUID();

		var ledgerIds = demoSetupService.initializedLedgerAccountIds();
		UUID debitLedgerId = ledgerIds.get("paymentCaptureDebitLedgerAccountId");
		UUID creditLedgerId = ledgerIds.get("paymentCaptureCreditLedgerAccountId");

		var rawRequest = new PaymentApplicationService.CaptureHoldRequest(
				idempotencyKey,
				request.holdId(),
				amountMinor,
				debitLedgerId,
				creditLedgerId,
				hold.payeeAccountId(),
				actor,
				correlationId,
				requestId,
				sessionId,
				null);

		try {
			PaymentApplicationService.CaptureHoldResponse result =
					paymentApplicationService.captureHold(rawRequest);
			return ResponseEntity.ok(new DemoCaptureResponse(
					result.paymentOrderId(),
					result.holdId(),
					result.journalId(),
					result.capturedAmountMinor(),
					result.remainingAmountMinor(),
					result.holdStatus(),
					result.paymentStatus(),
					result.currency()));
		} catch (CoreBankException ex) {
			throw new ResponseStatusException(BAD_REQUEST, ex.getMessage(), ex);
		}
	}

	@PostMapping("/void")
	public ResponseEntity<DemoVoidResponse> voidHold(
			@RequestBody DemoVoidRequest request,
			Authentication authentication) {
		iamAuthorizationService.requireAuthenticated(authentication);
		String actor = authentication.getName();

		var hold = demoPaymentFacadeService.getHoldById(request.holdId());
		verifyDemoAccount(hold.payerAccountId());

		String idempotencyKey = UUID.randomUUID().toString();
		UUID correlationId = UUID.randomUUID();
		UUID requestId = UUID.randomUUID();
		UUID sessionId = UUID.randomUUID();

		var rawRequest = new PaymentApplicationService.VoidHoldRequest(
				idempotencyKey,
				request.holdId(),
				actor,
				correlationId,
				requestId,
				sessionId,
				null);

		try {
			PaymentApplicationService.VoidHoldResponse result =
					paymentApplicationService.voidHold(rawRequest);
			return ResponseEntity.ok(new DemoVoidResponse(
					result.paymentOrderId(),
					result.holdId(),
					result.restoredAmountMinor(),
					result.availableBalanceBeforeMinor(),
					result.availableBalanceAfterMinor(),
					result.currency(),
					result.status()));
		} catch (CoreBankException ex) {
			throw new ResponseStatusException(BAD_REQUEST, ex.getMessage(), ex);
		}
	}

	// -------------------------------------------------------------------------
	// Internal helpers
	// -------------------------------------------------------------------------

	private void verifyDemoAccount(UUID accountId) {
		var accounts = demoSetupService.listDemoAccounts().accounts();
		boolean isDemoAccount = accounts.stream()
				.anyMatch(a -> a.accountId().equals(accountId));
		if (!isDemoAccount) {
			throw new ResponseStatusException(
					BAD_REQUEST,
					"Account is not a demo account: " + accountId);
		}
	}

	// -------------------------------------------------------------------------
	// Response records
	// -------------------------------------------------------------------------

	public record DemoAuthorizeResponse(
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

	public record DemoCaptureResponse(
			UUID paymentOrderId,
			UUID holdId,
			UUID journalId,
			long capturedAmountMinor,
			long remainingAmountMinor,
			String holdStatus,
			String paymentStatus,
			String currency) {
	}

	public record DemoVoidResponse(
			UUID paymentOrderId,
			UUID holdId,
			long restoredAmountMinor,
			long availableBalanceBeforeMinor,
			long availableBalanceAfterMinor,
			String currency,
			String status) {
	}
}
