package com.corebank.corebank_api.reporting;

import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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
