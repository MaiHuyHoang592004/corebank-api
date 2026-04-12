package com.corebank.corebank_api.reporting;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
class ReadModelHealthServiceIntegrationTest {

	@Autowired
	private OutboxService outboxService;

	@Autowired
	private ReadModelProjector readModelProjector;

	@Autowired
	private ReadModelHealthService readModelHealthService;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@BeforeEach
	void setUp() {
		jdbcTemplate.update("DELETE FROM read_model_aggregate_activity");
		jdbcTemplate.update("DELETE FROM read_model_event_feed");
		jdbcTemplate.update("DELETE FROM outbox_events");
	}

	@Test
	void snapshot_whenNoData_reportsHealthyZeroState() {
		ReadModelHealthService.ReadModelHealthSnapshot snapshot = readModelHealthService.snapshot();
		assertTrue(snapshot.healthy());
		assertEquals(0L, snapshot.feedCount());
		assertEquals(0L, snapshot.summaryCount());
		assertEquals(0L, snapshot.pendingOutboxCount());
		assertEquals(0L, snapshot.lagSeconds());
	}

	@Test
	void snapshot_whenOutboxAheadWithoutProjection_reportsUnhealthyLag() {
		outboxService.appendMessage(
				"TRANSFER",
				"trf-health-1",
				"TRANSFER_COMPLETED",
				Map.of("amountMinor", 100_000L, "currency", "VND"),
				OutboxMetadata.of("corr-health-1", "req-health-1", "tester"));

		ReadModelHealthService.ReadModelHealthSnapshot snapshot = readModelHealthService.snapshot();
		assertFalse(snapshot.healthy());
		assertEquals(0L, snapshot.feedCount());
		assertEquals(0L, snapshot.summaryCount());
		assertEquals(1L, snapshot.pendingOutboxCount());
		assertEquals(Long.MAX_VALUE, snapshot.lagSeconds());
	}

	@Test
	void snapshot_afterProjection_reportsHealthyState() {
		outboxService.appendMessage(
				"LOAN_CONTRACT",
				"loan-health-1",
				"LOAN_DISBURSED",
				Map.of("principalAmountMinor", 800_000L, "currency", "VND"),
				OutboxMetadata.of("corr-health-2", "req-health-2", "loan-officer"));

		String eventData = jdbcTemplate.queryForObject(
				"SELECT event_data::text FROM outbox_events LIMIT 1",
				String.class);
		readModelProjector.projectEvent(KafkaConfig.TOPIC_LOAN_EVENTS, eventData);

		ReadModelHealthService.ReadModelHealthSnapshot snapshot = readModelHealthService.snapshot();
		assertTrue(snapshot.healthy());
		assertEquals(1L, snapshot.feedCount());
		assertEquals(1L, snapshot.summaryCount());
		assertEquals(1L, snapshot.pendingOutboxCount());
		assertTrue(snapshot.lagSeconds() <= snapshot.maxAllowedLagSeconds());
	}
}
