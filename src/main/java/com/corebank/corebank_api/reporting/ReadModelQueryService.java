package com.corebank.corebank_api.reporting;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;

@Service
public class ReadModelQueryService {

	private static final int MAX_PAGE_SIZE = 100;
	private static final int MAX_EVENT_LIMIT = 200;

	private final JdbcTemplate jdbcTemplate;

	public ReadModelQueryService(JdbcTemplate jdbcTemplate) {
		this.jdbcTemplate = jdbcTemplate;
	}

	public AggregateActivityPage findAggregateActivity(
			String aggregateType,
			String aggregateId,
			int page,
			int size) {
		int safePage = Math.max(page, 0);
		int safeSize = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
		FilterQuery filterQuery = buildAggregateFilter(aggregateType, aggregateId);

		String countSql = "SELECT COUNT(*) FROM read_model_aggregate_activity" + filterQuery.whereClause();
		Long totalItems = jdbcTemplate.queryForObject(countSql, Long.class, filterQuery.args().toArray());
		long safeTotalItems = totalItems == null ? 0L : totalItems;

		List<Object> pagingArgs = new ArrayList<>(filterQuery.args());
		pagingArgs.add(safeSize);
		pagingArgs.add((long) safePage * safeSize);

		String dataSql = """
				SELECT aggregate_type,
				       aggregate_id,
				       latest_event_id,
				       latest_event_type,
				       latest_occurred_at,
				       last_actor,
				       last_correlation_id,
				       event_count,
				       updated_at
				FROM read_model_aggregate_activity
				""" + filterQuery.whereClause() + """
				ORDER BY latest_occurred_at DESC, aggregate_type ASC, aggregate_id ASC
				LIMIT ? OFFSET ?
				""";

		List<AggregateActivityItem> items = jdbcTemplate.query(
				dataSql,
				this::mapAggregateActivityItem,
				pagingArgs.toArray());

		return new AggregateActivityPage(safePage, safeSize, safeTotalItems, items);
	}

	public AggregateEventPage findAggregateEvents(
			String aggregateType,
			String aggregateId,
			String eventType,
			Instant fromOccurredAt,
			Instant toOccurredAt,
			int limit) {
		int safeLimit = Math.min(Math.max(limit, 1), MAX_EVENT_LIMIT);
		List<String> conditions = new ArrayList<>();
		List<Object> args = new ArrayList<>();

		conditions.add("aggregate_type = ?");
		args.add(aggregateType);
		conditions.add("aggregate_id = ?");
		args.add(aggregateId);
		if (eventType != null && !eventType.isBlank()) {
			conditions.add("event_type = ?");
			args.add(eventType.trim());
		}
		if (fromOccurredAt != null) {
			conditions.add("occurred_at >= ?");
			args.add(OffsetDateTime.ofInstant(fromOccurredAt, ZoneOffset.UTC));
		}
		if (toOccurredAt != null) {
			conditions.add("occurred_at < ?");
			args.add(OffsetDateTime.ofInstant(toOccurredAt, ZoneOffset.UTC));
		}
		args.add(safeLimit);

		String sql = """
				SELECT event_id,
				       source_topic,
				       aggregate_type,
				       aggregate_id,
				       event_type,
				       occurred_at,
				       schema_version,
				       correlation_id,
				       request_id,
				       actor,
				       payload::text AS payload_json,
				       projected_at
				FROM read_model_event_feed
				WHERE %s
				ORDER BY occurred_at DESC, event_id DESC
				LIMIT ?
				""".formatted(String.join(" AND ", conditions));

		List<AggregateEventItem> items = jdbcTemplate.query(
				sql,
				this::mapAggregateEventItem,
				args.toArray());

		return new AggregateEventPage(aggregateType, aggregateId, safeLimit, items);
	}

	private FilterQuery buildAggregateFilter(String aggregateType, String aggregateId) {
		List<String> conditions = new ArrayList<>();
		List<Object> args = new ArrayList<>();

		if (aggregateType != null && !aggregateType.isBlank()) {
			conditions.add("aggregate_type = ?");
			args.add(aggregateType.trim());
		}
		if (aggregateId != null && !aggregateId.isBlank()) {
			conditions.add("aggregate_id = ?");
			args.add(aggregateId.trim());
		}

		if (conditions.isEmpty()) {
			return new FilterQuery("", args);
		}

		return new FilterQuery(" WHERE " + String.join(" AND ", conditions) + " ", args);
	}

	private AggregateActivityItem mapAggregateActivityItem(ResultSet rs, int rowNum) throws SQLException {
		return new AggregateActivityItem(
				rs.getString("aggregate_type"),
				rs.getString("aggregate_id"),
				rs.getObject("latest_event_id", UUID.class),
				rs.getString("latest_event_type"),
				readInstant(rs, "latest_occurred_at"),
				rs.getString("last_actor"),
				rs.getString("last_correlation_id"),
				rs.getLong("event_count"),
				readInstant(rs, "updated_at"));
	}

	private AggregateEventItem mapAggregateEventItem(ResultSet rs, int rowNum) throws SQLException {
		return new AggregateEventItem(
				rs.getObject("event_id", UUID.class),
				rs.getString("source_topic"),
				rs.getString("aggregate_type"),
				rs.getString("aggregate_id"),
				rs.getString("event_type"),
				readInstant(rs, "occurred_at"),
				rs.getString("schema_version"),
				rs.getString("correlation_id"),
				rs.getString("request_id"),
				rs.getString("actor"),
				rs.getString("payload_json"),
				readInstant(rs, "projected_at"));
	}

	private Instant readInstant(ResultSet rs, String column) throws SQLException {
		java.sql.Timestamp timestamp = rs.getTimestamp(column);
		return timestamp == null ? null : timestamp.toInstant();
	}

	private record FilterQuery(String whereClause, List<Object> args) {
	}

	public record AggregateActivityPage(
			int page,
			int size,
			long totalItems,
			List<AggregateActivityItem> items) {
	}

	public record AggregateActivityItem(
			String aggregateType,
			String aggregateId,
			UUID latestEventId,
			String latestEventType,
			Instant latestOccurredAt,
			String lastActor,
			String lastCorrelationId,
			long eventCount,
			Instant updatedAt) {
	}

	public record AggregateEventPage(
			String aggregateType,
			String aggregateId,
			int limit,
			List<AggregateEventItem> items) {
	}

	public record AggregateEventItem(
			UUID eventId,
			String sourceTopic,
			String aggregateType,
			String aggregateId,
			String eventType,
			Instant occurredAt,
			String schemaVersion,
			String correlationId,
			String requestId,
			String actor,
			String payloadJson,
			Instant projectedAt) {
	}
}
