package com.corebank.corebank_api.integration.saga;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class SagaQueryService {

	private static final int MAX_PAGE_SIZE = 100;
	private static final int MAX_STEP_LIMIT = 500;

	private final JdbcTemplate jdbcTemplate;

	public SagaQueryService(JdbcTemplate jdbcTemplate) {
		this.jdbcTemplate = jdbcTemplate;
	}

	public SagaInstancePage findSagas(
			String sagaType,
			String status,
			String businessKey,
			int page,
			int size) {
		int safePage = Math.max(page, 0);
		int safeSize = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
		FilterQuery filter = buildSagaFilter(sagaType, status, businessKey);

		String countSql = "SELECT COUNT(*) FROM saga_instances" + filter.whereClause();
		Long totalItems = jdbcTemplate.queryForObject(countSql, Long.class, filter.args().toArray());
		long safeTotalItems = totalItems == null ? 0L : totalItems;

		List<Object> pagingArgs = new ArrayList<>(filter.args());
		pagingArgs.add(safeSize);
		pagingArgs.add((long) safePage * safeSize);

		String dataSql = """
				SELECT saga_instance_id,
				       saga_type,
				       business_key,
				       status,
				       current_step,
				       version,
				       started_at,
				       last_updated_at
				FROM saga_instances
				""" + filter.whereClause() + """
				ORDER BY last_updated_at DESC, saga_instance_id ASC
				LIMIT ? OFFSET ?
				""";

		List<SagaInstanceItem> items = jdbcTemplate.query(
				dataSql,
				this::mapSagaInstanceItem,
				pagingArgs.toArray());

		return new SagaInstancePage(safePage, safeSize, safeTotalItems, items);
	}

	public SagaStepPage findSagaSteps(UUID sagaInstanceId, int limit) {
		int safeLimit = Math.min(Math.max(limit, 1), MAX_STEP_LIMIT);

		String sql = """
				SELECT saga_step_id,
				       step_no,
				       step_name,
				       direction,
				       step_key,
				       status,
				       started_at,
				       finished_at
				FROM saga_steps
				WHERE saga_instance_id = ?
				ORDER BY saga_step_id ASC
				LIMIT ?
				""";

		List<SagaStepItem> items = jdbcTemplate.query(
				sql,
				this::mapSagaStepItem,
				sagaInstanceId,
				safeLimit);

		return new SagaStepPage(sagaInstanceId, safeLimit, items);
	}

	private FilterQuery buildSagaFilter(String sagaType, String status, String businessKey) {
		List<String> conditions = new ArrayList<>();
		List<Object> args = new ArrayList<>();

		if (sagaType != null && !sagaType.isBlank()) {
			conditions.add("saga_type = ?");
			args.add(sagaType.trim());
		}
		if (status != null && !status.isBlank()) {
			conditions.add("status = ?");
			args.add(status.trim());
		}
		if (businessKey != null && !businessKey.isBlank()) {
			conditions.add("business_key = ?");
			args.add(businessKey.trim());
		}

		if (conditions.isEmpty()) {
			return new FilterQuery("", args);
		}

		return new FilterQuery(" WHERE " + String.join(" AND ", conditions) + " ", args);
	}

	private SagaInstanceItem mapSagaInstanceItem(ResultSet rs, int rowNum) throws SQLException {
		return new SagaInstanceItem(
				rs.getObject("saga_instance_id", UUID.class),
				rs.getString("saga_type"),
				rs.getString("business_key"),
				rs.getString("status"),
				rs.getString("current_step"),
				rs.getLong("version"),
				readInstant(rs, "started_at"),
				readInstant(rs, "last_updated_at"));
	}

	private SagaStepItem mapSagaStepItem(ResultSet rs, int rowNum) throws SQLException {
		return new SagaStepItem(
				rs.getLong("saga_step_id"),
				rs.getInt("step_no"),
				rs.getString("step_name"),
				rs.getString("direction"),
				rs.getString("step_key"),
				rs.getString("status"),
				readInstant(rs, "started_at"),
				readInstant(rs, "finished_at"));
	}

	private Instant readInstant(ResultSet rs, String column) throws SQLException {
		java.sql.Timestamp timestamp = rs.getTimestamp(column);
		return timestamp == null ? null : timestamp.toInstant();
	}

	private record FilterQuery(String whereClause, List<Object> args) {
	}

	public record SagaInstancePage(
			int page,
			int size,
			long totalItems,
			List<SagaInstanceItem> items) {
	}

	public record SagaInstanceItem(
			UUID sagaInstanceId,
			String sagaType,
			String businessKey,
			String status,
			String currentStep,
			long version,
			Instant startedAt,
			Instant lastUpdatedAt) {
	}

	public record SagaStepPage(
			UUID sagaInstanceId,
			int limit,
			List<SagaStepItem> items) {
	}

	public record SagaStepItem(
			long sagaStepId,
			int stepNo,
			String stepName,
			String direction,
			String stepKey,
			String status,
			Instant startedAt,
			Instant finishedAt) {
	}
}
