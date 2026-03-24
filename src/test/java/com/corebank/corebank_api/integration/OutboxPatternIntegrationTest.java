package com.corebank.corebank_api.integration;

import com.corebank.corebank_api.TestcontainersConfiguration;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
class OutboxPatternIntegrationTest {

	@Autowired
	private OutboxService outboxService;

	@Autowired
	private OutboxEventRepository outboxEventRepository;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

	@BeforeEach
	void cleanOutbox() {
		jdbcTemplate.update("DELETE FROM outbox_events");
	}

	@Test
	void appendMessage_persistsPendingOutboxEvent() throws Exception {
		outboxService.appendMessage(
				"DEPOSIT_CONTRACT",
				"contract-001",
				"DepositOpened",
				Map.of("amountMinor", 1_000_000L, "currency", "VND"));

		Integer count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM outbox_events", Integer.class);
		assertEquals(1, count);

		Map<String, Object> row = jdbcTemplate.queryForMap(
				"""
				SELECT aggregate_type,
				       aggregate_id,
				       event_type,
				       status,
				       event_data::text AS event_data
				FROM outbox_events
				LIMIT 1
				""");

		assertEquals("DEPOSIT_CONTRACT", row.get("aggregate_type"));
		assertEquals("contract-001", row.get("aggregate_id"));
		assertEquals("DepositOpened", row.get("event_type"));
		assertEquals("PENDING", row.get("status"));

		Map<String, Object> envelope = objectMapper.readValue(String.valueOf(row.get("event_data")), Map.class);
		assertNotNull(envelope.get("eventId"));
		assertNotNull(envelope.get("occurredAt"));
		assertEquals("v1", envelope.get("schemaVersion"));
		assertEquals("DEPOSIT_CONTRACT", envelope.get("aggregateType"));
		assertEquals("contract-001", envelope.get("aggregateId"));
		assertEquals("DepositOpened", envelope.get("eventType"));

		Map<?, ?> payload = (Map<?, ?>) envelope.get("payload");
		assertEquals("VND", payload.get("currency"));
		assertEquals(1_000_000L, ((Number) payload.get("amountMinor")).longValue());
	}

	@Test
	void appendMessage_withMetadataStoresTraceFieldsInsideEnvelope() throws Exception {
		outboxService.appendMessage(
				"TRANSFER",
				"trf-001",
				"TRANSFER_COMPLETED",
				Map.of("amountMinor", 250_000L),
				OutboxMetadata.of("corr-1", "req-1", "tester"));

		Map<String, Object> row = jdbcTemplate.queryForMap(
				"""
				SELECT event_data::text AS event_data,
				       correlation_id
				FROM outbox_events
				LIMIT 1
				""");

		assertEquals("corr-1", row.get("correlation_id"));

		Map<String, Object> envelope = objectMapper.readValue(String.valueOf(row.get("event_data")), Map.class);
		assertEquals("corr-1", envelope.get("correlationId"));
		assertEquals("req-1", envelope.get("requestId"));
		assertEquals("tester", envelope.get("actor"));
	}

	@Test
	void markAsProcessed_updatesStatusAndProcessedAt() {
		outboxService.appendMessage(
				"TRANSFER",
				"trf-001",
				"TransferPosted",
				Map.of("amountMinor", 250_000L));

		Long eventId = jdbcTemplate.queryForObject("SELECT id FROM outbox_events LIMIT 1", Long.class);
		assertNotNull(eventId);

		boolean marked = outboxEventRepository.markAsProcessed(eventId, OutboxEvent.STATUS_PROCESSED);
		assertTrue(marked);

		Map<String, Object> row = jdbcTemplate.queryForMap(
				"SELECT status, processed_at FROM outbox_events WHERE id = ?",
				eventId);

		assertEquals("PROCESSED", row.get("status"));
		assertNotNull(row.get("processed_at"));
	}

	@Test
	void getPendingEvents_marksClaimedEventAsProcessing() {
		outboxService.appendMessage(
				"PAYMENT_ORDER",
				"pay-001",
				"PAYMENT_AUTHORIZED",
				Map.of("amountMinor", 100_000L));

		List<OutboxEvent> pending = outboxEventRepository.getPendingEvents(10);
		assertEquals(1, pending.size());

		Map<String, Object> row = jdbcTemplate.queryForMap(
				"SELECT status FROM outbox_events WHERE id = ?",
				pending.get(0).getId());
		assertEquals("PROCESSING", row.get("status"));
	}

	@Test
	void markAsProcessed_acceptsProcessingStatusEvent() {
		outboxService.appendMessage(
				"DEPOSIT_CONTRACT",
				"contract-002",
				"DEPOSIT_MATURED",
				Map.of("amountMinor", 1_500_000L));

		List<OutboxEvent> pending = outboxEventRepository.getPendingEvents(10);
		assertEquals(1, pending.size());

		Long eventId = pending.get(0).getId();
		boolean marked = outboxEventRepository.markAsProcessed(eventId, OutboxEvent.STATUS_PROCESSED);
		assertTrue(marked);

		Map<String, Object> row = jdbcTemplate.queryForMap(
				"SELECT status, processed_at FROM outbox_events WHERE id = ?",
				eventId);
		assertEquals("PROCESSED", row.get("status"));
		assertNotNull(row.get("processed_at"));
	}

	@Test
	void markAsFailed_incrementsRetryAndPreservesFailedStateForRetry() {
		outboxService.appendMessage(
				"LOAN_CONTRACT",
				"loan-001",
				"LOAN_DEFAULTED",
				Map.of("outstandingPrincipalMinor", 900_000L));

		List<OutboxEvent> pending = outboxEventRepository.getPendingEvents(10);
		assertEquals(1, pending.size());

		Long eventId = pending.get(0).getId();
		boolean firstFail = outboxEventRepository.markAsFailed(eventId, "kafka timeout");
		assertTrue(firstFail);

		Map<String, Object> first = jdbcTemplate.queryForMap(
				"SELECT status, retry_count, last_error FROM outbox_events WHERE id = ?",
				eventId);
		assertEquals("FAILED", first.get("status"));
		assertEquals(1, ((Number) first.get("retry_count")).intValue());
		assertTrue(String.valueOf(first.get("last_error")).contains("timeout"));

		boolean secondFail = outboxEventRepository.markAsFailed(eventId, "kafka timeout retry");
		assertTrue(secondFail);

		Map<String, Object> second = jdbcTemplate.queryForMap(
				"SELECT status, retry_count, last_error FROM outbox_events WHERE id = ?",
				eventId);
		assertEquals("FAILED", second.get("status"));
		assertEquals(2, ((Number) second.get("retry_count")).intValue());
		assertTrue(String.valueOf(second.get("last_error")).contains("retry"));
	}
}
