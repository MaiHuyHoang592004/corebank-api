package com.corebank.corebank_api.ops.exception;

import com.corebank.corebank_api.common.CoreBankException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Service to manage exception queue for failed operations.
 *
 * <p>Stores failed operations for manual review and retry by operators.</p>
 */
@Service
public class ExceptionQueueService {

	private final JdbcTemplate jdbcTemplate;

	public ExceptionQueueService(JdbcTemplate jdbcTemplate) {
		this.jdbcTemplate = jdbcTemplate;
	}

	/**
	 * Add exception to queue.
	 *
	 * @param exceptionType exception type (e.g., PAYMENT_FAILED, TRANSFER_FAILED)
	 * @param errorMessage error message
	 * @param errorDetail detailed error information
	 * @param sourceService source service name
	 * @param sourceOperation source operation name
	 * @param payloadJson payload JSON for retry
	 * @return exception ID
	 */
	@Transactional
	public long addException(
			String exceptionType,
			String errorMessage,
			String errorDetail,
			String sourceService,
			String sourceOperation,
			String payloadJson) {

		return jdbcTemplate.queryForObject(
				"""
				INSERT INTO exception_queue (
				    exception_type, error_message, error_detail,
				    source_service, source_operation, payload_json,
				    status, retry_count, max_retries
				) VALUES (?, ?, ?, ?, ?, ?::jsonb, 'PENDING', 0, 3)
				RETURNING exception_id
				""",
				Long.class,
				exceptionType,
				errorMessage,
				errorDetail,
				sourceService,
				sourceOperation,
				payloadJson);
	}

	/**
	 * Get exception by ID.
	 *
	 * @param exceptionId exception ID
	 * @return exception or empty if not found
	 */
	public Optional<ExceptionRecord> getException(long exceptionId) {
		List<ExceptionRecord> records = jdbcTemplate.query(
				"""
				SELECT exception_id, exception_type, error_message, error_detail,
				       source_service, source_operation, payload_json,
				       status, retry_count, max_retries,
				       resolved_by, resolved_at, resolution_note,
				       created_at, updated_at
				FROM exception_queue
				WHERE exception_id = ?
				""",
				new ExceptionRowMapper(),
				exceptionId);

		return records.isEmpty() ? Optional.empty() : Optional.of(records.get(0));
	}

	/**
	 * Get exceptions by status.
	 *
	 * @param status exception status
	 * @param limit max number of records
	 * @return list of exceptions
	 */
	public List<ExceptionRecord> getExceptionsByStatus(String status, int limit) {
		return jdbcTemplate.query(
				"""
				SELECT exception_id, exception_type, error_message, error_detail,
				       source_service, source_operation, payload_json,
				       status, retry_count, max_retries,
				       resolved_by, resolved_at, resolution_note,
				       created_at, updated_at
				FROM exception_queue
				WHERE status = ?
				ORDER BY created_at DESC
				LIMIT ?
				""",
				new ExceptionRowMapper(),
				status,
				limit);
	}

	/**
	 * Get pending exceptions that can be retried.
	 *
	 * @param limit max number of records
	 * @return list of retryable exceptions
	 */
	public List<ExceptionRecord> getRetryableExceptions(int limit) {
		return jdbcTemplate.query(
				"""
				SELECT exception_id, exception_type, error_message, error_detail,
				       source_service, source_operation, payload_json,
				       status, retry_count, max_retries,
				       resolved_by, resolved_at, resolution_note,
				       created_at, updated_at
				FROM exception_queue
				WHERE status = 'PENDING'
				  AND retry_count < max_retries
				ORDER BY created_at ASC
				LIMIT ?
				""",
				new ExceptionRowMapper(),
				limit);
	}

	/**
	 * Mark exception as in progress.
	 *
	 * @param exceptionId exception ID
	 */
	@Transactional
	public void markInProgress(long exceptionId) {
		jdbcTemplate.update(
				"""
				UPDATE exception_queue
				SET status = 'IN_PROGRESS',
				    updated_at = now()
				WHERE exception_id = ?
				  AND status = 'PENDING'
				""",
				exceptionId);
	}

	/**
	 * Mark exception as resolved.
	 *
	 * @param exceptionId exception ID
	 * @param resolvedBy operator who resolved
	 * @param resolutionNote resolution note
	 */
	@Transactional
	public void markResolved(long exceptionId, String resolvedBy, String resolutionNote) {
		jdbcTemplate.update(
				"""
				UPDATE exception_queue
				SET status = 'RESOLVED',
				    resolved_by = ?,
				    resolved_at = now(),
				    resolution_note = ?,
				    updated_at = now()
				WHERE exception_id = ?
				""",
				resolvedBy,
				resolutionNote,
				exceptionId);
	}

	/**
	 * Mark exception as retried (for successful retry).
	 *
	 * @param exceptionId exception ID
	 */
	@Transactional
	public void markRetried(long exceptionId) {
		jdbcTemplate.update(
				"""
				UPDATE exception_queue
				SET status = 'RETRIED',
				    retry_count = retry_count + 1,
				    updated_at = now()
				WHERE exception_id = ?
				""",
				exceptionId);
	}

	/**
	 * Increment retry count for failed retry.
	 *
	 * @param exceptionId exception ID
	 */
	@Transactional
	public void incrementRetryCount(long exceptionId) {
		jdbcTemplate.update(
				"""
				UPDATE exception_queue
				SET retry_count = retry_count + 1,
				    updated_at = now()
				WHERE exception_id = ?
				""",
				exceptionId);
	}

	/**
	 * Mark exception as ignored.
	 *
	 * @param exceptionId exception ID
	 * @param resolvedBy operator who ignored
	 * @param resolutionNote reason for ignoring
	 */
	@Transactional
	public void markIgnored(long exceptionId, String resolvedBy, String resolutionNote) {
		jdbcTemplate.update(
				"""
				UPDATE exception_queue
				SET status = 'IGNORED',
				    resolved_by = ?,
				    resolved_at = now(),
				    resolution_note = ?,
				    updated_at = now()
				WHERE exception_id = ?
				""",
				resolvedBy,
				resolutionNote,
				exceptionId);
	}

	/**
	 * Get exception count by status.
	 *
	 * @param status exception status
	 * @return count of exceptions
	 */
	public long getExceptionCountByStatus(String status) {
		return jdbcTemplate.queryForObject(
				"SELECT COUNT(*) FROM exception_queue WHERE status = ?",
				Long.class,
				status);
	}

	// Row Mapper
	private static class ExceptionRowMapper implements RowMapper<ExceptionRecord> {
		@Override
		public ExceptionRecord mapRow(ResultSet rs, int rowNum) throws SQLException {
			return new ExceptionRecord(
					rs.getLong("exception_id"),
					rs.getString("exception_type"),
					rs.getString("error_message"),
					rs.getString("error_detail"),
					rs.getString("source_service"),
					rs.getString("source_operation"),
					rs.getString("payload_json"),
					rs.getString("status"),
					rs.getInt("retry_count"),
					rs.getInt("max_retries"),
					rs.getString("resolved_by"),
					rs.getTimestamp("resolved_at") != null ? rs.getTimestamp("resolved_at").toInstant() : null,
					rs.getString("resolution_note"),
					rs.getTimestamp("created_at").toInstant(),
					rs.getTimestamp("updated_at").toInstant());
		}
	}

	// Record definition
	public record ExceptionRecord(
			long exceptionId,
			String exceptionType,
			String errorMessage,
			String errorDetail,
			String sourceService,
			String sourceOperation,
			String payloadJson,
			String status,
			int retryCount,
			int maxRetries,
			String resolvedBy,
			Instant resolvedAt,
			String resolutionNote,
			Instant createdAt,
			Instant updatedAt) {
	}
}