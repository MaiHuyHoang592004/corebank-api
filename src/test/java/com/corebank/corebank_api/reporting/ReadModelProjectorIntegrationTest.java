package com.corebank.corebank_api.reporting;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import com.corebank.corebank_api.TestcontainersConfiguration;
import com.corebank.corebank_api.integration.KafkaConfig;
import com.corebank.corebank_api.integration.OutboxMetadata;
import com.corebank.corebank_api.integration.OutboxService;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
class ReadModelProjectorIntegrationTest {

	@Autowired
	private OutboxService outboxService;

	@Autowired
	private ReadModelProjector readModelProjector;

	@Autowired
	private ReadModelReplayService readModelReplayService;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@BeforeEach
	void setUp() {
		jdbcTemplate.update("DELETE FROM read_model_event_feed");
		jdbcTemplate.update("DELETE FROM outbox_events");
	}

	@Test
	void projectEvent_persistsEnvelopeIntoReadModelFeed() {
		outboxService.appendMessage(
				"LOAN_CONTRACT",
				"loan-001",
				"LOAN_DISBURSED",
				Map.of("principalAmountMinor", 1_200_000L, "currency", "VND"),
				OutboxMetadata.of("corr-loan-1", "req-loan-1", "loan-officer"));

		String eventData = jdbcTemplate.queryForObject(
				"SELECT event_data::text FROM outbox_events LIMIT 1",
				String.class);

		int inserted = readModelProjector.projectEvent(KafkaConfig.TOPIC_LOAN_EVENTS, eventData);
		assertEquals(1, inserted);

		Map<String, Object> projected = jdbcTemplate.queryForMap(
				"""
				SELECT source_topic,
				       aggregate_type,
				       aggregate_id,
				       event_type,
				       schema_version,
				       correlation_id,
				       request_id,
				       actor,
				       payload->>'currency' AS currency
				FROM read_model_event_feed
				LIMIT 1
				""");

		assertEquals(KafkaConfig.TOPIC_LOAN_EVENTS, projected.get("source_topic"));
		assertEquals("LOAN_CONTRACT", projected.get("aggregate_type"));
		assertEquals("loan-001", projected.get("aggregate_id"));
		assertEquals("LOAN_DISBURSED", projected.get("event_type"));
		assertEquals("v1", projected.get("schema_version"));
		assertEquals("corr-loan-1", projected.get("correlation_id"));
		assertEquals("req-loan-1", projected.get("request_id"));
		assertEquals("loan-officer", projected.get("actor"));
		assertEquals("VND", projected.get("currency"));
	}

	@Test
	void projectEvent_isIdempotentByEnvelopeEventId() {
		outboxService.appendMessage(
				"TRANSFER",
				"trf-001",
				"TRANSFER_COMPLETED",
				Map.of("amountMinor", 250_000L, "currency", "VND"),
				OutboxMetadata.of("corr-transfer-1", "req-transfer-1", "tester"));

		String eventData = jdbcTemplate.queryForObject(
				"SELECT event_data::text FROM outbox_events LIMIT 1",
				String.class);

		assertEquals(1, readModelProjector.projectEvent(KafkaConfig.TOPIC_TRANSFER_EVENTS, eventData));
		assertEquals(0, readModelProjector.projectEvent(KafkaConfig.TOPIC_TRANSFER_EVENTS, eventData));
		assertEquals(1, count("SELECT COUNT(*) FROM read_model_event_feed"));
	}

	@Test
	void projectEvent_skipsLegacyPayloadWithoutEnvelope() {
		int inserted = readModelProjector.projectEvent(
				KafkaConfig.TOPIC_PAYMENT_EVENTS,
				"{\"paymentId\":\"legacy-1\",\"status\":\"AUTHORIZED\"}");

		assertEquals(0, inserted);
		assertEquals(0, count("SELECT COUNT(*) FROM read_model_event_feed"));
	}

	@Test
	void replayFromOutbox_rebuildsFeedFromStoredEvents() {
		outboxService.appendMessage(
				"DEPOSIT_CONTRACT",
				"dep-001",
				"DEPOSIT_OPENED",
				Map.of("principalAmountMinor", 5_000_000L, "currency", "VND"),
				OutboxMetadata.of("corr-deposit-1", "req-deposit-1", "operator"));

		String eventId = jdbcTemplate.queryForObject(
				"SELECT event_data->>'eventId' FROM outbox_events LIMIT 1",
				String.class);
		assertNotNull(eventId);

		int replayed = readModelReplayService.replayFromOutbox();
		assertEquals(1, replayed);

		Map<String, Object> projected = jdbcTemplate.queryForMap(
				"""
				SELECT source_topic,
				       aggregate_id,
				       event_type
				FROM read_model_event_feed
				WHERE event_id = CAST(? AS uuid)
				""",
				eventId);

		assertEquals("outbox-replay", projected.get("source_topic"));
		assertEquals("dep-001", projected.get("aggregate_id"));
		assertEquals("DEPOSIT_OPENED", projected.get("event_type"));
	}

	private int count(String sql, Object... args) {
		Integer result = jdbcTemplate.queryForObject(sql, Integer.class, args);
		return result == null ? 0 : result;
	}
}
