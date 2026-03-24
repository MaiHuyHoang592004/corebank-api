package com.corebank.corebank_api.integration;

import com.corebank.corebank_api.TestcontainersConfiguration;
import java.util.Map;
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

	@BeforeEach
	void cleanOutbox() {
		jdbcTemplate.update("DELETE FROM outbox_events");
	}

	@Test
	void appendMessage_persistsPendingOutboxEvent() {
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
		assertTrue(String.valueOf(row.get("event_data")).contains("VND"));
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
}