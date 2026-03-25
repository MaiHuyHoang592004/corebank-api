package com.corebank.corebank_api.ops.maintenance;

import com.corebank.corebank_api.common.CoreBankException;
import com.corebank.corebank_api.ops.iam.IamAuthorizationService;
import com.corebank.corebank_api.ops.system.OpsRuntimeModePolicy;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/ops/maintenance")
public class OpsMaintenanceController {

	private final PartitionMaintenanceService partitionMaintenanceService;
	private final IamAuthorizationService iamAuthorizationService;
	private final OpsRuntimeModePolicy opsRuntimeModePolicy;

	public OpsMaintenanceController(
			PartitionMaintenanceService partitionMaintenanceService,
			IamAuthorizationService iamAuthorizationService,
			OpsRuntimeModePolicy opsRuntimeModePolicy) {
		this.partitionMaintenanceService = partitionMaintenanceService;
		this.iamAuthorizationService = iamAuthorizationService;
		this.opsRuntimeModePolicy = opsRuntimeModePolicy;
	}

	@PostMapping("/partitions/ensure-future")
	public ResponseEntity<PartitionMaintenanceService.PartitionMaintenanceResult> ensureFuturePartitions(
			@RequestBody(required = false) EnsureFuturePartitionsRequest request,
			Authentication authentication) {
		iamAuthorizationService.requireAnyRole(authentication, "ROLE_OPS", "ROLE_ADMIN");
		opsRuntimeModePolicy.requireNonRunningForMaintenanceJob();

		try {
			PartitionMaintenanceService.PartitionMaintenanceResult result = partitionMaintenanceService.ensureFuturePartitions(
					request == null ? null : request.fromMonth(),
					request == null ? null : request.monthsAhead(),
					actor(authentication));
			return ResponseEntity.ok(result);
		} catch (CoreBankException ex) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage(), ex);
		}
	}

	private String actor(Authentication authentication) {
		return authentication == null ? "system" : authentication.getName();
	}

	public record EnsureFuturePartitionsRequest(
			String fromMonth,
			Integer monthsAhead) {
	}
}
