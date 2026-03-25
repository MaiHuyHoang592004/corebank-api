package com.corebank.corebank_api.ops.hotaccount;

import com.corebank.corebank_api.common.CoreBankException;
import com.corebank.corebank_api.ops.iam.IamAuthorizationService;
import java.util.Locale;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/ops/hot-accounts")
public class OpsHotAccountController {

	private final HotAccountOpsService hotAccountOpsService;
	private final IamAuthorizationService iamAuthorizationService;

	public OpsHotAccountController(
			HotAccountOpsService hotAccountOpsService,
			IamAuthorizationService iamAuthorizationService) {
		this.hotAccountOpsService = hotAccountOpsService;
		this.iamAuthorizationService = iamAuthorizationService;
	}

	@PutMapping("/{ledgerAccountId}/profile")
	public ResponseEntity<HotAccountOpsService.HotAccountProfileView> upsertProfile(
			@PathVariable UUID ledgerAccountId,
			@RequestBody(required = false) UpsertHotAccountProfileRequest request,
			Authentication authentication) {
		iamAuthorizationService.requireAnyRole(authentication, "ROLE_OPS", "ROLE_ADMIN");
		try {
			HotAccountOpsService.HotAccountProfileView view = hotAccountOpsService.upsertProfile(
					new HotAccountOpsService.UpsertHotAccountProfileCommand(
							ledgerAccountId,
							request == null ? null : request.slotCount(),
							request == null ? null : request.selectionStrategy(),
							request == null ? null : request.isActive()),
					actor(authentication));
			return ResponseEntity.ok(view);
		} catch (CoreBankException ex) {
			throw toHttpException(ex);
		}
	}

	@GetMapping("/{ledgerAccountId}")
	public ResponseEntity<HotAccountOpsService.HotAccountProfileView> getHotAccount(
			@PathVariable UUID ledgerAccountId,
			Authentication authentication) {
		iamAuthorizationService.requireAnyRole(authentication, "ROLE_OPS", "ROLE_ADMIN");
		try {
			return ResponseEntity.ok(hotAccountOpsService.getHotAccount(ledgerAccountId));
		} catch (CoreBankException ex) {
			throw toHttpException(ex);
		}
	}

	private String actor(Authentication authentication) {
		return authentication == null ? "system" : authentication.getName();
	}

	private ResponseStatusException toHttpException(CoreBankException exception) {
		String message = exception.getMessage() == null
				? "Hot-account operation failed"
				: exception.getMessage();
		String lowered = message.toLowerCase(Locale.ROOT);

		HttpStatus status;
		if (lowered.contains("not found")) {
			status = HttpStatus.NOT_FOUND;
		} else if (lowered.contains("cannot reduce slotcount")) {
			status = HttpStatus.CONFLICT;
		} else {
			status = HttpStatus.BAD_REQUEST;
		}

		return new ResponseStatusException(status, message, exception);
	}

	public record UpsertHotAccountProfileRequest(
			Integer slotCount,
			String selectionStrategy,
			Boolean isActive) {
	}
}
