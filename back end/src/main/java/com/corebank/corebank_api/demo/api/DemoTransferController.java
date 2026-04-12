package com.corebank.corebank_api.demo.api;

import com.corebank.corebank_api.common.CoreBankException;
import com.corebank.corebank_api.demo.application.DemoSetupService;
import com.corebank.corebank_api.ops.iam.IamAuthorizationService;
import com.corebank.corebank_api.transfer.TransferService;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import static org.springframework.http.HttpStatus.BAD_REQUEST;

/**
 * Demo-only internal transfer facade.
 *
 * <p>Accepts major-unit amounts from the frontend (e.g. "700000" as long).
 * Converts to minor units internally, auto-fills idempotency key, trace IDs,
 * and demo ledger account IDs. Delegates to the real TransferService.
 *
 * <p>All amounts are in VND. Only demo accounts are permitted.
 */
@RestController
@RequestMapping("/api/demo/transfers")
public class DemoTransferController {

	private static final String VND = "VND";

	private final TransferService transferService;
	private final DemoSetupService demoSetupService;
	private final IamAuthorizationService iamAuthorizationService;

	public DemoTransferController(
			TransferService transferService,
			DemoSetupService demoSetupService,
			IamAuthorizationService iamAuthorizationService) {
		this.transferService = transferService;
		this.demoSetupService = demoSetupService;
		this.iamAuthorizationService = iamAuthorizationService;
	}

	public record DemoTransferRequest(
			UUID sourceAccountId,
			UUID destinationAccountId,
			long amountMajor,
			String description) {
	}

	public record DemoTransferResponse(
			UUID journalId,
			UUID sourceAccountId,
			UUID destinationAccountId,
			long amountMinor,
			String currency,
			long sourcePostedBalanceMinor,
			long sourceAvailableBalanceAfterMinor,
			long destinationPostedBalanceMinor,
			long destinationAvailableBalanceAfterMinor,
			String status,
			String message) {
	}

	@PostMapping("/internal")
	public ResponseEntity<DemoTransferResponse> transfer(
			@RequestBody DemoTransferRequest request,
			Authentication authentication) {
		iamAuthorizationService.requireAuthenticated(authentication);
		String actor = authentication.getName();

		// Verify both accounts are demo accounts
		verifyDemoAccount(request.sourceAccountId());
		verifyDemoAccount(request.destinationAccountId());

		// Amount is in major units (VND, no minor unit)
		long amountMinor = request.amountMajor();

		// Auto-generate idempotency key and trace IDs
		String idempotencyKey = UUID.randomUUID().toString();
		UUID correlationId = UUID.randomUUID();
		UUID requestId = UUID.randomUUID();
		UUID sessionId = UUID.randomUUID();

		// Get demo ledger account IDs (same ledger account for debit and credit in this simplified model)
		var ledgerIds = demoSetupService.initializedLedgerAccountIds();
		UUID debitLedgerId = ledgerIds.get("transferDebitLedgerAccountId");
		UUID creditLedgerId = ledgerIds.get("transferCreditLedgerAccountId");

		var transferRequest = new TransferService.TransferRequest(
				idempotencyKey,
				request.sourceAccountId(),
				request.destinationAccountId(),
				amountMinor,
				VND,
				debitLedgerId,
				creditLedgerId,
				request.description() != null ? request.description() : "Demo transfer",
				actor,
				correlationId,
				requestId,
				sessionId,
				null);

		try {
			TransferService.TransferResponse result = transferService.transfer(transferRequest);
			return ResponseEntity.ok(new DemoTransferResponse(
					result.journalId(),
					result.sourceAccountId(),
					result.destinationAccountId(),
					result.amountMinor(),
					result.currency(),
					result.sourcePostedBalanceMinor(),
					result.sourceAvailableBalanceAfterMinor(),
					result.destinationPostedBalanceMinor(),
					result.destinationAvailableBalanceAfterMinor(),
					result.status(),
					"Transfer completed successfully"));
		} catch (CoreBankException ex) {
			throw new ResponseStatusException(BAD_REQUEST, ex.getMessage(), ex);
		}
	}

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
}
