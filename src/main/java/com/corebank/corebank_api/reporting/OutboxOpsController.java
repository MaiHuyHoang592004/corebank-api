package com.corebank.corebank_api.reporting;

import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/reporting/outbox/dead-letters")
public class OutboxOpsController {

	private final OutboxReportingService outboxReportingService;
	private final OpsAuthorizationPolicy opsAuthorizationPolicy;

	public OutboxOpsController(
			OutboxReportingService outboxReportingService,
			OpsAuthorizationPolicy opsAuthorizationPolicy) {
		this.outboxReportingService = outboxReportingService;
		this.opsAuthorizationPolicy = opsAuthorizationPolicy;
	}

	@PostMapping("/{outboxEventId}/requeue")
	public ResponseEntity<OutboxReportingService.OutboxDeadLetterRequeueResponse> requeueOutboxDeadLetter(
			@PathVariable Long outboxEventId,
			Authentication authentication) {
		opsAuthorizationPolicy.requireOpsAccess(authentication);

		String actor = authentication == null ? "system" : authentication.getName();
		OutboxReportingService.OutboxDeadLetterRequeueResponse response =
				outboxReportingService.requeueDeadLetter(outboxEventId, actor);

		if ("REQUEUED".equals(response.status())) {
			return ResponseEntity.ok(response);
		}
		if ("NOT_FOUND".equals(response.status())) {
			return ResponseEntity.status(HttpStatus.NOT_FOUND).body(response);
		}
		return ResponseEntity.status(HttpStatus.CONFLICT).body(response);
	}

	@PostMapping("/requeue-bulk")
	public ResponseEntity<OutboxReportingService.OutboxDeadLetterBulkRequeueResponse> requeueOutboxDeadLettersBulk(
			@RequestBody(required = false) BulkDeadLetterRequeueRequest request,
			Authentication authentication) {
		opsAuthorizationPolicy.requireOpsAccess(authentication);

		if (request == null || request.outboxEventIds() == null || request.outboxEventIds().isEmpty()) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "outboxEventIds must not be empty");
		}
		if (request.outboxEventIds().stream().anyMatch(id -> id == null || id <= 0)) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "outboxEventIds must contain positive numbers");
		}
		if (request.outboxEventIds().size() > outboxReportingService.maxBulkRequeueSize()) {
			throw new ResponseStatusException(
					HttpStatus.BAD_REQUEST,
					"outboxEventIds must not exceed " + outboxReportingService.maxBulkRequeueSize());
		}

		String actor = authentication == null ? "system" : authentication.getName();
		OutboxReportingService.OutboxDeadLetterBulkRequeueResponse response =
				outboxReportingService.requeueDeadLetters(request.outboxEventIds(), actor);
		return ResponseEntity.ok(response);
	}

	public record BulkDeadLetterRequeueRequest(List<Long> outboxEventIds) {
	}
}
