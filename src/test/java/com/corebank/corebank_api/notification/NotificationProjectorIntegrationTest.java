package com.corebank.corebank_api.notification;

import static org.junit.jupiter.api.Assertions.assertEquals;

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
class NotificationProjectorIntegrationTest {

	@Autowired
	private OutboxService outboxService;

	@Autowired
	private NotificationProjector notificationProjector;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@BeforeEach
	void setUp() {
		jdbcTemplate.update("DELETE FROM read_model_notification_inbox");
		jdbcTemplate.update("DELETE FROM outbox_events");
	}

	@Test
	void projectEvent_persistsEnvelopeToNotificationInbox() {
		outboxService.appendMessage(
				"PAYMENT_ORDER",
				"pay-noti-1",
				"PAYMENT_CAPTURED",
				Map.of("amountMinor", 250_000L, "currency", "VND"),
				OutboxMetadata.of("corr-noti-1", "req-noti-1", "operator"));

		String eventData = jdbcTemplate.queryForObject(
				"SELECT event_data::text FROM outbox_events LIMIT 1",
				String.class);

		int inserted = notificationProjector.projectEvent(KafkaConfig.TOPIC_PAYMENT_EVENTS, eventData);
		assertEquals(1, inserted);

		Map<String, Object> row = jdbcTemplate.queryForMap(
				"""
				SELECT source_topic,
				       aggregate_type,
				       aggregate_id,
				       event_type,
				       correlation_id,
				       request_id,
				       actor,
				       status,
				       payload->>'currency' AS currency
				FROM read_model_notification_inbox
				LIMIT 1
				""");
		assertEquals(KafkaConfig.TOPIC_PAYMENT_EVENTS, row.get("source_topic"));
		assertEquals("PAYMENT_ORDER", row.get("aggregate_type"));
		assertEquals("pay-noti-1", row.get("aggregate_id"));
		assertEquals("PAYMENT_CAPTURED", row.get("event_type"));
		assertEquals("corr-noti-1", row.get("correlation_id"));
		assertEquals("req-noti-1", row.get("request_id"));
		assertEquals("operator", row.get("actor"));
		assertEquals("UNREAD", row.get("status"));
		assertEquals("VND", row.get("currency"));
	}

	@Test
	void projectEvent_isIdempotentByEventId() {
		outboxService.appendMessage(
				"DEPOSIT_CONTRACT",
				"dep-noti-1",
				"DEPOSIT_OPENED",
				Map.of("principalAmountMinor", 5_000_000L, "currency", "VND"),
				OutboxMetadata.of("corr-noti-2", "req-noti-2", "operator"));

		String eventData = jdbcTemplate.queryForObject(
				"SELECT event_data::text FROM outbox_events LIMIT 1",
				String.class);

		assertEquals(1, notificationProjector.projectEvent(KafkaConfig.TOPIC_DEPOSIT_EVENTS, eventData));
		assertEquals(0, notificationProjector.projectEvent(KafkaConfig.TOPIC_DEPOSIT_EVENTS, eventData));
		assertEquals(1, count("SELECT COUNT(*) FROM read_model_notification_inbox"));
	}

	@Test
	void projectEvent_skipsLegacyPayloadWithoutEnvelope() {
		int inserted = notificationProjector.projectEvent(
				KafkaConfig.TOPIC_ACCOUNT_EVENTS,
				"{\"accountId\":\"acc-1\",\"eventType\":\"ACCOUNT_OPENED\"}");

		assertEquals(0, inserted);
		assertEquals(0, count("SELECT COUNT(*) FROM read_model_notification_inbox"));
	}

	private int count(String sql, Object... args) {
		Integer result = jdbcTemplate.queryForObject(sql, Integer.class, args);
		return result == null ? 0 : result;
	}
}
