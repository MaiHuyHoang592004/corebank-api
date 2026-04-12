package com.corebank.corebank_api.demo.api;

import com.corebank.corebank_api.common.CoreBankException;
import com.corebank.corebank_api.demo.application.DemoSetupService;
import com.corebank.corebank_api.ops.iam.IamAuthorizationService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/demo")
public class DemoSetupController {

	private final DemoSetupService demoSetupService;
	private final IamAuthorizationService iamAuthorizationService;

	public DemoSetupController(
			DemoSetupService demoSetupService,
			IamAuthorizationService iamAuthorizationService) {
		this.demoSetupService = demoSetupService;
		this.iamAuthorizationService = iamAuthorizationService;
	}

	@PostMapping("/setup")
	public ResponseEntity<DemoSetupService.DemoSetupResponse> setup(Authentication authentication) {
		iamAuthorizationService.requireAnyRole(authentication, "ROLE_OPS", "ROLE_ADMIN");
		try {
			return ResponseEntity.ok(demoSetupService.initialize());
		} catch (CoreBankException ex) {
			throw new ResponseStatusException(org.springframework.http.HttpStatus.BAD_REQUEST, ex.getMessage(), ex);
		}
	}
}

