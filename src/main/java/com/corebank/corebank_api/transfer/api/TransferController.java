package com.corebank.corebank_api.transfer.api;

import com.corebank.corebank_api.common.CoreBankException;
import com.corebank.corebank_api.ops.iam.IamAuthorizationService;
import com.corebank.corebank_api.transfer.TransferService;
import java.util.Locale;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/transfers")
public class TransferController {

	private static final String[] MONEY_MOVEMENT_ROLES = {"ROLE_USER", "ROLE_OPS", "ROLE_ADMIN"};

	private final TransferService transferService;
	private final IamAuthorizationService iamAuthorizationService;

	public TransferController(TransferService transferService, IamAuthorizationService iamAuthorizationService) {
		this.transferService = transferService;
		this.iamAuthorizationService = iamAuthorizationService;
	}

	@PostMapping("/internal")
	public ResponseEntity<TransferService.TransferResponse> transfer(
			@RequestBody TransferService.TransferRequest request,
			Authentication authentication) {
		iamAuthorizationService.requireAnyRole(authentication, MONEY_MOVEMENT_ROLES);
		try {
			String actor = authentication == null ? "system" : authentication.getName();
			return ResponseEntity.ok(transferService.transfer(request.withActor(actor)));
		} catch (CoreBankException ex) {
			throw toHttpException(ex);
		}
	}

	private ResponseStatusException toHttpException(CoreBankException exception) {
		String message = exception.getMessage() == null ? "Transfer command failed" : exception.getMessage();
		String lowered = message.toLowerCase(Locale.ROOT);
		HttpStatus status = lowered.contains("not found")
				? HttpStatus.NOT_FOUND
				: lowered.contains("writes are not allowed")
						? HttpStatus.CONFLICT
						: HttpStatus.BAD_REQUEST;
		return new ResponseStatusException(status, message, exception);
	}
}

