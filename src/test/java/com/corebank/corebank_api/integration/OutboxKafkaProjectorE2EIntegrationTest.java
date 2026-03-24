package com.corebank.corebank_api.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;

import com.corebank.corebank_api.TestcontainersConfiguration;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.ConfluentKafkaContainer;
import org.testcontainers.utility.DockerImageName;

@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = {
		"spring.task.scheduling.enabled=false",
		"spring.kafka.consumer.auto-offset-reset=earliest" })
@Testcontainers
class OutboxKafkaProjectorE2EIntegrationTest {

	@Container
	static final ConfluentKafkaContainer KAFKA = new ConfluentKafkaContainer(
			DockerImageName.parse("confluentinc/cp-kafka:7.6.1"))
			.waitingFor(Wait.forListeningPort());

	@DynamicPropertySource
	static void kafkaProperties(DynamicPropertyRegistry registry) {
		registry.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
	}

	@Autowired
	private OutboxService outboxService;

	@Autowired
	private OutboxEventPublisher outboxEventPublisher;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@BeforeEach
	void setUp() {
		jdbcTemplate.update("DELETE FROM read_model_aggregate_activity");
		jdbcTemplate.update("DELETE FROM read_model_event_feed");
		jdbcTemplate.update("DELETE FROM read_model_notification_inbox");
		jdbcTemplate.update("DELETE FROM outbox_events");
	}

	@Test
	void processPendingEvents_publishesToKafkaAndProjectsReadModel() throws Exception {
		outboxService.appendMessage(
				"LOAN_CONTRACT",
				"loan-e2e-1",
				"LOAN_DISBURSED",
				Map.of("principalAmountMinor", 1_500_000L, "currency", "VND"),
				OutboxMetadata.of("corr-e2e-1", "req-e2e-1", "loan-officer"));

		String eventId = jdbcTemplate.queryForObject(
				"SELECT event_data->>'eventId' FROM outbox_events LIMIT 1",
				String.class);

		outboxEventPublisher.processPendingEvents();
		waitUntilProjected(eventId, Duration.ofSeconds(15));

		String outboxStatus = jdbcTemplate.queryForObject(
				"SELECT status FROM outbox_events LIMIT 1",
				String.class);
		assertEquals("PROCESSED", outboxStatus);

		int feedCount = count(
				"SELECT COUNT(*) FROM read_model_event_feed WHERE event_id = CAST(? AS uuid)",
				eventId);
		assertEquals(1, feedCount);
		int notificationCount = count(
				"SELECT COUNT(*) FROM read_model_notification_inbox WHERE event_id = CAST(? AS uuid)",
				eventId);
		assertEquals(1, notificationCount);

		Map<String, Object> summary = jdbcTemplate.queryForMap(
				"""
				SELECT latest_event_type,
				       event_count
				FROM read_model_aggregate_activity
				WHERE aggregate_type = 'LOAN_CONTRACT'
				  AND aggregate_id = 'loan-e2e-1'
				""");
		assertEquals("LOAN_DISBURSED", summary.get("latest_event_type"));
		assertEquals(1L, ((Number) summary.get("event_count")).longValue());
	}

	private void waitUntilProjected(String eventId, Duration timeout) throws InterruptedException {
		Instant deadline = Instant.now().plus(timeout);
		while (Instant.now().isBefore(deadline)) {
			int projected = count(
					"SELECT COUNT(*) FROM read_model_event_feed WHERE event_id = CAST(? AS uuid)",
					eventId);
			if (projected == 1) {
				return;
			}
			Thread.sleep(250);
		}
		fail("Timed out waiting for projector to consume Kafka event");
	}

	private int count(String sql, Object... args) {
		Integer result = jdbcTemplate.queryForObject(sql, Integer.class, args);
		return result == null ? 0 : result;
	}
}
