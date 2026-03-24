package com.corebank.corebank_api.reporting;

import com.corebank.corebank_api.integration.OutboxEventRepository;
import com.corebank.corebank_api.ops.audit.AuditService;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class OutboxReportingService {

	private static final int MAX_LIMIT = 200;

	private final JdbcTemplate jdbcTemplate;
	private final OutboxEventRepository outboxEventRepository;
	private final AuditService auditService;

	public OutboxReportingService(
			JdbcTemplate jdbcTemplate,
			OutboxEventRepository outboxEventRepository,
			AuditService auditService) {
		this.jdbcTemplate = jdbcTemplate;
		this.outboxEventRepository = outboxEventRepository;
		this.auditService = auditService;
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
		int safeLimit = Math.min(Math.max(limit, 1), MAX_LIMIT);

		List<OutboxDeadLetterItem> items = jdbcTemplate.query(
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
				ORDER BY dead_lettered_at DESC, dead_letter_id DESC
				LIMIT ?
				""",
				(rs, rowNum) -> new OutboxDeadLetterItem(
						rs.getLong("dead_letter_id"),
						rs.getLong("outbox_event_id"),
						rs.getString("aggregate_type"),
						rs.getString("aggregate_id"),
						rs.getString("event_type"),
						rs.getInt("retry_count"),
						rs.getString("last_error"),
						readInstant(rs.getTimestamp("dead_lettered_at"))),
				safeLimit);

		return new OutboxDeadLetterPage(safeLimit, items);
	}

	@Transactional
	public OutboxDeadLetterRequeueResponse requeueDeadLetter(long outboxEventId, String actor) {
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
}
