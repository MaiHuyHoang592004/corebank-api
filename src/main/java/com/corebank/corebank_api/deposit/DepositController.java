package com.corebank.corebank_api.deposit;

import com.corebank.corebank_api.common.CoreBankException;
import com.corebank.corebank_api.ops.iam.IamAuthorizationService;
import java.util.Locale;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/deposits")
public class DepositController {

	private static final String[] MONEY_MOVEMENT_ROLES = {"ROLE_USER", "ROLE_OPS", "ROLE_ADMIN"};

	private final DepositApplicationService depositApplicationService;
	private final IamAuthorizationService iamAuthorizationService;

	public DepositController(
			DepositApplicationService depositApplicationService,
			IamAuthorizationService iamAuthorizationService) {
		this.depositApplicationService = depositApplicationService;
		this.iamAuthorizationService = iamAuthorizationService;
	}

	@PostMapping("/open")
	public ResponseEntity<DepositApplicationService.OpenDepositResponse> openDeposit(
			@RequestBody DepositApplicationService.OpenDepositRequest request,
			Authentication authentication) {
		iamAuthorizationService.requireAnyRole(authentication, MONEY_MOVEMENT_ROLES);
		try {
			DepositApplicationService.OpenDepositResponse response =
					depositApplicationService.openDeposit(request.withActor(actor(authentication)));
			return ResponseEntity.ok(response);
		} catch (CoreBankException ex) {
			throw toHttpException(ex);
		}
	}

	@PostMapping("/accrue")
	public ResponseEntity<DepositApplicationService.AccrueInterestResponse> accrueInterest(
			@RequestBody DepositApplicationService.AccrueInterestRequest request,
			Authentication authentication) {
		iamAuthorizationService.requireAnyRole(authentication, MONEY_MOVEMENT_ROLES);
		try {
			DepositApplicationService.AccrueInterestResponse response =
					depositApplicationService.accrueInterest(request.withActor(actor(authentication)));
			return ResponseEntity.ok(response);
		} catch (CoreBankException ex) {
			throw toHttpException(ex);
		}
	}

	@PostMapping("/maturity")
	public ResponseEntity<DepositApplicationService.MaturityResponse> processMaturity(
			@RequestBody DepositApplicationService.MaturityRequest request,
			Authentication authentication) {
		iamAuthorizationService.requireAnyRole(authentication, MONEY_MOVEMENT_ROLES);
		try {
			DepositApplicationService.MaturityResponse response =
					depositApplicationService.processMaturity(request.withActor(actor(authentication)));
			return ResponseEntity.ok(response);
		} catch (CoreBankException ex) {
			throw toHttpException(ex);
		}
	}

	private String actor(Authentication authentication) {
		return authentication == null ? "system" : authentication.getName();
	}

	private ResponseStatusException toHttpException(CoreBankException exception) {
		String message = exception.getMessage() == null ? "Deposit command failed" : exception.getMessage();
		String lowered = message.toLowerCase(Locale.ROOT);
		HttpStatus status = lowered.contains("not found")
				? HttpStatus.NOT_FOUND
				: lowered.contains("writes are not allowed")
						? HttpStatus.CONFLICT
						: HttpStatus.BAD_REQUEST;
		return new ResponseStatusException(status, message, exception);
	}
}
