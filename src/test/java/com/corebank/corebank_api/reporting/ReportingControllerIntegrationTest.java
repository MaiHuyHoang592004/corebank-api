package com.corebank.corebank_api.reporting;

import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.corebank.corebank_api.TestcontainersConfiguration;
import com.corebank.corebank_api.integration.KafkaConfig;
import com.corebank.corebank_api.integration.OutboxMetadata;
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
class ReportingControllerIntegrationTest {

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private OutboxService outboxService;

	@Autowired
	private ReadModelProjector readModelProjector;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@BeforeEach
	void setUp() {
		jdbcTemplate.update("DELETE FROM read_model_aggregate_activity");
		jdbcTemplate.update("DELETE FROM read_model_event_feed");
		jdbcTemplate.update("DELETE FROM outbox_events");
	}

	@Test
	void aggregateActivity_returnsPagedAndFilteredData() throws Exception {
		appendAndProject(
				"LOAN_CONTRACT",
				"loan-report-1",
				"LOAN_DISBURSED",
				KafkaConfig.TOPIC_LOAN_EVENTS,
				"corr-report-1",
				"loan-officer");
		appendAndProject(
				"DEPOSIT_CONTRACT",
				"dep-report-1",
				"DEPOSIT_OPENED",
				KafkaConfig.TOPIC_DEPOSIT_EVENTS,
				"corr-report-2",
				"deposit-officer");
		appendAndProject(
				"LOAN_CONTRACT",
				"loan-report-1",
				"LOAN_REPAID",
				KafkaConfig.TOPIC_LOAN_EVENTS,
				"corr-report-3",
				"loan-officer");

		mockMvc.perform(get("/api/reporting/aggregate-activity")
						.queryParam("page", "0")
						.queryParam("size", "1"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.page").value(0))
				.andExpect(jsonPath("$.size").value(1))
				.andExpect(jsonPath("$.totalItems").value(2))
				.andExpect(jsonPath("$.items.length()").value(1));

		mockMvc.perform(get("/api/reporting/aggregate-activity")
						.queryParam("aggregateType", "LOAN_CONTRACT")
						.queryParam("aggregateId", "loan-report-1")
						.queryParam("page", "0")
						.queryParam("size", "10"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.totalItems").value(1))
				.andExpect(jsonPath("$.items.length()").value(1))
				.andExpect(jsonPath("$.items[0].aggregateType").value("LOAN_CONTRACT"))
				.andExpect(jsonPath("$.items[0].aggregateId").value("loan-report-1"))
				.andExpect(jsonPath("$.items[0].latestEventType").value("LOAN_REPAID"))
				.andExpect(jsonPath("$.items[0].eventCount").value(2));
	}

	@Test
	void aggregateEvents_returnsOnlyRequestedAggregateEvents() throws Exception {
		appendAndProject(
				"LOAN_CONTRACT",
				"loan-report-2",
				"LOAN_DISBURSED",
				KafkaConfig.TOPIC_LOAN_EVENTS,
				"corr-event-1",
				"loan-officer");
		appendAndProject(
				"LOAN_CONTRACT",
				"loan-report-2",
				"LOAN_REPAID",
				KafkaConfig.TOPIC_LOAN_EVENTS,
				"corr-event-2",
				"loan-officer");
		appendAndProject(
				"DEPOSIT_CONTRACT",
				"dep-report-2",
				"DEPOSIT_OPENED",
				KafkaConfig.TOPIC_DEPOSIT_EVENTS,
				"corr-event-3",
				"deposit-officer");

		mockMvc.perform(get("/api/reporting/aggregate-activity/{aggregateType}/{aggregateId}/events",
						"LOAN_CONTRACT",
						"loan-report-2")
						.queryParam("limit", "10"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.aggregateType").value("LOAN_CONTRACT"))
				.andExpect(jsonPath("$.aggregateId").value("loan-report-2"))
				.andExpect(jsonPath("$.items.length()").value(2))
				.andExpect(jsonPath("$.items[*].aggregateType", everyItem(is("LOAN_CONTRACT"))))
				.andExpect(jsonPath("$.items[*].aggregateId", everyItem(is("loan-report-2"))))
				.andExpect(jsonPath("$.items[*].eventType", containsInAnyOrder("LOAN_DISBURSED", "LOAN_REPAID")));

		mockMvc.perform(get("/api/reporting/aggregate-activity/{aggregateType}/{aggregateId}/events",
						"LOAN_CONTRACT",
						"loan-report-2")
						.queryParam("limit", "1"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.limit").value(1))
				.andExpect(jsonPath("$.items.length()").value(1));
	}

	private void appendAndProject(
			String aggregateType,
			String aggregateId,
			String eventType,
			String topic,
			String correlationId,
			String actor) {
		outboxService.appendMessage(
				aggregateType,
				aggregateId,
				eventType,
				Map.of("currency", "VND", "amountMinor", 100_000L),
				OutboxMetadata.of(correlationId, "req-" + correlationId, actor));

		String eventData = jdbcTemplate.queryForObject(
				"""
				SELECT event_data::text
				FROM outbox_events
				WHERE aggregate_type = ? AND aggregate_id = ? AND event_type = ?
				ORDER BY id DESC
				LIMIT 1
				""",
				String.class,
				aggregateType,
				aggregateId,
				eventType);
		readModelProjector.projectEvent(topic, eventData);
	}
}
