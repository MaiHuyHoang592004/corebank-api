package com.corebank.corebank_api.ops.batch;

import com.corebank.corebank_api.common.CoreBankException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Service to manage batch run registry.
 *
 * <p>Tracks batch job execution status and supports idempotent execution.</p>
 */
@Service
public class BatchRunService {

	private final JdbcTemplate jdbcTemplate;

	public BatchRunService(JdbcTemplate jdbcTemplate) {
		this.jdbcTemplate = jdbcTemplate;
	}

	/**
	 * Start a new batch run.
	 *
	 * @param batchName batch name
	 * @param batchType batch type (EOD, BOD, ACCRUAL, SNAPSHOT, RECONCILIATION)
	 * @param parametersJson parameters JSON
	 * @return run ID
	 */
	@Transactional
	public long startBatch(String batchName, String batchType, String parametersJson) {
		// Check if batch is already running
		if (isBatchRunning(batchName)) {
			throw new CoreBankException("Batch '" + batchName + "' is already running");
		}

		return jdbcTemplate.queryForObject(
				"""
				INSERT INTO batch_runs (batch_name, batch_type, status, started_at, parameters_json)
				VALUES (?, ?, 'RUNNING', now(), ?::jsonb)
				RETURNING run_id
				""",
				Long.class,
				batchName,
				batchType,
				parametersJson);
	}

	/**
	 * Complete a batch run.
	 *
	 * @param runId run ID
	 * @param resultJson result JSON
	 */
	@Transactional
	public void completeBatch(long runId, String resultJson) {
		jdbcTemplate.update(
				"""
				UPDATE batch_runs
				SET status = 'COMPLETED',
				    completed_at = now(),
				    result_json = ?::jsonb,
				    updated_at = now()
				WHERE run_id = ?
				  AND status = 'RUNNING'
				""",
				resultJson,
				runId);
	}

	/**
	 * Fail a batch run.
	 *
	 * @param runId run ID
	 * @param errorMessage error message
	 */
	@Transactional
	public void failBatch(long runId, String errorMessage) {
		jdbcTemplate.update(
				"""
				UPDATE batch_runs
				SET status = 'FAILED',
				    completed_at = now(),
				    error_message = ?,
				    updated_at = now()
				WHERE run_id = ?
				  AND status = 'RUNNING'
				""",
				errorMessage,
				runId);
	}

	/**
	 * Get batch status.
	 *
	 * @param runId run ID
	 * @return batch run or empty if not found
	 */
	public Optional<BatchRun> getBatchStatus(long runId) {
		List<BatchRun> runs = jdbcTemplate.query(
				"""
				SELECT run_id, batch_name, batch_type, status,
				       started_at, completed_at, parameters_json,
				       result_json, error_message, retry_count,
				       created_at, updated_at
				FROM batch_runs
				WHERE run_id = ?
				""",
				new BatchRunRowMapper(),
				runId);

		return runs.isEmpty() ? Optional.empty() : Optional.of(runs.get(0));
	}

	/**
	 * Check if batch is running.
	 *
	 * @param batchName batch name
	 * @return true if batch is running
	 */
	public boolean isBatchRunning(String batchName) {
		Integer count = jdbcTemplate.queryForObject(
				"SELECT COUNT(*) FROM batch_runs WHERE batch_name = ? AND status = 'RUNNING'",
				Integer.class,
				batchName);

		return count != null && count > 0;
	}

	/**
	 * Get batches by status.
	 *
	 * @param status batch status
	 * @param limit max number of records
	 * @return list of batch runs
	 */
	public List<BatchRun> getBatchesByStatus(String status, int limit) {
		return jdbcTemplate.query(
				"""
				SELECT run_id, batch_name, batch_type, status,
				       started_at, completed_at, parameters_json,
				       result_json, error_message, retry_count,
				       created_at, updated_at
				FROM batch_runs
				WHERE status = ?
				ORDER BY created_at DESC
				LIMIT ?
				""",
				new BatchRunRowMapper(),
				status,
				limit);
	}

	/**
	 * Get batches by name.
	 *
	 * @param batchName batch name
	 * @param limit max number of records
	 * @return list of batch runs
	 */
	public List<BatchRun> getBatchesByName(String batchName, int limit) {
		return jdbcTemplate.query(
				"""
				SELECT run_id, batch_name, batch_type, status,
				       started_at, completed_at, parameters_json,
				       result_json, error_message, retry_count,
				       created_at, updated_at
				FROM batch_runs
				WHERE batch_name = ?
				ORDER BY created_at DESC
				LIMIT ?
				""",
				new BatchRunRowMapper(),
				batchName,
				limit);
	}

	/**
	 * Increment retry count.
	 *
	 * @param runId run ID
	 */
	@Transactional
	public void incrementRetryCount(long runId) {
		jdbcTemplate.update(
				"""
				UPDATE batch_runs
				SET retry_count = retry_count + 1,
				    updated_at = now()
				WHERE run_id = ?
				""",
				runId);
	}

	// Row Mapper
	private static class BatchRunRowMapper implements RowMapper<BatchRun> {
		@Override
		public BatchRun mapRow(ResultSet rs, int rowNum) throws SQLException {
			return new BatchRun(
					rs.getLong("run_id"),
					rs.getString("batch_name"),
					rs.getString("batch_type"),
					rs.getString("status"),
					rs.getTimestamp("started_at") != null ? rs.getTimestamp("started_at").toInstant() : null,
					rs.getTimestamp("completed_at") != null ? rs.getTimestamp("completed_at").toInstant() : null,
					rs.getString("parameters_json"),
					rs.getString("result_json"),
					rs.getString("error_message"),
					rs.getInt("retry_count"),
					rs.getTimestamp("created_at").toInstant(),
					rs.getTimestamp("updated_at").toInstant());
		}
	}

	// Record definition
	public record BatchRun(
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