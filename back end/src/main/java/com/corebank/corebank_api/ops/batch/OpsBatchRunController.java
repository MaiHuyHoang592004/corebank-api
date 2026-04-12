package com.corebank.corebank_api.ops.batch;

import com.corebank.corebank_api.common.CoreBankException;
import com.corebank.corebank_api.ops.iam.IamAuthorizationService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/ops/batch-runs")
public class OpsBatchRunController {

	private final BatchRunReportingService batchRunReportingService;
	private final IamAuthorizationService iamAuthorizationService;

	public OpsBatchRunController(
			BatchRunReportingService batchRunReportingService,
			IamAuthorizationService iamAuthorizationService) {
		this.batchRunReportingService = batchRunReportingService;
		this.iamAuthorizationService = iamAuthorizationService;
	}

	@GetMapping
	public ResponseEntity<BatchRunReportingService.BatchRunPage> listBatchRuns(
			@RequestParam(required = false) String batchType,
			@RequestParam(required = false) String status,
			@RequestParam(required = false) Integer limit,
			Authentication authentication) {
		iamAuthorizationService.requireAnyRole(authentication, "ROLE_OPS", "ROLE_ADMIN");
		try {
			return ResponseEntity.ok(batchRunReportingService.listBatchRuns(batchType, status, limit));
		} catch (CoreBankException ex) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage(), ex);
		}
	}

	@GetMapping("/{runId}")
	public ResponseEntity<BatchRunReportingService.BatchRunView> getBatchRun(
			@PathVariable long runId,
			Authentication authentication) {
		iamAuthorizationService.requireAnyRole(authentication, "ROLE_OPS", "ROLE_ADMIN");
		try {
			return batchRunReportingService.findBatchRun(runId)
					.map(ResponseEntity::ok)
					.orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Batch run not found"));
		} catch (CoreBankException ex) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage(), ex);
		}
	}
}
