package com.corebank.corebank_api.ops.approval;

import com.corebank.corebank_api.integration.OutboxMetadata;
import com.corebank.corebank_api.integration.OutboxService;
import com.corebank.corebank_api.lending.LoanContractService;
import com.corebank.corebank_api.ops.audit.AuditService;
import com.corebank.corebank_api.ops.iam.IamAuthorizationService;
import com.corebank.corebank_api.ops.system.OpsRuntimeModePolicy;
import com.corebank.corebank_api.reporting.OutboxReportingService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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
@RequestMapping("/api/ops/executions")
public class OpsExecutionController {

	private final ApprovalService approvalService;
	private final IamAuthorizationService iamAuthorizationService;
	private final OutboxReportingService outboxReportingService;
	private final LoanContractService loanContractService;
	private final AuditService auditService;
	private final OutboxService outboxService;
	private final ObjectMapper objectMapper;
	private final OpsRuntimeModePolicy opsRuntimeModePolicy;

	public OpsExecutionController(
			ApprovalService approvalService,
			IamAuthorizationService iamAuthorizationService,
			OutboxReportingService outboxReportingService,
			LoanContractService loanContractService,
			AuditService auditService,
			OutboxService outboxService,
			ObjectMapper objectMapper,
			OpsRuntimeModePolicy opsRuntimeModePolicy) {
		this.approvalService = approvalService;
		this.iamAuthorizationService = iamAuthorizationService;
		this.outboxReportingService = outboxReportingService;
		this.loanContractService = loanContractService;
		this.auditService = auditService;
		this.outboxService = outboxService;
		this.objectMapper = objectMapper.copy().findAndRegisterModules();
		this.opsRuntimeModePolicy = opsRuntimeModePolicy;
	}

	@PostMapping("/outbox-dead-letter-requeue-bulk")
	public ResponseEntity<OutboxBulkExecutionResponse> executeOutboxDeadLetterBulkRequeue(
			@RequestBody(required = false) ApprovalExecutionRequest request,
			Authentication authentication) {
		iamAuthorizationService.requirePermission(authentication, "APPROVAL_EXECUTE");
		opsRuntimeModePolicy.requireRunningForMoneyImpactWrite();
		UUID approvalId = requiredApprovalId(request);
		String actor = actor(authentication);

		ApprovalService.ApprovalView approval = approvalService.claimApprovedExecution(
				approvalId,
				actor,
				"OUTBOX_BULK_REQUEUE");

		List<Long> outboxEventIds = extractOutboxEventIds(approval.operationPayload());
		OutboxReportingService.OutboxDeadLetterBulkRequeueResponse result =
				outboxReportingService.requeueDeadLetters(outboxEventIds, actor);

		appendExecutionAudit(
				approval,
				actor,
				"APPROVAL_EXECUTED_OUTBOX_BULK_REQUEUE",
				approvalCorrelationId(approval.approvalId()),
				Map.of(
						"requestedCount", result.requestedCount(),
						"requeuedCount", result.requeuedCount(),
						"notFoundCount", result.notFoundCount(),
						"conflictCount", result.conflictCount()));

		return ResponseEntity.ok(new OutboxBulkExecutionResponse(
				approval.approvalId(),
				approval.operationType(),
				result));
	}

	@PostMapping("/loan-default")
	public ResponseEntity<LoanDefaultExecutionResponse> executeLoanDefault(
			@RequestBody(required = false) ApprovalExecutionRequest request,
			Authentication authentication) {
		iamAuthorizationService.requirePermission(authentication, "APPROVAL_EXECUTE");
		opsRuntimeModePolicy.requireRunningForMoneyImpactWrite();
		UUID approvalId = requiredApprovalId(request);
		String actor = actor(authentication);

		ApprovalService.ApprovalView approval = approvalService.claimApprovedExecution(
				approvalId,
				actor,
				"LOAN_DEFAULT");

		LoanDefaultPayload payload = extractLoanDefaultPayload(approval.operationPayload());
		LoanContractService.DefaultTransitionResult result = loanContractService.markContractDefaulted(
				payload.contractId(),
				payload.asOfDate());

		UUID correlationId = approvalCorrelationId(approval.approvalId());
		UUID requestId = UUID.nameUUIDFromBytes(("approval-execution:" + approval.approvalId())
				.getBytes(StandardCharsets.UTF_8));

		if (result.transitioned()) {
			Map<String, Object> defaultedPayload = Map.of(
					"asOfDate", payload.asOfDate(),
					"contractId", result.contractId(),
					"outstandingPrincipalMinor", result.outstandingPrincipalMinor(),
					"overdueInstallmentCount", result.overdueInstallmentCount(),
					"status", result.contractStatus());

			auditService.appendEvent(new AuditService.AuditCommand(
					actor,
					"LOAN_DEFAULTED",
					"LOAN_CONTRACT",
					result.contractId().toString(),
					correlationId,
					requestId,
					null,
					"approval-execution",
					null,
					toJson(defaultedPayload)));

			outboxService.appendMessage(
					"LOAN_CONTRACT",
					result.contractId().toString(),
					"LOAN_DEFAULTED",
					defaultedPayload,
					OutboxMetadata.of(correlationId, requestId, actor));
		}

		appendExecutionAudit(
				approval,
				actor,
				"APPROVAL_EXECUTED_LOAN_DEFAULT",
				correlationId,
				Map.of(
						"contractId", result.contractId(),
						"asOfDate", payload.asOfDate(),
						"transitioned", result.transitioned(),
						"status", result.contractStatus(),
						"overdueInstallmentCount", result.overdueInstallmentCount(),
						"outstandingPrincipalMinor", result.outstandingPrincipalMinor()));

		return ResponseEntity.ok(new LoanDefaultExecutionResponse(
				approval.approvalId(),
				approval.operationType(),
				new LoanDefaultResult(
						result.contractId(),
						payload.asOfDate(),
						result.outstandingPrincipalMinor(),
						result.overdueInstallmentCount(),
						result.transitioned(),
						result.contractStatus())));
	}

	private UUID requiredApprovalId(ApprovalExecutionRequest request) {
		if (request == null || request.approvalId() == null) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "approvalId is required");
		}
		return request.approvalId();
	}

	private List<Long> extractOutboxEventIds(Map<String, Object> payload) {
		Object idsValue = payload == null ? null : payload.get("outboxEventIds");
		if (!(idsValue instanceof List<?> idsNode) || idsNode.isEmpty()) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Approval payload must include outboxEventIds");
		}

		List<Long> ids = new ArrayList<>();
		for (Object value : idsNode) {
			if (!(value instanceof Number number) || number.longValue() <= 0) {
				throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Approval payload has invalid outboxEventIds");
			}
			ids.add(number.longValue());
		}
		return ids;
	}

	private LoanDefaultPayload extractLoanDefaultPayload(Map<String, Object> payload) {
		String contractIdText = payload == null || payload.get("contractId") == null
				? null
				: String.valueOf(payload.get("contractId")).trim();
		String asOfDateText = payload == null || payload.get("asOfDate") == null
				? null
				: String.valueOf(payload.get("asOfDate")).trim();
		if (contractIdText == null || contractIdText.isBlank()) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Approval payload contractId is required");
		}
		if (asOfDateText == null || asOfDateText.isBlank()) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Approval payload asOfDate is required");
		}

		try {
			UUID contractId = UUID.fromString(contractIdText);
			LocalDate asOfDate = LocalDate.parse(asOfDateText);
			return new LoanDefaultPayload(contractId, asOfDate);
		} catch (RuntimeException ex) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Approval payload for LOAN_DEFAULT is invalid");
		}
	}

	private void appendExecutionAudit(
			ApprovalService.ApprovalView approval,
			String actor,
			String action,
			UUID correlationId,
			Map<String, Object> resultPayload) {
		Map<String, Object> before = Map.of(
				"approvalId", approval.approvalId(),
				"operationType", approval.operationType(),
				"status", "APPROVED",
				"executionStatus", "NOT_EXECUTED",
				"requestedBy", approval.requestedBy(),
				"decidedBy", approval.decidedBy());
		Map<String, Object> after = Map.of(
				"approvalId", approval.approvalId(),
				"operationType", approval.operationType(),
				"status", approval.status(),
				"executionStatus", approval.executionStatus(),
				"requestedBy", approval.requestedBy(),
				"decidedBy", approval.decidedBy(),
				"executedBy", approval.executedBy(),
				"executedAt", approval.executedAt(),
				"result", resultPayload);

		auditService.appendEvent(new AuditService.AuditCommand(
				actor,
				action,
				"APPROVAL",
				approval.approvalId().toString(),
				correlationId,
				null,
				null,
				"approval-execution",
				toJson(before),
				toJson(after)));
	}

	private UUID approvalCorrelationId(UUID approvalId) {
		return UUID.nameUUIDFromBytes(("approval:" + approvalId).getBytes(StandardCharsets.UTF_8));
	}

	private String actor(Authentication authentication) {
		return authentication == null ? "system" : authentication.getName();
	}

	private String toJson(Object value) {
		try {
			return objectMapper.writeValueAsString(value);
		} catch (JsonProcessingException ex) {
			throw new IllegalStateException("Unable to serialize execution audit payload", ex);
		}
	}

	public record ApprovalExecutionRequest(
			UUID approvalId) {
	}

	public record OutboxBulkExecutionResponse(
			UUID approvalId,
			String operationType,
			OutboxReportingService.OutboxDeadLetterBulkRequeueResponse result) {
	}

	public record LoanDefaultExecutionResponse(
			UUID approvalId,
			String operationType,
			LoanDefaultResult result) {
	}

	public record LoanDefaultResult(
			UUID contractId,
			LocalDate asOfDate,
			long outstandingPrincipalMinor,
			int overdueInstallmentCount,
			boolean transitioned,
			String status) {
	}

	private record LoanDefaultPayload(
			UUID contractId,
			LocalDate asOfDate) {
	}
}
