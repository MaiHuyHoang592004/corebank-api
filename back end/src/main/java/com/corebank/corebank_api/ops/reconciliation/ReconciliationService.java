package com.corebank.corebank_api.ops.reconciliation;

import com.corebank.corebank_api.ops.audit.AuditService;
import com.corebank.corebank_api.ops.batch.BatchRunService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.sql.Date;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ReconciliationService {

	private static final int DEFAULT_LIMIT = 2000;
	private static final int MAX_LIMIT = 10000;

	private final JdbcTemplate jdbcTemplate;
	private final BatchRunService batchRunService;
	private final AuditService auditService;
	private final ObjectMapper objectMapper;

	private final RowMapper<AccountSnapshotState> accountSnapshotStateMapper = new RowMapper<>() {
		@Override
		public AccountSnapshotState mapRow(ResultSet rs, int rowNum) throws SQLException {
			return new AccountSnapshotState(
					rs.getObject("customer_account_id", UUID.class),
					rs.getLong("account_posted"),
					rs.getLong("account_available"),
					rs.getObject("snapshot_posted", Long.class),
					rs.getObject("snapshot_available", Long.class));
		}
	};

	private final RowMapper<ReconciliationBreakView> reconciliationBreakViewMapper = new RowMapper<>() {
		@Override
		public ReconciliationBreakView mapRow(ResultSet rs, int rowNum) throws SQLException {
			String runIdText = rs.getString("run_id_text");
			Long runId = null;
			if (runIdText != null && !runIdText.isBlank()) {
				try {
					runId = Long.parseLong(runIdText);
				} catch (NumberFormatException ignored) {
					runId = null;
				}
			}

			return new ReconciliationBreakView(
					rs.getObject("reconciliation_break_id", UUID.class),
					runId,
					rs.getString("break_type"),
					rs.getString("reference_type"),
					rs.getString("reference_id"),
					rs.getString("severity"),
					rs.getString("status"),
					readPayloadMap(rs.getString("details_json")),
					readInstant(rs.getTimestamp("opened_at")),
					readInstant(rs.getTimestamp("resolved_at")));
		}
	};

	public ReconciliationService(
			JdbcTemplate jdbcTemplate,
			BatchRunService batchRunService,
			AuditService auditService,
			ObjectMapper objectMapper) {
		this.jdbcTemplate = jdbcTemplate;
		this.batchRunService = batchRunService;
		this.auditService = auditService;
		this.objectMapper = objectMapper.copy().findAndRegisterModules();
	}

	@Transactional
	public ReconciliationRunResult run(LocalDate businessDate, Integer requestedLimit, String actor) {
		LocalDate safeBusinessDate = requireBusinessDate(businessDate);
		int safeLimit = resolveLimit(requestedLimit);
		String safeActor = safeActor(actor);
		long runId = batchRunService.startBatch(
				buildBatchName(safeBusinessDate),
				"RECONCILIATION",
				toJson(Map.of(
						"businessDate", safeBusinessDate,
						"limit", safeLimit)));
		UUID runCorrelationId = runCorrelationId(runId);

		try {
			int totalActiveAccounts = countActiveAccounts();
			List<AccountSnapshotState> states = loadAccountSnapshotStates(safeBusinessDate, safeLimit);

			int missingCount = 0;
			int mismatchCount = 0;

			for (AccountSnapshotState state : states) {
				if (!state.hasSnapshot()) {
					insertBreak(runId, runCorrelationId, safeBusinessDate, state, "MISSING_ENTRY", "HIGH");
					missingCount++;
					continue;
				}

				if (state.isMismatch()) {
					insertBreak(runId, runCorrelationId, safeBusinessDate, state, "AMOUNT_MISMATCH", "CRITICAL");
					mismatchCount++;
				}
			}

			int breakCount = missingCount + mismatchCount;
			boolean truncated = totalActiveAccounts > safeLimit;
			ReconciliationRunResult result = new ReconciliationRunResult(
					runId,
					safeBusinessDate,
					states.size(),
					breakCount,
					missingCount,
					mismatchCount,
					truncated);

			batchRunService.completeBatch(runId, toJson(Map.of(
					"runId", result.runId(),
					"businessDate", result.businessDate(),
					"checkedCount", result.checkedCount(),
					"breakCount", result.breakCount(),
					"missingCount", result.missingCount(),
					"mismatchCount", result.mismatchCount(),
					"truncated", result.truncated())));
			appendRunAudit(result, safeActor, runCorrelationId);
			return result;
		} catch (RuntimeException ex) {
			batchRunService.failBatch(runId, summarizeError(ex));
			throw ex;
		}
	}

	@Transactional(readOnly = true)
	public ReconciliationBreakPage listBreaks(Long runId, String status, String severity, int requestedLimit) {
		int safeLimit = resolveReportingLimit(requestedLimit);
		String safeStatus = normalize(status);
		String safeSeverity = normalize(severity);
		String safeRunId = runId == null ? null : String.valueOf(runId);

		StringBuilder sql = new StringBuilder(
				"""
				SELECT reconciliation_break_id,
				       break_type,
				       reference_type,
				       reference_id,
				       severity,
				       status,
				       details_json::text AS details_json,
				       opened_at,
				       resolved_at,
				       details_json->>'runId' AS run_id_text
				FROM reconciliation_breaks
				WHERE 1 = 1
				""");
		List<Object> args = new ArrayList<>();
		if (safeRunId != null) {
			sql.append(" AND details_json->>'runId' = ?");
			args.add(safeRunId);
		}
		if (safeStatus != null) {
			sql.append(" AND status = ?");
			args.add(safeStatus);
		}
		if (safeSeverity != null) {
			sql.append(" AND severity = ?");
			args.add(safeSeverity);
		}
		sql.append(" ORDER BY opened_at DESC, reconciliation_break_id DESC LIMIT ?");
		args.add(safeLimit);

		List<ReconciliationBreakView> items = jdbcTemplate.query(
				sql.toString(),
				reconciliationBreakViewMapper,
				args.toArray());

		return new ReconciliationBreakPage(safeLimit, items);
	}

	private int countActiveAccounts() {
		Integer count = jdbcTemplate.queryForObject(
				"SELECT COUNT(*) FROM customer_accounts WHERE status = 'ACTIVE'",
				Integer.class);
		return count == null ? 0 : count;
	}

	private List<AccountSnapshotState> loadAccountSnapshotStates(LocalDate businessDate, int limit) {
		return jdbcTemplate.query(
				"""
				SELECT ca.customer_account_id,
				       ca.posted_balance_minor AS account_posted,
				       ca.available_balance_minor AS account_available,
				       s.posted_balance AS snapshot_posted,
				       s.available_balance AS snapshot_available
				FROM customer_accounts ca
				LEFT JOIN account_balance_snapshots s
				  ON s.customer_account_id = ca.customer_account_id
				 AND s.snapshot_date = ?::date
				WHERE ca.status = 'ACTIVE'
				ORDER BY ca.customer_account_id
				LIMIT ?
				""",
				accountSnapshotStateMapper,
				Date.valueOf(businessDate),
				limit);
	}

	private void insertBreak(
			long runId,
			UUID runCorrelationId,
			LocalDate businessDate,
			AccountSnapshotState state,
			String breakType,
			String severity) {
		long deltaPosted = state.accountPosted() - (state.snapshotPosted() == null ? 0L : state.snapshotPosted());
		long deltaAvailable = state.accountAvailable() - (state.snapshotAvailable() == null ? 0L : state.snapshotAvailable());

		Map<String, Object> details = new LinkedHashMap<>();
		details.put("runId", runId);
		details.put("businessDate", businessDate);
		details.put("customerAccountId", state.customerAccountId());
		details.put("accountPosted", state.accountPosted());
		details.put("accountAvailable", state.accountAvailable());
		details.put("snapshotPosted", state.snapshotPosted());
		details.put("snapshotAvailable", state.snapshotAvailable());
		details.put("deltaPosted", deltaPosted);
		details.put("deltaAvailable", deltaAvailable);

		jdbcTemplate.update(
				"""
				INSERT INTO reconciliation_breaks (
				    reconciliation_run_id,
				    break_type,
				    reference_type,
				    reference_id,
				    severity,
				    status,
				    details_json,
				    opened_at
				) VALUES (?, ?, 'CUSTOMER_ACCOUNT', ?, ?, 'OPEN', CAST(? AS jsonb), now())
				""",
				runCorrelationId,
				breakType,
				state.customerAccountId().toString(),
				severity,
				toJson(details));
	}

	private void appendRunAudit(ReconciliationRunResult result, String actor, UUID runCorrelationId) {
		Map<String, Object> after = Map.of(
				"runId", result.runId(),
				"businessDate", result.businessDate(),
				"checkedCount", result.checkedCount(),
				"breakCount", result.breakCount(),
				"missingCount", result.missingCount(),
				"mismatchCount", result.mismatchCount(),
				"truncated", result.truncated());

		auditService.appendEvent(new AuditService.AuditCommand(
				actor,
				"RECONCILIATION_RUN_EXECUTED",
				"RECONCILIATION_RUN",
				String.valueOf(result.runId()),
				runCorrelationId,
				null,
				null,
				"reconciliation-service",
				null,
				toJson(after)));
	}

	private LocalDate requireBusinessDate(LocalDate businessDate) {
		if (businessDate == null) {
			throw new IllegalArgumentException("businessDate is required");
		}
		return businessDate;
	}

	private int resolveLimit(Integer requestedLimit) {
		if (requestedLimit == null) {
			return DEFAULT_LIMIT;
		}
		return Math.min(Math.max(requestedLimit, 1), MAX_LIMIT);
	}

	private int resolveReportingLimit(int requestedLimit) {
		if (requestedLimit <= 0) {
			return 50;
		}
		return Math.min(requestedLimit, 200);
	}

	private String buildBatchName(LocalDate businessDate) {
		return "RECONCILIATION_" + businessDate + "_" + Instant.now().toEpochMilli();
	}

	private UUID runCorrelationId(long runId) {
		return UUID.nameUUIDFromBytes(("reconciliation-run:" + runId).getBytes(StandardCharsets.UTF_8));
	}

	private String safeActor(String actor) {
		if (actor == null || actor.trim().isEmpty()) {
			return "system";
		}
		return actor.trim();
	}

	private String normalize(String value) {
		if (value == null) {
			return null;
		}
		String trimmed = value.trim();
		return trimmed.isEmpty() ? null : trimmed.toUpperCase();
	}

	private String summarizeError(RuntimeException ex) {
		String message = ex.getMessage();
		if (message == null || message.isBlank()) {
			return ex.getClass().getSimpleName();
		}
		return message.length() > 500 ? message.substring(0, 500) : message;
	}

	private String toJson(Object value) {
		try {
			return objectMapper.writeValueAsString(value);
		} catch (JsonProcessingException ex) {
			throw new IllegalStateException("Unable to serialize reconciliation payload", ex);
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
			throw new IllegalStateException("Unable to parse reconciliation details JSON", ex);
		}
	}

	private Instant readInstant(Timestamp timestamp) {
		return timestamp == null ? null : timestamp.toInstant();
	}

	private record AccountSnapshotState(
			UUID customerAccountId,
			long accountPosted,
			long accountAvailable,
			Long snapshotPosted,
			Long snapshotAvailable) {

		boolean hasSnapshot() {
			return snapshotPosted != null && snapshotAvailable != null;
		}

		boolean isMismatch() {
			return hasSnapshot()
					&& (accountPosted != snapshotPosted
							|| accountAvailable != snapshotAvailable);
		}
	}

	public record ReconciliationRunResult(
			long runId,
			LocalDate businessDate,
			int checkedCount,
			int breakCount,
			int missingCount,
			int mismatchCount,
			boolean truncated) {
	}

	public record ReconciliationBreakPage(
			int limit,
			List<ReconciliationBreakView> items) {
	}

	public record ReconciliationBreakView(
			UUID reconciliationBreakId,
			Long runId,
			String breakType,
			String referenceType,
			String referenceId,
			String severity,
			String status,
			Map<String, Object> details,
			Instant openedAt,
			Instant resolvedAt) {
	}
}
