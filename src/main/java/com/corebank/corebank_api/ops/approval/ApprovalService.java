package com.corebank.corebank_api.ops.approval;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.corebank.corebank_api.ops.system.OpsRuntimeModePolicy;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class ApprovalService {

	private static final int MAX_LIST_LIMIT = 200;
	private static final Set<String> SUPPORTED_OPERATION_TYPES = Set.of(
			"OUTBOX_BULK_REQUEUE",
			"LOAN_DEFAULT");

	private final JdbcTemplate jdbcTemplate;
	private final ObjectMapper objectMapper;
	private final OpsRuntimeModePolicy opsRuntimeModePolicy;

	private final RowMapper<ApprovalView> approvalRowMapper = new RowMapper<>() {
		@Override
		public ApprovalView mapRow(ResultSet rs, int rowNum) throws SQLException {
			String operationType = rs.getString("operation_type");
			if (operationType == null || operationType.isBlank()) {
				operationType = rs.getString("approval_type");
			}
			String executionStatus = rs.getString("execution_status");
			if (executionStatus == null || executionStatus.isBlank()) {
				executionStatus = "NOT_EXECUTED";
			}
			return new ApprovalView(
					rs.getObject("approval_id", UUID.class),
					rs.getString("reference_type"),
					rs.getString("reference_id"),
					rs.getString("approval_type"),
					operationType,
					readPayloadMap(rs.getString("operation_payload_json")),
					rs.getString("status"),
					rs.getString("requested_by"),
					rs.getString("decided_by"),
					rs.getString("decision_reason"),
					readInstant(rs.getTimestamp("created_at")),
					readInstant(rs.getTimestamp("decided_at")),
					executionStatus,
					rs.getString("executed_by"),
					readInstant(rs.getTimestamp("executed_at")),
					readInstant(rs.getTimestamp("expires_at")));
		}
	};

	public ApprovalService(
			JdbcTemplate jdbcTemplate,
			ObjectMapper objectMapper,
			OpsRuntimeModePolicy opsRuntimeModePolicy) {
		this.jdbcTemplate = jdbcTemplate;
		this.objectMapper = objectMapper.copy().findAndRegisterModules();
		this.opsRuntimeModePolicy = opsRuntimeModePolicy;
	}

	@Transactional
	public ApprovalView createApproval(CreateApprovalCommand command) {
		validateCreateCommand(command);

		UUID approvalId = UUID.randomUUID();
		String operationType = normalizeOperationType(command.operationType());
		String referenceType = normalizeReferenceType(command.referenceType());
		String referenceId = normalizeReferenceId(command.referenceId());
		String requestedBy = safeActor(command.requestedBy());
		String payloadJson = toJson(command.operationPayload());
		Timestamp expiresAt = asTimestamp(command.expiresAt());

		jdbcTemplate.update(
				"""
				INSERT INTO approvals (
				    approval_id,
				    reference_type,
				    reference_id,
				    approval_type,
				    status,
				    requested_by,
				    operation_type,
				    operation_payload_json,
				    execution_status,
				    expires_at
				) VALUES (?, ?, ?, ?, 'PENDING', ?, ?, CAST(? AS jsonb), 'NOT_EXECUTED', ?)
				""",
				approvalId,
				referenceType,
				referenceId,
				operationType,
				requestedBy,
				operationType,
				payloadJson,
				expiresAt);

		return getApproval(approvalId);
	}

	@Transactional(readOnly = true)
	public ApprovalView getApproval(UUID approvalId) {
		return findApproval(approvalId).orElseThrow(() ->
				new ResponseStatusException(HttpStatus.NOT_FOUND, "Approval not found"));
	}

	@Transactional(readOnly = true)
	public Optional<ApprovalView> findApproval(UUID approvalId) {
		List<ApprovalView> rows = jdbcTemplate.query(
				"""
				SELECT approval_id,
				       reference_type,
				       reference_id,
				       approval_type,
				       operation_type,
				       operation_payload_json::text AS operation_payload_json,
				       status,
				       requested_by,
				       decided_by,
				       decision_reason,
				       created_at,
				       decided_at,
				       execution_status,
				       executed_by,
				       executed_at,
				       expires_at
				FROM approvals
				WHERE approval_id = ?
				""",
				approvalRowMapper,
				approvalId);
		return rows.stream().findFirst();
	}

	@Transactional(readOnly = true)
	public ApprovalPage listApprovals(String status, String operationType, int limit) {
		String safeStatus = normalizeText(status);
		String safeOperationType = normalizeText(operationType);
		int safeLimit = Math.min(Math.max(limit, 1), MAX_LIST_LIMIT);

		List<ApprovalView> items = jdbcTemplate.query(
				"""
				SELECT approval_id,
				       reference_type,
				       reference_id,
				       approval_type,
				       operation_type,
				       operation_payload_json::text AS operation_payload_json,
				       status,
				       requested_by,
				       decided_by,
				       decision_reason,
				       created_at,
				       decided_at,
				       execution_status,
				       executed_by,
				       executed_at,
				       expires_at
				FROM approvals
				WHERE (? IS NULL OR status = ?)
				  AND (? IS NULL OR operation_type = ?)
				ORDER BY created_at DESC, approval_id DESC
				LIMIT ?
				""",
				approvalRowMapper,
				safeStatus,
				safeStatus,
				safeOperationType,
				safeOperationType,
				safeLimit);

		return new ApprovalPage(safeLimit, items);
	}

	@Transactional
	public ApprovalView approve(UUID approvalId, String actor, String decisionReason) {
		return decide(approvalId, actor, decisionReason, "APPROVED");
	}

	@Transactional
	public ApprovalView reject(UUID approvalId, String actor, String decisionReason) {
		return decide(approvalId, actor, decisionReason, "REJECTED");
	}

	@Transactional
	public ApprovalView claimApprovedExecution(UUID approvalId, String actor, String expectedOperationType) {
		opsRuntimeModePolicy.requireRunningForMoneyImpactWrite();
		ApprovalView locked = lockApproval(approvalId);
		String normalizedExpectedOperation = normalizeOperationType(expectedOperationType);

		if (!normalizedExpectedOperation.equalsIgnoreCase(normalizeOperationType(locked.operationType()))) {
			throw new ResponseStatusException(
					HttpStatus.CONFLICT,
					"Approval operation type does not match execution endpoint");
		}

		if (!"APPROVED".equals(locked.status())) {
			throw new ResponseStatusException(HttpStatus.CONFLICT, "Approval is not in APPROVED status");
		}
		if (isExpired(locked.expiresAt())) {
			throw new ResponseStatusException(HttpStatus.CONFLICT, "Approval is expired");
		}
		if (!"NOT_EXECUTED".equals(locked.executionStatus())) {
			throw new ResponseStatusException(HttpStatus.CONFLICT, "Approval execution already completed");
		}

		int updated = jdbcTemplate.update(
				"""
				UPDATE approvals
				SET execution_status = 'EXECUTED',
				    executed_by = ?,
				    executed_at = now()
				WHERE approval_id = ?
				  AND execution_status = 'NOT_EXECUTED'
				""",
				safeActor(actor),
				approvalId);
		if (updated != 1) {
			throw new ResponseStatusException(HttpStatus.CONFLICT, "Approval execution already completed");
		}

		return getApproval(approvalId);
	}

	private ApprovalView decide(UUID approvalId, String actor, String decisionReason, String targetStatus) {
		ApprovalView locked = lockApproval(approvalId);
		String safeActor = safeActor(actor);

		if (!"PENDING".equals(locked.status())) {
			throw new ResponseStatusException(HttpStatus.CONFLICT, "Approval is no longer pending");
		}
		if (isExpired(locked.expiresAt())) {
			throw new ResponseStatusException(HttpStatus.CONFLICT, "Approval is expired");
		}
		if (safeActor.equalsIgnoreCase(locked.requestedBy())) {
			throw new ResponseStatusException(HttpStatus.CONFLICT, "Maker cannot decide own approval");
		}

		jdbcTemplate.update(
				"""
				UPDATE approvals
				SET status = ?,
				    decided_by = ?,
				    decided_at = now(),
				    decision_note = ?,
				    decision_reason = ?
				WHERE approval_id = ?
				""",
				targetStatus,
				safeActor,
				normalizeText(decisionReason),
				normalizeText(decisionReason),
				approvalId);

		return getApproval(approvalId);
	}

	private ApprovalView lockApproval(UUID approvalId) {
		List<ApprovalView> rows = jdbcTemplate.query(
				"""
				SELECT approval_id,
				       reference_type,
				       reference_id,
				       approval_type,
				       operation_type,
				       operation_payload_json::text AS operation_payload_json,
				       status,
				       requested_by,
				       decided_by,
				       decision_reason,
				       created_at,
				       decided_at,
				       execution_status,
				       executed_by,
				       executed_at,
				       expires_at
				FROM approvals
				WHERE approval_id = ?
				FOR UPDATE
				""",
				approvalRowMapper,
				approvalId);

		if (rows.isEmpty()) {
			throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Approval not found");
		}
		return rows.get(0);
	}

	private void validateCreateCommand(CreateApprovalCommand command) {
		if (command == null) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Approval request body is required");
		}

		String operationType = normalizeOperationType(command.operationType());
		if (!SUPPORTED_OPERATION_TYPES.contains(operationType)) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unsupported operationType");
		}
		if (command.expiresAt() != null && command.expiresAt().isBefore(Instant.now())) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "expiresAt must be in the future");
		}
		validateOperationPayload(operationType, command.operationPayload());
	}

	private void validateOperationPayload(String operationType, Map<String, Object> operationPayload) {
		if (operationPayload == null || operationPayload.isEmpty()) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "operationPayload must be a non-empty JSON object");
		}

		if ("OUTBOX_BULK_REQUEUE".equals(operationType)) {
			Object idsValue = operationPayload.get("outboxEventIds");
			if (!(idsValue instanceof List<?> ids) || ids.isEmpty()) {
				throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "outboxEventIds must be a non-empty array");
			}
			if (ids.size() > 100) {
				throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "outboxEventIds must not exceed 100 entries");
			}
			for (Object id : ids) {
				if (!(id instanceof Number number) || number.longValue() <= 0) {
					throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "outboxEventIds must contain positive numeric values");
				}
			}
			return;
		}

		if ("LOAN_DEFAULT".equals(operationType)) {
			String contractIdValue = stringValue(operationPayload.get("contractId"));
			String asOfDateValue = stringValue(operationPayload.get("asOfDate"));
			if (contractIdValue == null || contractIdValue.isBlank()) {
				throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "contractId is required");
			}
			if (asOfDateValue == null || asOfDateValue.isBlank()) {
				throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "asOfDate is required");
			}
			try {
				UUID.fromString(contractIdValue);
			} catch (IllegalArgumentException ex) {
				throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "contractId must be a UUID");
			}
			try {
				LocalDate.parse(asOfDateValue);
			} catch (RuntimeException ex) {
				throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "asOfDate must be yyyy-MM-dd");
			}
		}
	}

	private String toJson(Map<String, Object> value) {
		try {
			return objectMapper.writeValueAsString(value);
		} catch (JsonProcessingException ex) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "operationPayload cannot be serialized");
		}
	}

	@SuppressWarnings("unchecked")
	private Map<String, Object> readPayloadMap(String json) {
		if (json == null || json.isBlank()) {
			return Map.of();
		}
		try {
			Object value = objectMapper.readValue(json, Map.class);
			if (value instanceof Map<?, ?> mapValue) {
				return (Map<String, Object>) mapValue;
			}
			return Map.of();
		} catch (JsonProcessingException ex) {
			throw new IllegalStateException("Unable to parse approval payload JSON", ex);
		}
	}

	private String stringValue(Object value) {
		if (value == null) {
			return null;
		}
		String asText = String.valueOf(value).trim();
		return asText.isEmpty() ? null : asText;
	}

	private String normalizeOperationType(String value) {
		String normalized = normalizeText(value);
		if (normalized == null) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "operationType is required");
		}
		return normalized.toUpperCase(Locale.ROOT);
	}

	private String normalizeReferenceType(String value) {
		String normalized = normalizeText(value);
		return normalized == null ? "OPERATION" : normalized.toUpperCase(Locale.ROOT);
	}

	private String normalizeReferenceId(String value) {
		String normalized = normalizeText(value);
		return normalized == null ? UUID.randomUUID().toString() : normalized;
	}

	private String safeActor(String value) {
		String normalized = normalizeText(value);
		if (normalized == null) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "actor is required");
		}
		return normalized;
	}

	private String normalizeText(String value) {
		if (value == null) {
			return null;
		}
		String trimmed = value.trim();
		return trimmed.isEmpty() ? null : trimmed;
	}

	private boolean isExpired(Instant expiresAt) {
		return expiresAt != null && expiresAt.isBefore(Instant.now());
	}

	private Instant readInstant(Timestamp timestamp) {
		return timestamp == null ? null : timestamp.toInstant();
	}

	private Timestamp asTimestamp(Instant instant) {
		return instant == null ? null : Timestamp.from(instant);
	}

	public record CreateApprovalCommand(
			String referenceType,
			String referenceId,
			String operationType,
			Map<String, Object> operationPayload,
			Instant expiresAt,
			String requestedBy) {
	}

	public record ApprovalPage(
			int limit,
			List<ApprovalView> items) {
	}

	public record ApprovalView(
			UUID approvalId,
			String referenceType,
			String referenceId,
			String approvalType,
			String operationType,
			Map<String, Object> operationPayload,
			String status,
			String requestedBy,
			String decidedBy,
			String decisionReason,
			Instant createdAt,
			Instant decidedAt,
			String executionStatus,
			String executedBy,
			Instant executedAt,
			Instant expiresAt) {
	}
}
