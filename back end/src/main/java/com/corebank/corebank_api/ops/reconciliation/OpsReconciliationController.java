package com.corebank.corebank_api.ops.reconciliation;

import com.corebank.corebank_api.ops.iam.IamAuthorizationService;
import com.corebank.corebank_api.ops.system.OpsRuntimeModePolicy;
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
	private final ExternalReconciliationService externalReconciliationService;
	private final IamAuthorizationService iamAuthorizationService;
	private final OpsRuntimeModePolicy opsRuntimeModePolicy;

	public OpsReconciliationController(
			ReconciliationService reconciliationService,
			ExternalReconciliationService externalReconciliationService,
			IamAuthorizationService iamAuthorizationService,
			OpsRuntimeModePolicy opsRuntimeModePolicy) {
		this.reconciliationService = reconciliationService;
		this.externalReconciliationService = externalReconciliationService;
		this.iamAuthorizationService = iamAuthorizationService;
		this.opsRuntimeModePolicy = opsRuntimeModePolicy;
	}

	@PostMapping("/runs")
	public ResponseEntity<ReconciliationService.ReconciliationRunResult> runReconciliation(
			@RequestBody(required = false) ReconciliationRunRequest request,
			Authentication authentication) {
		iamAuthorizationService.requireAnyRole(authentication, "ROLE_OPS", "ROLE_ADMIN");
		opsRuntimeModePolicy.requireNonRunningForMaintenanceJob();

		LocalDate businessDate = parseBusinessDate(request == null ? null : request.businessDate());
		Integer limit = request == null ? null : request.limit();
		ReconciliationService.ReconciliationRunResult result =
				reconciliationService.run(businessDate, limit, actor(authentication));
		return ResponseEntity.ok(result);
	}

	@PostMapping("/external/runs")
	public ResponseEntity<ExternalReconciliationService.ExternalReconciliationRunResult> runExternalReconciliation(
			@RequestBody(required = false) ExternalReconciliationRunRequest request,
			Authentication authentication) {
		iamAuthorizationService.requireAnyRole(authentication, "ROLE_OPS", "ROLE_ADMIN");
		opsRuntimeModePolicy.requireNonRunningForMaintenanceJob();

		if (request == null) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "request body is required");
		}

		LocalDate statementDate = parseStatementDate(request.statementDate());
		ExternalReconciliationService.ExternalReconciliationRunResult result =
				externalReconciliationService.run(new ExternalReconciliationService.ExternalReconciliationRunCommand(
						request.statementRef(),
						request.provider(),
						statementDate,
						request.processingLimit(),
						request.dryRun(),
						request.entries(),
						actor(authentication)));
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

	private LocalDate parseStatementDate(String value) {
		if (value == null || value.trim().isEmpty()) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "statementDate is required");
		}
		try {
			return LocalDate.parse(value.trim());
		} catch (RuntimeException ex) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "statementDate must be yyyy-MM-dd");
		}
	}

	private String actor(Authentication authentication) {
		return authentication == null ? "system" : authentication.getName();
	}

	public record ReconciliationRunRequest(
			String businessDate,
			Integer limit) {
	}

	public record ExternalReconciliationRunRequest(
			String statementRef,
			String provider,
			String statementDate,
			Integer processingLimit,
			Boolean dryRun,
			java.util.List<ExternalReconciliationService.ExternalSettlementEntryInput> entries) {
	}
}
