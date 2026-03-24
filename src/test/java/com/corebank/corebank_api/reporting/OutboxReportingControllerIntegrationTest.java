package com.corebank.corebank_api.reporting;

import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.corebank.corebank_api.TestcontainersConfiguration;
import com.corebank.corebank_api.integration.OutboxService;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = "spring.task.scheduling.enabled=false")
@AutoConfigureMockMvc
@WithMockUser(username = "reporter", roles = "USER")
class OutboxReportingControllerIntegrationTest {

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private OutboxService outboxService;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@BeforeEach
	void setUp() {
		jdbcTemplate.update("DELETE FROM outbox_dead_letters");
		jdbcTemplate.update("DELETE FROM outbox_events");
	}

	@Test
	void outboxSummary_returnsOperationalCounts() throws Exception {
		append("PENDING_EVT", "pending-1");
		append("PROCESSING_EVT", "processing-1");
		append("FAILED_RETRYABLE_EVT", "failed-retryable-1");
		append("FAILED_DEAD_EVT", "failed-dead-1");
		append("PROCESSED_EVT", "processed-1");

		jdbcTemplate.update(
				"UPDATE outbox_events SET status = 'PROCESSING', processing_started_at = CURRENT_TIMESTAMP WHERE event_type = 'PROCESSING_EVT'");
		jdbcTemplate.update(
				"""
				UPDATE outbox_events
				SET status = 'FAILED',
				    retry_count = 2,
				    processed_at = CURRENT_TIMESTAMP,
				    last_error = 'temporary'
				WHERE event_type = 'FAILED_RETRYABLE_EVT'
				""");
		jdbcTemplate.update(
				"""
				UPDATE outbox_events
				SET status = 'FAILED',
				    retry_count = 3,
				    processed_at = CURRENT_TIMESTAMP,
				    last_error = 'exhausted'
				WHERE event_type = 'FAILED_DEAD_EVT'
				""");
		jdbcTemplate.update(
				"UPDATE outbox_events SET status = 'PROCESSED', processed_at = CURRENT_TIMESTAMP WHERE event_type = 'PROCESSED_EVT'");
		insertDeadLetterFor("FAILED_DEAD_EVT", "2026-03-25T04:00:00Z");

		mockMvc.perform(get("/api/reporting/outbox/summary"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.pendingCount").value(1))
				.andExpect(jsonPath("$.processingCount").value(1))
				.andExpect(jsonPath("$.failedCount").value(2))
				.andExpect(jsonPath("$.processedCount").value(1))
				.andExpect(jsonPath("$.deadLetterCount").value(1))
				.andExpect(jsonPath("$.retryQueueCount").value(3))
				.andExpect(jsonPath("$.oldestRetryQueueAgeSeconds", greaterThanOrEqualTo(0)));
	}

	@Test
	void outboxDeadLetters_returnsPagedData() throws Exception {
		append("FAILED_DEAD_EVT_OLD", "failed-dead-old");
		append("FAILED_DEAD_EVT_NEW", "failed-dead-new");

		jdbcTemplate.update(
				"""
				UPDATE outbox_events
				SET status = 'FAILED',
				    retry_count = 3,
				    processed_at = CURRENT_TIMESTAMP,
				    last_error = 'exhausted-old'
				WHERE event_type = 'FAILED_DEAD_EVT_OLD'
				""");
		jdbcTemplate.update(
				"""
				UPDATE outbox_events
				SET status = 'FAILED',
				    retry_count = 3,
				    processed_at = CURRENT_TIMESTAMP,
				    last_error = 'exhausted-new'
				WHERE event_type = 'FAILED_DEAD_EVT_NEW'
				""");

		insertDeadLetterFor("FAILED_DEAD_EVT_OLD", "2026-03-25T04:00:00Z");
		insertDeadLetterFor("FAILED_DEAD_EVT_NEW", "2026-03-25T04:05:00Z");

		mockMvc.perform(get("/api/reporting/outbox/dead-letters")
						.queryParam("limit", "1"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.limit").value(1))
				.andExpect(jsonPath("$.items.length()").value(1))
				.andExpect(jsonPath("$.items[0].eventType").value("FAILED_DEAD_EVT_NEW"))
				.andExpect(jsonPath("$.items[0].retryCount").value(3));

		mockMvc.perform(get("/api/reporting/outbox/dead-letters")
						.queryParam("limit", "10"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.items.length()").value(2))
				.andExpect(jsonPath("$.items[*].eventType",
						containsInAnyOrder("FAILED_DEAD_EVT_OLD", "FAILED_DEAD_EVT_NEW")));
	}

	@Test
	void requeueDeadLetter_requeuesFailedEventAndWritesAudit() throws Exception {
		append("FAILED_DEAD_REQUEUE_EVT", "failed-dead-requeue-1");
		jdbcTemplate.update(
				"""
				UPDATE outbox_events
				SET status = 'FAILED',
				    retry_count = 3,
				    processed_at = CURRENT_TIMESTAMP,
				    last_error = 'exhausted'
				WHERE event_type = 'FAILED_DEAD_REQUEUE_EVT'
				""");
		insertDeadLetterFor("FAILED_DEAD_REQUEUE_EVT", "2026-03-25T04:10:00Z");

		Long eventId = jdbcTemplate.queryForObject(
				"SELECT id FROM outbox_events WHERE event_type = ?",
				Long.class,
				"FAILED_DEAD_REQUEUE_EVT");

		mockMvc.perform(post("/api/reporting/outbox/dead-letters/{outboxEventId}/requeue", eventId)
						.with(csrf()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.outboxEventId").value(eventId))
				.andExpect(jsonPath("$.status").value("REQUEUED"));

		Map<String, Object> outboxRow = jdbcTemplate.queryForMap(
				"""
				SELECT status,
				       retry_count,
				       processed_at,
				       processing_started_at,
				       last_error
				FROM outbox_events
				WHERE id = ?
				""",
				eventId);
		assertEquals("PENDING", outboxRow.get("status"));
		assertEquals(0, ((Number) outboxRow.get("retry_count")).intValue());
		assertNull(outboxRow.get("processed_at"));
		assertNull(outboxRow.get("processing_started_at"));
		assertNull(outboxRow.get("last_error"));

		Integer deadLetterCount = jdbcTemplate.queryForObject(
				"SELECT COUNT(*) FROM outbox_dead_letters WHERE outbox_event_id = ?",
				Integer.class,
				eventId);
		assertEquals(0, deadLetterCount);

		Integer auditCount = jdbcTemplate.queryForObject(
				"""
				SELECT COUNT(*)
				FROM audit_events
				WHERE action = 'OUTBOX_DEAD_LETTER_REQUEUED'
				  AND resource_type = 'OUTBOX_EVENT'
				  AND resource_id = ?
				""",
				Integer.class,
				String.valueOf(eventId));
		assertEquals(1, auditCount);
	}

	@Test
	void requeueDeadLetter_returnsNotFoundWhenDeadLetterDoesNotExist() throws Exception {
		mockMvc.perform(post("/api/reporting/outbox/dead-letters/{outboxEventId}/requeue", 987654321L)
						.with(csrf()))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.status").value("NOT_FOUND"));
	}

	@Test
	void requeueDeadLetter_returnsConflictWhenEventNotFailed() throws Exception {
		append("FAILED_DEAD_CONFLICT_EVT", "failed-dead-conflict-1");

		jdbcTemplate.update(
				"""
				UPDATE outbox_events
				SET status = 'FAILED',
				    retry_count = 3,
				    processed_at = CURRENT_TIMESTAMP,
				    last_error = 'exhausted'
				WHERE event_type = 'FAILED_DEAD_CONFLICT_EVT'
				""");
		insertDeadLetterFor("FAILED_DEAD_CONFLICT_EVT", "2026-03-25T04:20:00Z");
		jdbcTemplate.update(
				"UPDATE outbox_events SET status = 'PROCESSED' WHERE event_type = 'FAILED_DEAD_CONFLICT_EVT'");

		Long eventId = jdbcTemplate.queryForObject(
				"SELECT id FROM outbox_events WHERE event_type = ?",
				Long.class,
				"FAILED_DEAD_CONFLICT_EVT");

		mockMvc.perform(post("/api/reporting/outbox/dead-letters/{outboxEventId}/requeue", eventId)
						.with(csrf()))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.status").value("CONFLICT"));

		Map<String, Object> outboxRow = jdbcTemplate.queryForMap(
				"SELECT status, retry_count FROM outbox_events WHERE id = ?",
				eventId);
		assertEquals("PROCESSED", outboxRow.get("status"));
		assertEquals(3, ((Number) outboxRow.get("retry_count")).intValue());

		Integer deadLetterCount = jdbcTemplate.queryForObject(
				"SELECT COUNT(*) FROM outbox_dead_letters WHERE outbox_event_id = ?",
				Integer.class,
				eventId);
		assertEquals(1, deadLetterCount);
	}

	private void append(String eventType, String aggregateId) {
		outboxService.appendMessage(
				"OPS_TEST",
				aggregateId,
				eventType,
				Map.of("marker", eventType));
	}

	private void insertDeadLetterFor(String eventType, String deadLetteredAtIso8601) {
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
				       CAST(? AS TIMESTAMP WITH TIME ZONE)
				FROM outbox_events
				WHERE event_type = ?
				ON CONFLICT (outbox_event_id) DO NOTHING
				""",
				deadLetteredAtIso8601,
				eventType);
	}
}
