package com.corebank.corebank_api.ops.approval;

import com.corebank.corebank_api.ops.iam.IamAuthorizationService;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/ops/approvals")
public class OpsApprovalController {

	private final ApprovalService approvalService;
	private final IamAuthorizationService iamAuthorizationService;

	public OpsApprovalController(
			ApprovalService approvalService,
			IamAuthorizationService iamAuthorizationService) {
		this.approvalService = approvalService;
		this.iamAuthorizationService = iamAuthorizationService;
	}

	@PostMapping
	public ResponseEntity<ApprovalService.ApprovalView> createApproval(
			@RequestBody(required = false) CreateApprovalRequest request,
			Authentication authentication) {
		iamAuthorizationService.requirePermission(authentication, "APPROVAL_CREATE");

		ApprovalService.ApprovalView response = approvalService.createApproval(new ApprovalService.CreateApprovalCommand(
				request == null ? null : request.referenceType(),
				request == null ? null : request.referenceId(),
				request == null ? null : request.operationType(),
				request == null ? null : request.operationPayload(),
				request == null ? null : request.expiresAt(),
				actor(authentication)));
		return ResponseEntity.status(HttpStatus.CREATED).body(response);
	}

	@GetMapping
	public ResponseEntity<ApprovalService.ApprovalPage> listApprovals(
			@RequestParam(required = false) String status,
			@RequestParam(required = false) String operationType,
			@RequestParam(defaultValue = "50") int limit,
			Authentication authentication) {
		iamAuthorizationService.requireAnyRole(authentication, "ROLE_MAKER", "ROLE_APPROVER", "ROLE_OPS", "ROLE_ADMIN");
		return ResponseEntity.ok(approvalService.listApprovals(status, operationType, limit));
	}

	@GetMapping("/{approvalId}")
	public ResponseEntity<ApprovalService.ApprovalView> getApproval(
			@PathVariable UUID approvalId,
			Authentication authentication) {
		iamAuthorizationService.requireAnyRole(authentication, "ROLE_MAKER", "ROLE_APPROVER", "ROLE_OPS", "ROLE_ADMIN");
		return ResponseEntity.ok(approvalService.getApproval(approvalId));
	}

	@PostMapping("/{approvalId}/approve")
	public ResponseEntity<ApprovalService.ApprovalView> approveApproval(
			@PathVariable UUID approvalId,
			@RequestBody(required = false) ApprovalDecisionRequest request,
			Authentication authentication) {
		iamAuthorizationService.requirePermission(authentication, "APPROVAL_DECIDE");
		String reason = request == null ? null : request.decisionReason();
		return ResponseEntity.ok(approvalService.approve(approvalId, actor(authentication), reason));
	}

	@PostMapping("/{approvalId}/reject")
	public ResponseEntity<ApprovalService.ApprovalView> rejectApproval(
			@PathVariable UUID approvalId,
			@RequestBody(required = false) ApprovalDecisionRequest request,
			Authentication authentication) {
		iamAuthorizationService.requirePermission(authentication, "APPROVAL_DECIDE");
		String reason = request == null ? null : request.decisionReason();
		return ResponseEntity.ok(approvalService.reject(approvalId, actor(authentication), reason));
	}

	private String actor(Authentication authentication) {
		return authentication == null ? "system" : authentication.getName();
	}

	public record CreateApprovalRequest(
			String referenceType,
			String referenceId,
			String operationType,
			Map<String, Object> operationPayload,
			Instant expiresAt) {
	}

	public record ApprovalDecisionRequest(
			String decisionReason) {
	}
}
