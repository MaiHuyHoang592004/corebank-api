package com.corebank.corebank_api.ops.system;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.corebank.corebank_api.TestcontainersConfiguration;
import com.corebank.corebank_api.integration.OutboxService;
import com.corebank.corebank_api.ops.approval.ApprovalService;
import com.corebank.corebank_api.reporting.OutboxReportingService;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.server.ResponseStatusException;

@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = "spring.task.scheduling.enabled=false")
class OpsRuntimeModeServiceBackstopIntegrationTest {

	@Autowired
	private OutboxReportingService outboxReportingService;

	@Autowired
	private ApprovalService approvalService;

	@Autowired
	private OutboxService outboxService;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@BeforeEach
	void setUp() {
		jdbcTemplate.update("DELETE FROM approvals");
		jdbcTemplate.update("DELETE FROM outbox_dead_letters");
		jdbcTemplate.update("DELETE FROM outbox_events");
		setRuntimeMode("RUNNING");
	}

	@Test
	void outboxServiceRequeueDeadLetter_returnsConflictWhenModeIsNotRunning() {
		Long eventId = seedDeadLetterOutboxEvent("FAILED_SERVICE_GUARD_SINGLE_EVT");
		setRuntimeMode("READ_ONLY");

		ResponseStatusException exception = assertThrows(
				ResponseStatusException.class,
				() -> outboxReportingService.requeueDeadLetter(eventId, "ops-script"));
		assertEquals(HttpStatus.CONFLICT, exception.getStatusCode());
		assertTrue(exception.getReason().contains("RUNNING"));

		Map<String, Object> outboxRow = jdbcTemplate.queryForMap(
				"SELECT status, retry_count FROM outbox_events WHERE id = ?",
				eventId);
		assertEquals("FAILED", outboxRow.get("status"));
		assertEquals(3, ((Number) outboxRow.get("retry_count")).intValue());

		Integer deadLetterCount = jdbcTemplate.queryForObject(
				"SELECT COUNT(*) FROM outbox_dead_letters WHERE outbox_event_id = ?",
				Integer.class,
				eventId);
		assertEquals(1, deadLetterCount);
	}

	@Test
	void outboxServiceRequeueDeadLettersBulk_returnsConflictWhenModeIsNotRunning() {
		Long eventId = seedDeadLetterOutboxEvent("FAILED_SERVICE_GUARD_BULK_EVT");
		setRuntimeMode("MAINTENANCE");

		ResponseStatusException exception = assertThrows(
				ResponseStatusException.class,
				() -> outboxReportingService.requeueDeadLetters(List.of(eventId), "ops-script"));
		assertEquals(HttpStatus.CONFLICT, exception.getStatusCode());
		assertTrue(exception.getReason().contains("RUNNING"));

		Map<String, Object> outboxRow = jdbcTemplate.queryForMap(
				"SELECT status, retry_count FROM outbox_events WHERE id = ?",
				eventId);
		assertEquals("FAILED", outboxRow.get("status"));
		assertEquals(3, ((Number) outboxRow.get("retry_count")).intValue());

		Integer deadLetterCount = jdbcTemplate.queryForObject(
				"SELECT COUNT(*) FROM outbox_dead_letters WHERE outbox_event_id = ?",
				Integer.class,
				eventId);
		assertEquals(1, deadLetterCount);
	}

	@Test
	void approvalServiceClaimApprovedExecution_returnsConflictWhenModeIsNotRunning() {
		ApprovalService.ApprovalView created = approvalService.createApproval(new ApprovalService.CreateApprovalCommand(
				"OPERATION",
				"service-guard-ref-" + UUID.randomUUID(),
				"OUTBOX_BULK_REQUEUE",
				Map.of("outboxEventIds", List.of(1001L)),
				null,
				"maker-service"));

		ApprovalService.ApprovalView approved = approvalService.approve(
				created.approvalId(),
				"checker-service",
				"approved for execution");
		assertEquals("APPROVED", approved.status());
		assertEquals("NOT_EXECUTED", approved.executionStatus());

		setRuntimeMode("MAINTENANCE");
		ResponseStatusException exception = assertThrows(
				ResponseStatusException.class,
				() -> approvalService.claimApprovedExecution(
						approved.approvalId(),
						"ops-service",
						"OUTBOX_BULK_REQUEUE"));
		assertEquals(HttpStatus.CONFLICT, exception.getStatusCode());
		assertTrue(exception.getReason().contains("RUNNING"));

		Map<String, Object> approvalRow = jdbcTemplate.queryForMap(
				"SELECT status, execution_status, executed_by, executed_at FROM approvals WHERE approval_id = ?",
				approved.approvalId());
		assertEquals("APPROVED", approvalRow.get("status"));
		assertEquals("NOT_EXECUTED", approvalRow.get("execution_status"));
		assertNull(approvalRow.get("executed_by"));
		assertNull(approvalRow.get("executed_at"));
	}

	private Long seedDeadLetterOutboxEvent(String eventType) {
		outboxService.appendMessage(
				"OPS_TEST",
				"service-backstop-" + eventType,
				eventType,
				Map.of("amountMinor", 1234L));

		jdbcTemplate.update(
				"""
				UPDATE outbox_events
				SET status = 'FAILED',
				    retry_count = 3,
				    processed_at = CURRENT_TIMESTAMP,
				    last_error = 'exhausted'
				WHERE event_type = ?
				""",
				eventType);

		jdbcTemplate.update(
				"""
				INSERT INTO outbox_dead_letters (
				    outbox_event_id,
				    aggregate_type,
				    aggregate_id,
				    event_type,
				    event_data,
				    retry_count,
				    last_error,
				    dead_lettered_at
				)
				SELECT id,
				       aggregate_type,
				       aggregate_id,
				       event_type,
				       event_data,
				       retry_count,
				       last_error,
				       now()
				FROM outbox_events
				WHERE event_type = ?
				""",
				eventType);

		return jdbcTemplate.queryForObject(
				"SELECT id FROM outbox_events WHERE event_type = ?",
				Long.class,
				eventType);
	}

	private void setRuntimeMode(String status) {
		jdbcTemplate.update(
				"""
				UPDATE system_configs
				SET config_value = jsonb_set(config_value, '{status}', to_jsonb(?::text)),
				    updated_at = now(),
				    updated_by = 'test-suite'
				WHERE config_key = 'runtime_mode'
				""",
				status);
	}
}
