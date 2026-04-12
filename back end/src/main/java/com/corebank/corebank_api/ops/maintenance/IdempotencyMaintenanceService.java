package com.corebank.corebank_api.ops.maintenance;

import com.corebank.corebank_api.common.CoreBankException;
import com.corebank.corebank_api.ops.audit.AuditService;
import com.corebank.corebank_api.ops.batch.BatchRunService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class IdempotencyMaintenanceService {

	private static final int DEFAULT_LIMIT = 1000;
	private static final int MAX_LIMIT = 10_000;
	private static final int GRACE_HOURS = 24;

	private final JdbcTemplate jdbcTemplate;
	private final BatchRunService batchRunService;
	private final AuditService auditService;
	private final ObjectMapper objectMapper;

	public IdempotencyMaintenanceService(
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
	public IdempotencyCleanupResult cleanupExpired(Integer requestedLimit, Boolean dryRunValue, String actor) {
		int limit = resolveLimit(requestedLimit);
		boolean dryRun = dryRunValue == null ? true : dryRunValue;
		String safeActor = safeActor(actor);

		long runId = batchRunService.startBatch(
				buildBatchName(),
				"IDEMPOTENCY_CLEANUP",
				toJson(Map.of(
						"limit", limit,
						"dryRun", dryRun,
						"graceHours", GRACE_HOURS)));
		UUID correlationId = correlationId(runId);

		try {
			long candidateCount = countCleanupCandidates();
			long deletedCount = 0L;
			if (!dryRun && candidateCount > 0) {
				deletedCount = deleteCleanupCandidates(limit);
			}

			long processedCount = dryRun ? Math.min(candidateCount, limit) : deletedCount;
			boolean truncated = candidateCount > processedCount;

			IdempotencyCleanupResult result = new IdempotencyCleanupResult(
					runId,
					dryRun,
					limit,
					candidateCount,
					deletedCount,
					truncated);

			batchRunService.completeBatch(runId, toJson(Map.of(
					"runId", result.runId(),
					"dryRun", result.dryRun(),
					"limit", result.limit(),
					"candidateCount", result.candidateCount(),
					"deletedCount", result.deletedCount(),
					"truncated", result.truncated())));
			appendAudit(result, safeActor, correlationId);
			return result;
		} catch (RuntimeException ex) {
			batchRunService.failBatch(runId, summarizeError(ex));
			throw ex;
		}
	}

	private long countCleanupCandidates() {
		Long count = jdbcTemplate.queryForObject(
				"""
				SELECT COUNT(*)
				FROM idempotency_keys
				WHERE status IN ('SUCCEEDED', 'FAILED')
				  AND expires_at < now() - interval '24 hours'
				""",
				Long.class);
		return count == null ? 0L : count;
	}

	private long deleteCleanupCandidates(int limit) {
		return jdbcTemplate.update(
				"""
				WITH candidates AS (
					SELECT idempotency_key
					FROM idempotency_keys
					WHERE status IN ('SUCCEEDED', 'FAILED')
					  AND expires_at < now() - interval '24 hours'
					ORDER BY expires_at ASC
					LIMIT ?
				)
				DELETE FROM idempotency_keys ik
				USING candidates c
				WHERE ik.idempotency_key = c.idempotency_key
				""",
				limit);
	}

	private int resolveLimit(Integer requestedLimit) {
		if (requestedLimit == null) {
			return DEFAULT_LIMIT;
		}
		if (requestedLimit < 1 || requestedLimit > MAX_LIMIT) {
			throw new CoreBankException("limit must be between 1 and 10000");
		}
		return requestedLimit;
	}

	private String safeActor(String actor) {
		if (actor == null || actor.trim().isEmpty()) {
			return "system";
		}
		return actor.trim();
	}

	private String buildBatchName() {
		return "IDEMPOTENCY_CLEANUP_" + Instant.now().toEpochMilli();
	}

	private UUID correlationId(long runId) {
		return UUID.nameUUIDFromBytes(("idempotency-cleanup-run:" + runId).getBytes(StandardCharsets.UTF_8));
	}

	private void appendAudit(IdempotencyCleanupResult result, String actor, UUID correlationId) {
		Map<String, Object> after = new LinkedHashMap<>();
		after.put("runId", result.runId());
		after.put("dryRun", result.dryRun());
		after.put("limit", result.limit());
		after.put("candidateCount", result.candidateCount());
		after.put("deletedCount", result.deletedCount());
		after.put("truncated", result.truncated());
		after.put("graceHours", GRACE_HOURS);
		after.put("terminalStatuses", java.util.List.of("SUCCEEDED", "FAILED"));

		auditService.appendEvent(new AuditService.AuditCommand(
				actor,
				"IDEMPOTENCY_KEYS_CLEANED",
				"IDEMPOTENCY_CLEANUP",
				String.valueOf(result.runId()),
				correlationId,
				null,
				null,
				"idempotency-maintenance-service",
				null,
				toJson(after)));
	}

	private String summarizeError(Throwable throwable) {
		String type = throwable == null ? "RuntimeException" : throwable.getClass().getSimpleName();
		String message = throwable == null || throwable.getMessage() == null
				? "unexpected error"
				: throwable.getMessage().trim();
		if (message.length() > 220) {
			message = message.substring(0, 220);
		}
		return type + ": " + message;
	}

	private String toJson(Object payload) {
		try {
			return objectMapper.writeValueAsString(payload);
		} catch (JsonProcessingException ex) {
			throw new CoreBankException("Unable to serialize idempotency maintenance payload", ex);
		}
	}

	public record IdempotencyCleanupResult(
			long runId,
			boolean dryRun,
			int limit,
			long candidateCount,
			long deletedCount,
			boolean truncated) {
	}
}
