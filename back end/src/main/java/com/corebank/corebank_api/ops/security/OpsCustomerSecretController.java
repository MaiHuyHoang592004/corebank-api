package com.corebank.corebank_api.ops.security;

import com.corebank.corebank_api.ops.iam.IamAuthorizationService;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/ops/customers")
public class OpsCustomerSecretController {

	private final CustomerSecretService customerSecretService;
	private final IamAuthorizationService iamAuthorizationService;

	public OpsCustomerSecretController(
			CustomerSecretService customerSecretService,
			IamAuthorizationService iamAuthorizationService) {
		this.customerSecretService = customerSecretService;
		this.iamAuthorizationService = iamAuthorizationService;
	}

	@PutMapping("/{customerId}/secrets/{secretType}")
	public ResponseEntity<CustomerSecretService.SecretMetadata> upsertSecret(
			@PathVariable UUID customerId,
			@PathVariable String secretType,
			@RequestBody(required = false) UpsertCustomerSecretRequest request,
			Authentication authentication) {
		iamAuthorizationService.requireAnyRole(authentication, "ROLE_OPS", "ROLE_ADMIN");
		CustomerSecretService.SecretMetadata metadata = customerSecretService.upsertSecret(
				customerId,
				secretType,
				request == null ? null : request.plainText(),
				actor(authentication));
		return ResponseEntity.ok(metadata);
	}

	@GetMapping("/{customerId}/secrets")
	public ResponseEntity<CustomerSecretService.SecretMetadataPage> listSecrets(
			@PathVariable UUID customerId,
			Authentication authentication) {
		iamAuthorizationService.requireAnyRole(authentication, "ROLE_OPS", "ROLE_ADMIN");
		return ResponseEntity.ok(customerSecretService.listSecretMetadata(customerId));
	}

	private String actor(Authentication authentication) {
		return authentication == null ? "system" : authentication.getName();
	}

	public record UpsertCustomerSecretRequest(
			String plainText) {
	}
}
