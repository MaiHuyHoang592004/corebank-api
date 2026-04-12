package com.corebank.corebank_api.ops.maintenance;

import com.corebank.corebank_api.common.CoreBankException;
import com.corebank.corebank_api.ops.iam.IamAuthorizationService;
import com.corebank.corebank_api.ops.system.OpsRuntimeModePolicy;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/ops/maintenance")
public class OpsMaintenanceController {

	private final PartitionMaintenanceService partitionMaintenanceService;
	private final PartitionArchiveReadinessService partitionArchiveReadinessService;
	private final IdempotencyMaintenanceService idempotencyMaintenanceService;
	private final IamAuthorizationService iamAuthorizationService;
	private final OpsRuntimeModePolicy opsRuntimeModePolicy;

	public OpsMaintenanceController(
			PartitionMaintenanceService partitionMaintenanceService,
			PartitionArchiveReadinessService partitionArchiveReadinessService,
			IdempotencyMaintenanceService idempotencyMaintenanceService,
			IamAuthorizationService iamAuthorizationService,
			OpsRuntimeModePolicy opsRuntimeModePolicy) {
		this.partitionMaintenanceService = partitionMaintenanceService;
		this.partitionArchiveReadinessService = partitionArchiveReadinessService;
		this.idempotencyMaintenanceService = idempotencyMaintenanceService;
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

	@GetMapping("/partitions/archive-candidates")
	public ResponseEntity<PartitionArchiveReadinessService.PartitionArchiveCandidatePage> archiveCandidates(
			@RequestParam(required = false) Integer retentionMonths,
			@RequestParam(required = false) Integer limit,
			@RequestParam(required = false) List<String> parentTable,
			Authentication authentication) {
		iamAuthorizationService.requireAnyRole(authentication, "ROLE_OPS", "ROLE_ADMIN");

		try {
			PartitionArchiveReadinessService.PartitionArchiveCandidatePage result =
					partitionArchiveReadinessService.listArchiveCandidates(retentionMonths, limit, parentTable);
			return ResponseEntity.ok(result);
		} catch (CoreBankException ex) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage(), ex);
		}
	}

	@PostMapping("/idempotency/cleanup")
	public ResponseEntity<IdempotencyMaintenanceService.IdempotencyCleanupResult> cleanupExpiredIdempotencyKeys(
			@RequestBody(required = false) IdempotencyCleanupRequest request,
			Authentication authentication) {
		iamAuthorizationService.requireAnyRole(authentication, "ROLE_OPS", "ROLE_ADMIN");
		opsRuntimeModePolicy.requireNonRunningForMaintenanceJob();

		try {
			IdempotencyMaintenanceService.IdempotencyCleanupResult result =
					idempotencyMaintenanceService.cleanupExpired(
							request == null ? null : request.limit(),
							request == null ? null : request.dryRun(),
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

	public record IdempotencyCleanupRequest(
			Integer limit,
			Boolean dryRun) {
	}
}
