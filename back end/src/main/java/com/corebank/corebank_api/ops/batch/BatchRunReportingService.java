package com.corebank.corebank_api.ops.batch;

import com.corebank.corebank_api.common.CoreBankException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;

@Service
public class BatchRunReportingService {

	private static final int DEFAULT_LIMIT = 50;
	private static final int MAX_LIMIT = 200;
	private static final Set<String> ALLOWED_FILTER_STATUSES = Set.of("RUNNING", "COMPLETED", "FAILED");

	private final JdbcTemplate jdbcTemplate;
	private final RowMapper<BatchRunView> rowMapper = new BatchRunViewRowMapper();

	public BatchRunReportingService(JdbcTemplate jdbcTemplate) {
		this.jdbcTemplate = jdbcTemplate;
	}

	public BatchRunPage listBatchRuns(String batchType, String status, Integer requestedLimit) {
		int limit = resolveLimit(requestedLimit);
		String safeBatchType = normalizeBatchType(batchType);
		String safeStatus = normalizeStatus(status);

		StringBuilder sql = new StringBuilder(
				"""
				SELECT run_id, batch_name, batch_type, status,
				       started_at, completed_at, parameters_json,
				       result_json, error_message, retry_count,
				       created_at, updated_at
				FROM batch_runs
				WHERE 1 = 1
				""");
		List<Object> args = new ArrayList<>();

		if (safeBatchType != null) {
			sql.append(" AND batch_type = ?");
			args.add(safeBatchType);
		}
		if (safeStatus != null) {
			sql.append(" AND status = ?");
			args.add(safeStatus);
		}

		sql.append(" ORDER BY created_at DESC LIMIT ?");
		args.add(limit);

		List<BatchRunView> items = jdbcTemplate.query(sql.toString(), rowMapper, args.toArray());
		return new BatchRunPage(limit, items);
	}

	public Optional<BatchRunView> findBatchRun(long runId) {
		if (runId < 1) {
			throw new CoreBankException("runId must be a positive number");
		}

		List<BatchRunView> items = jdbcTemplate.query(
				"""
				SELECT run_id, batch_name, batch_type, status,
				       started_at, completed_at, parameters_json,
				       result_json, error_message, retry_count,
				       created_at, updated_at
				FROM batch_runs
				WHERE run_id = ?
				""",
				rowMapper,
				runId);
		return items.isEmpty() ? Optional.empty() : Optional.of(items.get(0));
	}

	private int resolveLimit(Integer requestedLimit) {
		if (requestedLimit == null) {
			return DEFAULT_LIMIT;
		}
		if (requestedLimit < 1 || requestedLimit > MAX_LIMIT) {
			throw new CoreBankException("limit must be between 1 and 200");
		}
		return requestedLimit;
	}

	private String normalizeBatchType(String batchType) {
		if (batchType == null) {
			return null;
		}
		String value = batchType.trim();
		return value.isEmpty() ? null : value;
	}

	private String normalizeStatus(String status) {
		if (status == null) {
			return null;
		}

		String value = status.trim();
		if (value.isEmpty()) {
			return null;
		}

		String normalized = value.toUpperCase(Locale.ROOT);
		if (!ALLOWED_FILTER_STATUSES.contains(normalized)) {
			throw new CoreBankException("status must be one of RUNNING, COMPLETED, FAILED");
		}
		return normalized;
	}

	private static class BatchRunViewRowMapper implements RowMapper<BatchRunView> {
		@Override
		public BatchRunView mapRow(ResultSet rs, int rowNum) throws SQLException {
			return new BatchRunView(
					rs.getLong("run_id"),
					rs.getString("batch_name"),
					rs.getString("batch_type"),
					rs.getString("status"),
					toInstant(rs, "started_at"),
					toInstant(rs, "completed_at"),
					rs.getString("parameters_json"),
					rs.getString("result_json"),
					rs.getString("error_message"),
					rs.getInt("retry_count"),
					toInstant(rs, "created_at"),
					toInstant(rs, "updated_at"));
		}

		private Instant toInstant(ResultSet rs, String column) throws SQLException {
			return rs.getTimestamp(column) == null ? null : rs.getTimestamp(column).toInstant();
		}
	}

	public record BatchRunPage(
			int limit,
			List<BatchRunView> items) {
	}

	public record BatchRunView(
			long runId,
			String batchName,
			String batchType,
			String status,
			Instant startedAt,
			Instant completedAt,
			String parametersJson,
			String resultJson,
			String errorMessage,
			int retryCount,
			Instant createdAt,
			Instant updatedAt) {
	}
}
