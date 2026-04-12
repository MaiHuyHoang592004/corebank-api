package com.corebank.corebank_api.integration.saga;

import com.corebank.corebank_api.common.CoreBankException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class SagaStateService {

	public static final String STATUS_PENDING = "PENDING";
	public static final String STATUS_RUNNING = "RUNNING";
	public static final String STATUS_COMPLETED = "COMPLETED";
	public static final String STATUS_FAILED = "FAILED";
	public static final String STATUS_COMPENSATING = "COMPENSATING";
	public static final String STATUS_COMPENSATED = "COMPENSATED";

	private final JdbcTemplate jdbcTemplate;

	public SagaStateService(JdbcTemplate jdbcTemplate) {
		this.jdbcTemplate = jdbcTemplate;
	}

	@Transactional
	public SagaInstance startSaga(String sagaType, String businessKey, String initialStateJson) {
		jdbcTemplate.update(
				"""
				INSERT INTO saga_instances (
				    saga_type,
				    business_key,
				    status,
				    context_json,
				    version
				) VALUES (?, ?, ?, CAST(? AS jsonb), 0)
				ON CONFLICT (saga_type, business_key) DO NOTHING
				""",
				sagaType,
				businessKey,
				STATUS_PENDING,
				normalizeJson(initialStateJson));

		return findByBusinessKey(sagaType, businessKey)
				.orElseThrow(() -> new CoreBankException("Saga cannot be loaded after start"));
	}

	@Transactional
	public SagaInstance updateSagaState(
			UUID sagaInstanceId,
			long expectedVersion,
			String nextStatus,
			String currentStep,
			String contextJson,
			String errorJson) {
		int updated = jdbcTemplate.update(
				"""
				UPDATE saga_instances
				SET status = ?,
				    current_step = ?,
				    context_json = CAST(? AS jsonb),
				    error_json = CAST(? AS jsonb),
				    version = version + 1,
				    last_updated_at = CURRENT_TIMESTAMP
				WHERE saga_instance_id = ?
				  AND version = ?
				""",
				nextStatus,
				currentStep,
				normalizeJson(contextJson),
				nullableJson(errorJson),
				sagaInstanceId,
				expectedVersion);

		if (updated != 1) {
			throw new CoreBankException("Saga state update rejected due to stale version or missing saga");
		}

		return findById(sagaInstanceId).orElseThrow(() -> new CoreBankException("Updated saga cannot be loaded"));
	}

	@Transactional
	public boolean appendStepLog(
			UUID sagaInstanceId,
			int stepNo,
			String stepName,
			String direction,
			String stepKey,
			String status,
			String requestPayloadJson,
			String responsePayloadJson,
			String errorJson) {
		int inserted = jdbcTemplate.update(
				"""
				INSERT INTO saga_steps (
				    saga_instance_id,
				    step_no,
				    step_name,
				    direction,
				    step_key,
				    status,
				    started_at,
				    finished_at,
				    request_payload_json,
				    response_payload_json,
				    error_json
				) VALUES (
				    ?, ?, ?, ?, ?, ?,
				    CURRENT_TIMESTAMP,
				    CASE WHEN ? = 'RUNNING' THEN NULL ELSE CURRENT_TIMESTAMP END,
				    CAST(? AS jsonb),
				    CAST(? AS jsonb),
				    CAST(? AS jsonb)
				)
				ON CONFLICT DO NOTHING
				""",
				sagaInstanceId,
				stepNo,
				stepName,
				direction,
				stepKey,
				status,
				status,
				nullableJson(requestPayloadJson),
				nullableJson(responsePayloadJson),
				nullableJson(errorJson));
		return inserted == 1;
	}

	public Optional<SagaInstance> findByBusinessKey(String sagaType, String businessKey) {
		List<SagaInstance> results = jdbcTemplate.query(
				"""
				SELECT saga_instance_id,
				       saga_type,
				       business_key,
				       status,
				       current_step,
				       context_json::text AS context_json,
				       error_json::text AS error_json,
				       version,
				       started_at,
				       last_updated_at
				FROM saga_instances
				WHERE saga_type = ?
				  AND business_key = ?
				""",
				this::mapSagaInstance,
				sagaType,
				businessKey);
		return results.stream().findFirst();
	}

	public Optional<SagaInstance> findById(UUID sagaId) {
		List<SagaInstance> results = jdbcTemplate.query(
				"""
				SELECT saga_instance_id,
				       saga_type,
				       business_key,
				       status,
				       current_step,
				       context_json::text AS context_json,
				       error_json::text AS error_json,
				       version,
				       started_at,
				       last_updated_at
				FROM saga_instances
				WHERE saga_instance_id = ?
				""",
				this::mapSagaInstance,
				sagaId);
		return results.stream().findFirst();
	}

	public List<SagaStepLog> findStepLogs(UUID sagaInstanceId) {
		return jdbcTemplate.query(
				"""
				SELECT saga_step_id,
				       saga_instance_id,
				       step_no,
				       step_name,
				       direction,
				       step_key,
				       status,
				       request_payload_json::text AS request_payload_json,
				       response_payload_json::text AS response_payload_json,
				       error_json::text AS error_json,
				       started_at,
				       finished_at
				FROM saga_steps
				WHERE saga_instance_id = ?
				ORDER BY saga_step_id ASC
				""",
				this::mapSagaStepLog,
				sagaInstanceId);
	}

	private SagaInstance mapSagaInstance(ResultSet rs, int rowNum) throws SQLException {
		OffsetDateTime startedAt = rs.getObject("started_at", OffsetDateTime.class);
		OffsetDateTime lastUpdatedAt = rs.getObject("last_updated_at", OffsetDateTime.class);
		return new SagaInstance(
				rs.getObject("saga_instance_id", UUID.class),
				rs.getString("saga_type"),
				rs.getString("business_key"),
				rs.getString("status"),
				rs.getString("current_step"),
				rs.getString("context_json"),
				rs.getString("error_json"),
				rs.getLong("version"),
				startedAt == null ? null : startedAt.toInstant(),
				lastUpdatedAt == null ? null : lastUpdatedAt.toInstant());
	}

	private SagaStepLog mapSagaStepLog(ResultSet rs, int rowNum) throws SQLException {
		OffsetDateTime startedAt = rs.getObject("started_at", OffsetDateTime.class);
		OffsetDateTime finishedAt = rs.getObject("finished_at", OffsetDateTime.class);
		return new SagaStepLog(
				rs.getLong("saga_step_id"),
				rs.getObject("saga_instance_id", UUID.class),
				rs.getInt("step_no"),
				rs.getString("step_name"),
				rs.getString("direction"),
				rs.getString("step_key"),
				rs.getString("status"),
				rs.getString("request_payload_json"),
				rs.getString("response_payload_json"),
				rs.getString("error_json"),
				startedAt == null ? null : startedAt.toInstant(),
				finishedAt == null ? null : finishedAt.toInstant());
	}

	private String normalizeJson(String json) {
		return (json == null || json.isBlank()) ? "{}" : json;
	}

	private String nullableJson(String json) {
		return (json == null || json.isBlank()) ? null : json;
	}

	public record SagaInstance(
			UUID sagaInstanceId,
			String sagaType,
			String businessKey,
			String status,
			String currentStep,
			String contextJson,
			String errorJson,
			long version,
			Instant startedAt,
			Instant lastUpdatedAt) {
	}

	public record SagaStepLog(
			long sagaStepId,
			UUID sagaInstanceId,
			int stepNo,
			String stepName,
			String direction,
			String stepKey,
			String status,
			String requestPayloadJson,
			String responsePayloadJson,
			String errorJson,
			Instant startedAt,
			Instant finishedAt) {
	}
}
