package com.corebank.corebank_api.ops.reconciliation;

import com.corebank.corebank_api.ops.iam.IamAuthorizationService;
import com.corebank.corebank_api.ops.system.SystemModeService;
import java.time.LocalDate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/ops/reconciliation")
public class OpsReconciliationController {

	private final ReconciliationService reconciliationService;
	private final IamAuthorizationService iamAuthorizationService;
	private final SystemModeService systemModeService;

	public OpsReconciliationController(
			ReconciliationService reconciliationService,
			IamAuthorizationService iamAuthorizationService,
			SystemModeService systemModeService) {
		this.reconciliationService = reconciliationService;
		this.iamAuthorizationService = iamAuthorizationService;
		this.systemModeService = systemModeService;
	}

	@PostMapping("/runs")
	public ResponseEntity<ReconciliationService.ReconciliationRunResult> runReconciliation(
			@RequestBody(required = false) ReconciliationRunRequest request,
			Authentication authentication) {
		iamAuthorizationService.requireAnyRole(authentication, "ROLE_OPS", "ROLE_ADMIN");
		if (systemModeService.getCurrentMode() == SystemModeService.SystemMode.RUNNING) {
			throw new ResponseStatusException(
					HttpStatus.CONFLICT,
					"Reconciliation run is allowed only when system mode is not RUNNING");
		}

		LocalDate businessDate = parseBusinessDate(request == null ? null : request.businessDate());
		Integer limit = request == null ? null : request.limit();
		ReconciliationService.ReconciliationRunResult result =
				reconciliationService.run(businessDate, limit, actor(authentication));
		return ResponseEntity.ok(result);
	}

	private LocalDate parseBusinessDate(String value) {
		if (value == null || value.trim().isEmpty()) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "businessDate is required");
		}
		try {
			return LocalDate.parse(value.trim());
		} catch (RuntimeException ex) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "businessDate must be yyyy-MM-dd");
		}
	}

	private String actor(Authentication authentication) {
		return authentication == null ? "system" : authentication.getName();
	}

	public record ReconciliationRunRequest(
			String businessDate,
			Integer limit) {
	}
}
