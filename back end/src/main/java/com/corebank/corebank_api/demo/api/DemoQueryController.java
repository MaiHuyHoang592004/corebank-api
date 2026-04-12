package com.corebank.corebank_api.demo.api;

import com.corebank.corebank_api.common.CoreBankException;
import com.corebank.corebank_api.demo.application.DemoSetupService;
import com.corebank.corebank_api.ops.iam.IamAuthorizationService;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import static org.springframework.http.HttpStatus.BAD_REQUEST;

/**
 * Read-only demo facade for the frontend.
 * All endpoints require authentication (any valid demo user).
 * Exposes only demo-scoped data — no raw internal APIs.
 */
@RestController
@RequestMapping("/api/demo")
public class DemoQueryController {

	private final DemoSetupService demoSetupService;
	private final IamAuthorizationService iamAuthorizationService;

	public DemoQueryController(
			DemoSetupService demoSetupService,
			IamAuthorizationService iamAuthorizationService) {
		this.demoSetupService = demoSetupService;
		this.iamAuthorizationService = iamAuthorizationService;
	}

	@GetMapping("/accounts")
	public ResponseEntity<DemoSetupService.DemoAccountsResponse> listAccounts(Authentication authentication) {
		iamAuthorizationService.requireAuthenticated(authentication);
		return ResponseEntity.ok(demoSetupService.listDemoAccounts());
	}

	@GetMapping("/accounts/{accountId}")
	public ResponseEntity<DemoSetupService.DemoAccountDto> getAccount(
			@PathVariable UUID accountId,
			Authentication authentication) {
		iamAuthorizationService.requireAuthenticated(authentication);
		try {
			return ResponseEntity.ok(demoSetupService.getDemoAccount(accountId));
		} catch (CoreBankException ex) {
			throw new ResponseStatusException(BAD_REQUEST, ex.getMessage(), ex);
		}
	}

	@GetMapping("/accounts/{accountId}/activity")
	public ResponseEntity<DemoSetupService.DemoActivityPage> getAccountActivity(
			@PathVariable UUID accountId,
			@RequestParam(defaultValue = "0") int page,
			@RequestParam(defaultValue = "20") int size,
			Authentication authentication) {
		iamAuthorizationService.requireAuthenticated(authentication);
		return ResponseEntity.ok(demoSetupService.getAccountActivity(accountId, page, size));
	}

	@GetMapping("/accounts/lookup")
	public ResponseEntity<List<DemoSetupService.DemoLookupItem>> lookupAccounts(
			@RequestParam String query,
			Authentication authentication) {
		iamAuthorizationService.requireAuthenticated(authentication);
		return ResponseEntity.ok(demoSetupService.lookupAccounts(query));
	}
}
