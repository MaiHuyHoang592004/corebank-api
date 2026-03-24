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
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
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

	private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

	@BeforeEach
	void setUp() {
		jdbcTemplate.update("DELETE FROM outbox_dead_letters");
		jdbcTemplate.update("DELETE FROM outbox_events");
	}

	@Test
	void outboxSummary_returnsOperationalCounts() throws Exception {
		append("OPS_TEST", "PENDING_EVT", "pending-1");
		append("OPS_TEST", "PROCESSING_EVT", "processing-1");
		append("OPS_TEST", "FAILED_RETRYABLE_EVT", "failed-retryable-1");
		append("OPS_TEST", "FAILED_DEAD_EVT", "failed-dead-1");
		append("OPS_TEST", "PROCESSED_EVT", "processed-1");

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
		append("OPS_TEST", "FAILED_DEAD_EVT_OLD", "failed-dead-old");
		append("OPS_TEST", "FAILED_DEAD_EVT_NEW", "failed-dead-new");

		markAsFailed("FAILED_DEAD_EVT_OLD", 3, "exhausted-old");
		markAsFailed("FAILED_DEAD_EVT_NEW", 3, "exhausted-new");

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
	void outboxDeadLetters_filtersByEventAggregateAndTimeWindow() throws Exception {
		append("TRANSFER", "FAILED_TRANSFER_EVT", "dead-transfer");
		append("PAYMENT", "FAILED_PAYMENT_EVT", "dead-payment");

		markAsFailed("FAILED_TRANSFER_EVT", 3, "transfer-exhausted");
		markAsFailed("FAILED_PAYMENT_EVT", 3, "payment-exhausted");

		insertDeadLetterFor("FAILED_TRANSFER_EVT", "2026-03-25T04:02:00Z");
		insertDeadLetterFor("FAILED_PAYMENT_EVT", "2026-03-25T04:07:00Z");

		mockMvc.perform(get("/api/reporting/outbox/dead-letters")
						.queryParam("limit", "20")
						.queryParam("eventType", "FAILED_PAYMENT_EVT")
						.queryParam("aggregateType", "PAYMENT")
						.queryParam("fromDeadLetteredAt", "2026-03-25T04:05:00Z")
						.queryParam("toDeadLetteredAt", "2026-03-25T04:10:00Z"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.items.length()").value(1))
				.andExpect(jsonPath("$.items[0].eventType").value("FAILED_PAYMENT_EVT"))
				.andExpect(jsonPath("$.items[0].aggregateType").value("PAYMENT"));
	}

	@Test
	void outboxDeadLetters_returnsBadRequestWhenTimeRangeInvalid() throws Exception {
		mockMvc.perform(get("/api/reporting/outbox/dead-letters")
						.queryParam("fromDeadLetteredAt", "2026-03-25T04:10:00Z")
						.queryParam("toDeadLetteredAt", "2026-03-25T04:05:00Z"))
				.andExpect(status().isBadRequest());
	}

	@Test
	void requeueDeadLetter_forbiddenForUserRole() throws Exception {
		mockMvc.perform(post("/api/reporting/outbox/dead-letters/{outboxEventId}/requeue", 12345L)
						.with(csrf()))
				.andExpect(status().isForbidden());
	}

	@Test
	void bulkRequeueDeadLetters_forbiddenForUserRole() throws Exception {
		String body = objectMapper.writeValueAsString(Map.of("outboxEventIds", List.of(1L, 2L)));

		mockMvc.perform(post("/api/reporting/outbox/dead-letters/requeue-bulk")
						.with(csrf())
						.contentType("application/json")
						.content(body))
				.andExpect(status().isForbidden());
	}

	@Test
	@WithMockUser(username = "ops-user", roles = "OPS")
	void requeueDeadLetter_requeuesFailedEventAndWritesAudit() throws Exception {
		append("OPS_TEST", "FAILED_DEAD_REQUEUE_EVT", "failed-dead-requeue-1");
		markAsFailed("FAILED_DEAD_REQUEUE_EVT", 3, "exhausted");
		insertDeadLetterFor("FAILED_DEAD_REQUEUE_EVT", "2026-03-25T04:10:00Z");

		Long eventId = eventIdByType("FAILED_DEAD_REQUEUE_EVT");

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
	@WithMockUser(username = "admin-user", roles = "ADMIN")
	void requeueDeadLetter_allowsAdminRole() throws Exception {
		append("OPS_TEST", "FAILED_DEAD_ADMIN_EVT", "failed-dead-admin-1");
		markAsFailed("FAILED_DEAD_ADMIN_EVT", 3, "exhausted-admin");
		insertDeadLetterFor("FAILED_DEAD_ADMIN_EVT", "2026-03-25T04:11:00Z");

		Long eventId = eventIdByType("FAILED_DEAD_ADMIN_EVT");

		mockMvc.perform(post("/api/reporting/outbox/dead-letters/{outboxEventId}/requeue", eventId)
						.with(csrf()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("REQUEUED"));
	}

	@Test
	@WithMockUser(username = "ops-user", roles = "OPS")
	void requeueDeadLetter_returnsNotFoundWhenDeadLetterDoesNotExist() throws Exception {
		mockMvc.perform(post("/api/reporting/outbox/dead-letters/{outboxEventId}/requeue", 987654321L)
						.with(csrf()))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.status").value("NOT_FOUND"));
	}

	@Test
	@WithMockUser(username = "ops-user", roles = "OPS")
	void requeueDeadLetter_returnsConflictWhenEventNotFailed() throws Exception {
		append("OPS_TEST", "FAILED_DEAD_CONFLICT_EVT", "failed-dead-conflict-1");

		markAsFailed("FAILED_DEAD_CONFLICT_EVT", 3, "exhausted");
		insertDeadLetterFor("FAILED_DEAD_CONFLICT_EVT", "2026-03-25T04:20:00Z");
		jdbcTemplate.update(
				"UPDATE outbox_events SET status = 'PROCESSED' WHERE event_type = 'FAILED_DEAD_CONFLICT_EVT'");

		Long eventId = eventIdByType("FAILED_DEAD_CONFLICT_EVT");

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

	@Test
	@WithMockUser(username = "ops-user", roles = "OPS")
	void bulkRequeueDeadLetters_returnsMixedResultSummary() throws Exception {
		append("OPS_TEST", "FAILED_DEAD_BULK_OK_EVT", "failed-dead-bulk-ok");
		markAsFailed("FAILED_DEAD_BULK_OK_EVT", 3, "exhausted-ok");
		insertDeadLetterFor("FAILED_DEAD_BULK_OK_EVT", "2026-03-25T04:30:00Z");
		Long requeueId = eventIdByType("FAILED_DEAD_BULK_OK_EVT");

		append("OPS_TEST", "FAILED_DEAD_BULK_CONFLICT_EVT", "failed-dead-bulk-conflict");
		markAsFailed("FAILED_DEAD_BULK_CONFLICT_EVT", 3, "exhausted-conflict");
		insertDeadLetterFor("FAILED_DEAD_BULK_CONFLICT_EVT", "2026-03-25T04:31:00Z");
		jdbcTemplate.update(
				"UPDATE outbox_events SET status = 'PROCESSED' WHERE event_type = 'FAILED_DEAD_BULK_CONFLICT_EVT'");
		Long conflictId = eventIdByType("FAILED_DEAD_BULK_CONFLICT_EVT");

		Long missingId = 90909090L;
		String body = objectMapper.writeValueAsString(
				Map.of("outboxEventIds", List.of(requeueId, missingId, conflictId, requeueId)));

		mockMvc.perform(post("/api/reporting/outbox/dead-letters/requeue-bulk")
						.with(csrf())
						.contentType("application/json")
						.content(body))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.requestedCount").value(3))
				.andExpect(jsonPath("$.requeuedCount").value(1))
				.andExpect(jsonPath("$.notFoundCount").value(1))
				.andExpect(jsonPath("$.conflictCount").value(1))
				.andExpect(jsonPath("$.items.length()").value(3))
				.andExpect(jsonPath("$.items[*].status",
						containsInAnyOrder("REQUEUED", "NOT_FOUND", "CONFLICT")));

		Map<String, Object> requeuedRow = jdbcTemplate.queryForMap(
				"SELECT status, retry_count FROM outbox_events WHERE id = ?",
				requeueId);
		assertEquals("PENDING", requeuedRow.get("status"));
		assertEquals(0, ((Number) requeuedRow.get("retry_count")).intValue());

		Map<String, Object> conflictRow = jdbcTemplate.queryForMap(
				"SELECT status, retry_count FROM outbox_events WHERE id = ?",
				conflictId);
		assertEquals("PROCESSED", conflictRow.get("status"));
		assertEquals(3, ((Number) conflictRow.get("retry_count")).intValue());

		Integer requeueAuditCount = jdbcTemplate.queryForObject(
				"""
				SELECT COUNT(*)
				FROM audit_events
				WHERE action = 'OUTBOX_DEAD_LETTER_REQUEUED'
				  AND resource_type = 'OUTBOX_EVENT'
				  AND resource_id = ?
				""",
				Integer.class,
				String.valueOf(requeueId));
		assertEquals(1, requeueAuditCount);
	}

	@Test
	@WithMockUser(username = "ops-user", roles = "OPS")
	void bulkRequeueDeadLetters_returnsBadRequestForEmptyList() throws Exception {
		String body = objectMapper.writeValueAsString(Map.of("outboxEventIds", List.of()));

		mockMvc.perform(post("/api/reporting/outbox/dead-letters/requeue-bulk")
						.with(csrf())
						.contentType("application/json")
						.content(body))
				.andExpect(status().isBadRequest());
	}

	@Test
	@WithMockUser(username = "ops-user", roles = "OPS")
	void bulkRequeueDeadLetters_returnsBadRequestForNullBody() throws Exception {
		mockMvc.perform(post("/api/reporting/outbox/dead-letters/requeue-bulk")
						.with(csrf())
						.contentType("application/json"))
				.andExpect(status().isBadRequest());
	}

	@Test
	@WithMockUser(username = "ops-user", roles = "OPS")
	void bulkRequeueDeadLetters_returnsBadRequestWhenOverLimit() throws Exception {
		List<Long> ids = IntStream.rangeClosed(1, 101)
				.mapToObj(i -> (long) i)
				.collect(Collectors.toList());
		String body = objectMapper.writeValueAsString(Map.of("outboxEventIds", ids));

		mockMvc.perform(post("/api/reporting/outbox/dead-letters/requeue-bulk")
						.with(csrf())
						.contentType("application/json")
						.content(body))
				.andExpect(status().isBadRequest());
	}

	private void append(String aggregateType, String eventType, String aggregateId) {
		outboxService.appendMessage(
				aggregateType,
				aggregateId,
				eventType,
				Map.of("marker", eventType));
	}

	private void markAsFailed(String eventType, int retryCount, String lastError) {
		jdbcTemplate.update(
				"""
				UPDATE outbox_events
				SET status = 'FAILED',
				    retry_count = ?,
				    processed_at = CURRENT_TIMESTAMP,
				    last_error = ?
				WHERE event_type = ?
				""",
				retryCount,
				lastError,
				eventType);
	}

	private Long eventIdByType(String eventType) {
		return jdbcTemplate.queryForObject(
				"SELECT id FROM outbox_events WHERE event_type = ?",
				Long.class,
				eventType);
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
