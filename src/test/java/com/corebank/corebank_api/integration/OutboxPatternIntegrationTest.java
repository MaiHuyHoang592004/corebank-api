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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
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

	@Test
	void getPendingEvents_reclaimsStaleProcessingClaim() {
		outboxService.appendMessage(
				"TRANSFER",
				"trf-stale-1",
				"TRANSFER_INITIATED",
				Map.of("amountMinor", 10_000L));

		List<OutboxEvent> firstClaim = outboxEventRepository.getPendingEvents(10, 3, 30, 300);
		assertEquals(1, firstClaim.size());
		Long eventId = firstClaim.get(0).getId();

		jdbcTemplate.update(
				"""
				UPDATE outbox_events
				SET processing_started_at = CURRENT_TIMESTAMP - INTERVAL '10 minutes'
				WHERE id = ?
				""",
				eventId);

		List<OutboxEvent> reclaimed = outboxEventRepository.getPendingEvents(10, 3, 30, 60);
		assertEquals(1, reclaimed.size());
		assertEquals(eventId, reclaimed.get(0).getId());

		Map<String, Object> row = jdbcTemplate.queryForMap(
				"SELECT status, retry_count, processing_started_at FROM outbox_events WHERE id = ?",
				eventId);
		assertEquals("PROCESSING", row.get("status"));
		assertEquals(0, ((Number) row.get("retry_count")).intValue());
		assertNotNull(row.get("processing_started_at"));
	}

	@Test
	void getPendingEvents_retriesFailedEventAfterBackoffWithinMaxRetries() {
		outboxService.appendMessage(
				"PAYMENT_ORDER",
				"pay-retry-1",
				"PAYMENT_CAPTURE_FAILED",
				Map.of("amountMinor", 50_000L));

		List<OutboxEvent> claimed = outboxEventRepository.getPendingEvents(10, 3, 30, 300);
		assertEquals(1, claimed.size());
		Long eventId = claimed.get(0).getId();

		boolean failed = outboxEventRepository.markAsFailed(eventId, "transient kafka outage");
		assertTrue(failed);

		List<OutboxEvent> immediateRetryBlocked = outboxEventRepository.getPendingEvents(10, 3, 300, 300);
		assertTrue(immediateRetryBlocked.isEmpty());

		jdbcTemplate.update(
				"""
				UPDATE outbox_events
				SET processed_at = CURRENT_TIMESTAMP - INTERVAL '10 minutes'
				WHERE id = ?
				""",
				eventId);

		List<OutboxEvent> retried = outboxEventRepository.getPendingEvents(10, 3, 60, 300);
		assertEquals(1, retried.size());
		assertEquals(eventId, retried.get(0).getId());
	}

	@Test
	void getPendingEvents_skipsFailedEventAtMaxRetryLimit() {
		outboxService.appendMessage(
				"LOAN_CONTRACT",
				"loan-retry-limit-1",
				"LOAN_DISBURSEMENT_FAILED",
				Map.of("amountMinor", 120_000L));

		List<OutboxEvent> claimed = outboxEventRepository.getPendingEvents(10, 3, 30, 300);
		assertEquals(1, claimed.size());
		Long eventId = claimed.get(0).getId();

		assertTrue(outboxEventRepository.markAsFailed(eventId, "attempt-1"));
		assertTrue(outboxEventRepository.markAsFailed(eventId, "attempt-2"));
		assertTrue(outboxEventRepository.markAsFailed(eventId, "attempt-3"));

		jdbcTemplate.update(
				"""
				UPDATE outbox_events
				SET processed_at = CURRENT_TIMESTAMP - INTERVAL '10 minutes'
				WHERE id = ?
				""",
				eventId);

		List<OutboxEvent> blocked = outboxEventRepository.getPendingEvents(10, 3, 0, 300);
		assertTrue(blocked.isEmpty());

		Map<String, Object> row = jdbcTemplate.queryForMap(
				"SELECT status, retry_count FROM outbox_events WHERE id = ?",
				eventId);
		assertEquals("FAILED", row.get("status"));
		assertEquals(3, ((Number) row.get("retry_count")).intValue());
	}

	@Test
	void addToDeadLetter_isIdempotentForExhaustedFailedEvent() {
		outboxService.appendMessage(
				"PAYMENT_ORDER",
				"pay-dead-letter-1",
				"PAYMENT_SETTLEMENT",
				Map.of("amountMinor", 230_000L));

		List<OutboxEvent> claimed = outboxEventRepository.getPendingEvents(10, 3, 30, 300);
		assertEquals(1, claimed.size());
		Long eventId = claimed.get(0).getId();

		assertTrue(outboxEventRepository.markAsFailed(eventId, "attempt-1"));
		assertTrue(outboxEventRepository.markAsFailed(eventId, "attempt-2"));
		assertTrue(outboxEventRepository.markAsFailed(eventId, "attempt-3"));

		boolean firstInsert = outboxEventRepository.addToDeadLetter(eventId);
		boolean duplicateInsert = outboxEventRepository.addToDeadLetter(eventId);
		assertTrue(firstInsert);
		assertFalse(duplicateInsert);

		Map<String, Object> row = jdbcTemplate.queryForMap(
				"""
				SELECT outbox_event_id,
				       event_type,
				       retry_count,
				       last_error
				FROM outbox_dead_letters
				WHERE outbox_event_id = ?
				""",
				eventId);
		assertEquals(eventId, ((Number) row.get("outbox_event_id")).longValue());
		assertEquals("PAYMENT_SETTLEMENT", row.get("event_type"));
		assertEquals(3, ((Number) row.get("retry_count")).intValue());
		assertTrue(String.valueOf(row.get("last_error")).contains("attempt-3"));
	}

	@Test
	void markAsTerminalFailed_setsRetryToMaxAndSkipsFurtherClaim() {
		outboxService.appendMessage(
				"TRANSFER",
				"trf-terminal-fail-1",
				"TRANSFER_COMPLETED",
				Map.of("amountMinor", 70_000L));

		List<OutboxEvent> claimed = outboxEventRepository.getPendingEvents(10, 3, 30, 300);
		assertEquals(1, claimed.size());
		Long eventId = claimed.get(0).getId();

		boolean terminalFailed = outboxEventRepository.markAsTerminalFailed(
				eventId,
				"Publish failed (non-transient): SerializationException: invalid payload",
				3);
		assertTrue(terminalFailed);
		assertTrue(outboxEventRepository.addToDeadLetter(eventId));

		Map<String, Object> row = jdbcTemplate.queryForMap(
				"""
				SELECT status,
				       retry_count,
				       processing_started_at,
				       last_error
				FROM outbox_events
				WHERE id = ?
				""",
				eventId);
		assertEquals("FAILED", row.get("status"));
		assertEquals(3, ((Number) row.get("retry_count")).intValue());
		assertNull(row.get("processing_started_at"));
		assertTrue(String.valueOf(row.get("last_error")).contains("non-transient"));

		List<OutboxEvent> pending = outboxEventRepository.getPendingEvents(10, 3, 0, 300);
		assertTrue(pending.stream().noneMatch(event -> eventId.equals(event.getId())));
	}

	@Test
	void requeueDeadLetter_movesFailedEventBackToPendingAndClearsDeadLetter() {
		outboxService.appendMessage(
				"TRANSFER",
				"trf-requeue-1",
				"TRANSFER_COMPLETED",
				Map.of("amountMinor", 90_000L));

		List<OutboxEvent> claimed = outboxEventRepository.getPendingEvents(10, 3, 30, 300);
		assertEquals(1, claimed.size());
		Long eventId = claimed.get(0).getId();

		assertTrue(outboxEventRepository.markAsFailed(eventId, "attempt-1"));
		assertTrue(outboxEventRepository.markAsFailed(eventId, "attempt-2"));
		assertTrue(outboxEventRepository.markAsFailed(eventId, "attempt-3"));
		assertTrue(outboxEventRepository.addToDeadLetter(eventId));

		OutboxEventRepository.DeadLetterRequeueStatus result = outboxEventRepository.requeueDeadLetter(eventId);
		assertEquals(OutboxEventRepository.DeadLetterRequeueStatus.REQUEUED, result);

		Map<String, Object> row = jdbcTemplate.queryForMap(
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
		assertEquals("PENDING", row.get("status"));
		assertEquals(0, ((Number) row.get("retry_count")).intValue());
		assertNull(row.get("processed_at"));
		assertNull(row.get("processing_started_at"));
		assertNull(row.get("last_error"));

		Integer deadLetterCount = jdbcTemplate.queryForObject(
				"SELECT COUNT(*) FROM outbox_dead_letters WHERE outbox_event_id = ?",
				Integer.class,
				eventId);
		assertEquals(0, deadLetterCount);
	}

	@Test
	void requeueDeadLetter_returnsNotFoundWhenNoDeadLetterExists() {
		OutboxEventRepository.DeadLetterRequeueStatus result = outboxEventRepository.requeueDeadLetter(9_999_999L);
		assertEquals(OutboxEventRepository.DeadLetterRequeueStatus.NOT_FOUND, result);
	}
}
