package com.corebank.corebank_api.reporting;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Computes operational health and lag for non-authoritative read models.
 */
@Service
public class ReadModelHealthService {

	private static final Duration MAX_ALLOWED_LAG = Duration.ofMinutes(5);
	private static final long MAX_PENDING_OUTBOX = 1_000L;

	private final JdbcTemplate jdbcTemplate;

	public ReadModelHealthService(JdbcTemplate jdbcTemplate) {
		this.jdbcTemplate = jdbcTemplate;
	}

	public ReadModelHealthSnapshot snapshot() {
		long feedCount = feedCount();
		long summaryCount = summaryCount();
		long pendingOutboxCount = pendingOutboxCount();
		Instant latestProjectedOccurredAt = latestProjectedOccurredAt();
		Instant latestOutboxCreatedAt = latestOutboxCreatedAt();
		long lagSeconds = projectionLagSeconds(latestProjectedOccurredAt, latestOutboxCreatedAt);
		boolean healthy = isHealthy(lagSeconds, pendingOutboxCount);

		return new ReadModelHealthSnapshot(
				healthy,
				feedCount,
				summaryCount,
				pendingOutboxCount,
				lagSeconds,
				latestProjectedOccurredAt,
				latestOutboxCreatedAt,
				MAX_ALLOWED_LAG.getSeconds(),
				MAX_PENDING_OUTBOX);
	}

	public long feedCount() {
		return queryLong("SELECT COUNT(*) FROM read_model_event_feed");
	}

	public long summaryCount() {
		return queryLong("SELECT COUNT(*) FROM read_model_aggregate_activity");
	}

	public long pendingOutboxCount() {
		return queryLong(
				"""
				SELECT COUNT(*)
				FROM outbox_events
				WHERE status IN ('PENDING', 'PROCESSING', 'FAILED')
				""");
	}

	public long projectionLagSeconds() {
		return projectionLagSeconds(latestProjectedOccurredAt(), latestOutboxCreatedAt());
	}

	private long projectionLagSeconds(Instant latestProjectedOccurredAt, Instant latestOutboxCreatedAt) {
		if (latestOutboxCreatedAt == null) {
			return 0L;
		}
		if (latestProjectedOccurredAt == null) {
			return Long.MAX_VALUE;
		}
		Duration lag = Duration.between(latestProjectedOccurredAt, latestOutboxCreatedAt);
		return Math.max(0L, lag.getSeconds());
	}

	private boolean isHealthy(long lagSeconds, long pendingOutboxCount) {
		return lagSeconds <= MAX_ALLOWED_LAG.getSeconds() && pendingOutboxCount <= MAX_PENDING_OUTBOX;
	}

	private Instant latestProjectedOccurredAt() {
		return queryInstant("SELECT MAX(occurred_at) FROM read_model_event_feed");
	}

	private Instant latestOutboxCreatedAt() {
		return queryInstant("SELECT MAX(created_at) FROM outbox_events");
	}

	private long queryLong(String sql) {
		Long result = jdbcTemplate.queryForObject(sql, Long.class);
		return result == null ? 0L : result;
	}

	private Instant queryInstant(String sql) {
		Timestamp result = jdbcTemplate.queryForObject(sql, Timestamp.class);
		return result == null ? null : result.toInstant();
	}

	public record ReadModelHealthSnapshot(
			boolean healthy,
			long feedCount,
			long summaryCount,
			long pendingOutboxCount,
			long lagSeconds,
			Instant latestProjectedOccurredAt,
			Instant latestOutboxCreatedAt,
			long maxAllowedLagSeconds,
			long maxPendingOutbox) {
	}
}
