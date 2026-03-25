package com.corebank.corebank_api.reporting;

import com.corebank.corebank_api.integration.OutboxEventRepository;
import com.corebank.corebank_api.ops.audit.AuditService;
import com.corebank.corebank_api.ops.system.OpsRuntimeModePolicy;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class OutboxReportingService {

	private static final int MAX_LIMIT = 200;
	private static final int MAX_BULK_REQUEUE_SIZE = 100;

	private final JdbcTemplate jdbcTemplate;
	private final NamedParameterJdbcTemplate namedParameterJdbcTemplate;
	private final OutboxEventRepository outboxEventRepository;
	private final AuditService auditService;
	private final OpsRuntimeModePolicy opsRuntimeModePolicy;

	public OutboxReportingService(
			JdbcTemplate jdbcTemplate,
			NamedParameterJdbcTemplate namedParameterJdbcTemplate,
			OutboxEventRepository outboxEventRepository,
			AuditService auditService,
			OpsRuntimeModePolicy opsRuntimeModePolicy) {
		this.jdbcTemplate = jdbcTemplate;
		this.namedParameterJdbcTemplate = namedParameterJdbcTemplate;
		this.outboxEventRepository = outboxEventRepository;
		this.auditService = auditService;
		this.opsRuntimeModePolicy = opsRuntimeModePolicy;
	}

	public OutboxSummary summary() {
		OutboxStatusCounters counters = jdbcTemplate.queryForObject(
				"""
				SELECT COUNT(*) FILTER (WHERE status = 'PENDING')   AS pending_count,
				       COUNT(*) FILTER (WHERE status = 'PROCESSING') AS processing_count,
				       COUNT(*) FILTER (WHERE status = 'FAILED')    AS failed_count,
				       COUNT(*) FILTER (WHERE status = 'PROCESSED') AS processed_count
				FROM outbox_events
				""",
				(rs, rowNum) -> new OutboxStatusCounters(
						rs.getLong("pending_count"),
						rs.getLong("processing_count"),
						rs.getLong("failed_count"),
						rs.getLong("processed_count")));

		long deadLetterCount = queryLong("SELECT COUNT(*) FROM outbox_dead_letters");
		long retryQueueCount = queryLong(
				"""
				SELECT COUNT(*)
				FROM outbox_events oe
				LEFT JOIN outbox_dead_letters dl ON dl.outbox_event_id = oe.id
				WHERE oe.status IN ('PENDING', 'PROCESSING')
				   OR (oe.status = 'FAILED' AND dl.outbox_event_id IS NULL)
				""");
		Instant oldestRetryQueueCreatedAt = queryInstant(
				"""
				SELECT MIN(oe.created_at)
				FROM outbox_events oe
				LEFT JOIN outbox_dead_letters dl ON dl.outbox_event_id = oe.id
				WHERE oe.status IN ('PENDING', 'PROCESSING')
				   OR (oe.status = 'FAILED' AND dl.outbox_event_id IS NULL)
				""");
		long oldestRetryQueueAgeSeconds = retryQueueAgeSeconds(oldestRetryQueueCreatedAt);

		OutboxStatusCounters safeCounters = counters == null ? new OutboxStatusCounters(0, 0, 0, 0) : counters;

		return new OutboxSummary(
				safeCounters.pendingCount(),
				safeCounters.processingCount(),
				safeCounters.failedCount(),
				safeCounters.processedCount(),
				deadLetterCount,
				retryQueueCount,
				oldestRetryQueueCreatedAt,
				oldestRetryQueueAgeSeconds);
	}

	public OutboxDeadLetterPage deadLetters(int limit) {
		return deadLetters(limit, null, null, null, null);
	}

	public OutboxDeadLetterPage deadLetters(
			int limit,
			String eventType,
			String aggregateType,
			Instant fromDeadLetteredAt,
			Instant toDeadLetteredAt) {
		int safeLimit = Math.min(Math.max(limit, 1), MAX_LIMIT);
		String safeEventType = normalizeFilter(eventType);
		String safeAggregateType = normalizeFilter(aggregateType);
		Timestamp fromTimestamp = asTimestamp(fromDeadLetteredAt);
		Timestamp toTimestamp = asTimestamp(toDeadLetteredAt);

		MapSqlParameterSource params = new MapSqlParameterSource()
				.addValue("limit", safeLimit)
				.addValue("eventType", safeEventType)
				.addValue("aggregateType", safeAggregateType)
				.addValue("fromDeadLetteredAt", fromTimestamp)
				.addValue("toDeadLetteredAt", toTimestamp);

		List<OutboxDeadLetterItem> items = namedParameterJdbcTemplate.query(
				"""
				SELECT dead_letter_id,
				       outbox_event_id,
				       aggregate_type,
				       aggregate_id,
				       event_type,
				       retry_count,
				       last_error,
				       dead_lettered_at
				FROM outbox_dead_letters
				WHERE (CAST(:eventType AS TEXT) IS NULL OR event_type = CAST(:eventType AS TEXT))
				  AND (CAST(:aggregateType AS TEXT) IS NULL OR aggregate_type = CAST(:aggregateType AS TEXT))
				  AND (CAST(:fromDeadLetteredAt AS TIMESTAMPTZ) IS NULL OR dead_lettered_at >= CAST(:fromDeadLetteredAt AS TIMESTAMPTZ))
				  AND (CAST(:toDeadLetteredAt AS TIMESTAMPTZ) IS NULL OR dead_lettered_at < CAST(:toDeadLetteredAt AS TIMESTAMPTZ))
				ORDER BY dead_lettered_at DESC, dead_letter_id DESC
				LIMIT :limit
				""",
				params,
				(rs, rowNum) -> new OutboxDeadLetterItem(
						rs.getLong("dead_letter_id"),
						rs.getLong("outbox_event_id"),
						rs.getString("aggregate_type"),
						rs.getString("aggregate_id"),
						rs.getString("event_type"),
						rs.getInt("retry_count"),
						rs.getString("last_error"),
						readInstant(rs.getTimestamp("dead_lettered_at"))));

		return new OutboxDeadLetterPage(safeLimit, items);
	}

	@Transactional
	public OutboxDeadLetterRequeueResponse requeueDeadLetter(long outboxEventId, String actor) {
		opsRuntimeModePolicy.requireRunningForMoneyImpactWrite();
		OutboxEventRepository.DeadLetterRequeueStatus status =
				outboxEventRepository.requeueDeadLetter(outboxEventId);

		if (status == OutboxEventRepository.DeadLetterRequeueStatus.REQUEUED) {
			auditService.appendEvent(new AuditService.AuditCommand(
					safeActor(actor),
					"OUTBOX_DEAD_LETTER_REQUEUED",
					"OUTBOX_EVENT",
					String.valueOf(outboxEventId),
					null,
					null,
					null,
					null,
					"{\"status\":\"FAILED\",\"deadLettered\":true}",
					"{\"status\":\"PENDING\",\"deadLettered\":false}"));
			return new OutboxDeadLetterRequeueResponse(
					outboxEventId,
					"REQUEUED",
					"Outbox event was requeued from dead-letter.");
		}

		if (status == OutboxEventRepository.DeadLetterRequeueStatus.NOT_FOUND) {
			return new OutboxDeadLetterRequeueResponse(
					outboxEventId,
					"NOT_FOUND",
					"Dead-letter outbox event was not found.");
		}

		return new OutboxDeadLetterRequeueResponse(
				outboxEventId,
				"CONFLICT",
				"Outbox event cannot be requeued from its current state.");
	}

	@Transactional
	public OutboxDeadLetterBulkRequeueResponse requeueDeadLetters(List<Long> outboxEventIds, String actor) {
		opsRuntimeModePolicy.requireRunningForMoneyImpactWrite();
		Set<Long> deduped = new LinkedHashSet<>(outboxEventIds);
		List<OutboxDeadLetterRequeueResponse> items = new ArrayList<>(deduped.size());

		int requeuedCount = 0;
		int notFoundCount = 0;
		int conflictCount = 0;

		for (Long outboxEventId : deduped) {
			OutboxDeadLetterRequeueResponse result = requeueDeadLetter(outboxEventId, actor);
			items.add(result);

			if ("REQUEUED".equals(result.status())) {
				requeuedCount++;
			} else if ("NOT_FOUND".equals(result.status())) {
				notFoundCount++;
			} else if ("CONFLICT".equals(result.status())) {
				conflictCount++;
			}
		}

		return new OutboxDeadLetterBulkRequeueResponse(
				deduped.size(),
				requeuedCount,
				notFoundCount,
				conflictCount,
				items);
	}

	public int maxBulkRequeueSize() {
		return MAX_BULK_REQUEUE_SIZE;
	}

	private long retryQueueAgeSeconds(Instant oldestRetryQueueCreatedAt) {
		if (oldestRetryQueueCreatedAt == null) {
			return 0L;
		}
		return Math.max(0L, Duration.between(oldestRetryQueueCreatedAt, Instant.now()).getSeconds());
	}

	private long queryLong(String sql) {
		Long value = jdbcTemplate.queryForObject(sql, Long.class);
		return value == null ? 0L : value;
	}

	private Instant queryInstant(String sql) {
		Timestamp value = jdbcTemplate.queryForObject(sql, Timestamp.class);
		return readInstant(value);
	}

	private Instant readInstant(Timestamp timestamp) {
		return timestamp == null ? null : timestamp.toInstant();
	}

	private Timestamp asTimestamp(Instant instant) {
		return instant == null ? null : Timestamp.from(instant);
	}

	private String normalizeFilter(String value) {
		if (value == null) {
			return null;
		}
		String trimmed = value.trim();
		return trimmed.isEmpty() ? null : trimmed;
	}

	private String safeActor(String actor) {
		return actor == null || actor.isBlank() ? "system" : actor;
	}

	private record OutboxStatusCounters(
			long pendingCount,
			long processingCount,
			long failedCount,
			long processedCount) {
	}

	public record OutboxSummary(
			long pendingCount,
			long processingCount,
			long failedCount,
			long processedCount,
			long deadLetterCount,
			long retryQueueCount,
			Instant oldestRetryQueueCreatedAt,
			long oldestRetryQueueAgeSeconds) {
	}

	public record OutboxDeadLetterPage(
			int limit,
			List<OutboxDeadLetterItem> items) {
	}

	public record OutboxDeadLetterItem(
			long deadLetterId,
			long outboxEventId,
			String aggregateType,
			String aggregateId,
			String eventType,
			int retryCount,
			String lastError,
			Instant deadLetteredAt) {
	}

	public record OutboxDeadLetterRequeueResponse(
			long outboxEventId,
			String status,
			String message) {
	}

	public record OutboxDeadLetterBulkRequeueResponse(
			int requestedCount,
			int requeuedCount,
			int notFoundCount,
			int conflictCount,
			List<OutboxDeadLetterRequeueResponse> items) {
	}
}
