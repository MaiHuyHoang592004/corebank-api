package com.corebank.corebank_api.ops.batch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.corebank.corebank_api.TestcontainersConfiguration;
import com.corebank.corebank_api.common.CoreBankException;
import com.corebank.corebank_api.ops.batch.BatchRunService.BatchRun;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
class BatchRunServiceTest {

	@Autowired
	private BatchRunService batchRunService;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@BeforeEach
	void setUp() {
		// Clean up batch_runs table before each test
		jdbcTemplate.update("DELETE FROM batch_runs");
	}

	@Test
	void startBatchCreatesNewBatch() {
		long runId = batchRunService.startBatch(
				"EOD_PROCESSING",
				"EOD",
				"{\"date\": \"2026-03-23\"}");

		assertNotNull(runId);
		assertTrue(runId > 0);

		// Verify batch was created
		Optional<BatchRun> batch = batchRunService.getBatchStatus(runId);
		assertTrue(batch.isPresent());
		assertEquals("EOD_PROCESSING", batch.get().batchName());
		assertEquals("RUNNING", batch.get().status());
	}

	@Test
	void completeBatchUpdatesStatus() {
		long runId = batchRunService.startBatch("EOD_PROCESSING", "EOD", "{}");

		batchRunService.completeBatch(runId, "{\"processed\": 100}");

		Optional<BatchRun> batch = batchRunService.getBatchStatus(runId);
		assertTrue(batch.isPresent());
		assertEquals("COMPLETED", batch.get().status());
		assertNotNull(batch.get().completedAt());
	}

	@Test
	void failBatchUpdatesStatus() {
		long runId = batchRunService.startBatch("EOD_PROCESSING", "EOD", "{}");

		batchRunService.failBatch(runId, "Database connection failed");

		Optional<BatchRun> batch = batchRunService.getBatchStatus(runId);
		assertTrue(batch.isPresent());
		assertEquals("FAILED", batch.get().status());
		assertEquals("Database connection failed", batch.get().errorMessage());
	}

	@Test
	void isBatchRunningReturnsTrueWhenRunning() {
		batchRunService.startBatch("EOD_PROCESSING", "EOD", "{}");

		assertTrue(batchRunService.isBatchRunning("EOD_PROCESSING"));
	}

	@Test
	void isBatchRunningReturnsFalseWhenCompleted() {
		long runId = batchRunService.startBatch("EOD_PROCESSING", "EOD", "{}");
		batchRunService.completeBatch(runId, "{}");

		assertFalse(batchRunService.isBatchRunning("EOD_PROCESSING"));
	}

	@Test
	void getBatchesByStatusReturnsCorrectBatches() {
		long runId1 = batchRunService.startBatch("EOD_PROCESSING", "EOD", "{}");
		long runId2 = batchRunService.startBatch("SNAPSHOT_GENERATION", "SNAPSHOT", "{}");

		batchRunService.completeBatch(runId1, "{}");

		List<BatchRun> completedBatches = batchRunService.getBatchesByStatus("COMPLETED", 10);
		assertEquals(1, completedBatches.size());
		assertEquals("EOD_PROCESSING", completedBatches.get(0).batchName());

		List<BatchRun> runningBatches = batchRunService.getBatchesByStatus("RUNNING", 10);
		assertEquals(1, runningBatches.size());
		assertEquals("SNAPSHOT_GENERATION", runningBatches.get(0).batchName());
	}

	@Test
	void duplicateBatchNameThrowsException() {
		batchRunService.startBatch("EOD_PROCESSING", "EOD", "{}");

		// Try to start the same batch again - should throw exception
		assertThrows(
				CoreBankException.class,
				() -> batchRunService.startBatch("EOD_PROCESSING", "EOD", "{}"));
	}

	@Test
	void batchNameCanBeReusedAfterCompletion() {
		long firstRunId = batchRunService.startBatch("EOD_PROCESSING", "EOD", "{}");
		batchRunService.completeBatch(firstRunId, "{\"processed\": 10}");

		long secondRunId = batchRunService.startBatch("EOD_PROCESSING", "EOD", "{}");
		assertTrue(secondRunId > firstRunId);

		Optional<BatchRun> secondRun = batchRunService.getBatchStatus(secondRunId);
		assertTrue(secondRun.isPresent());
		assertEquals("RUNNING", secondRun.get().status());

		List<BatchRun> batches = batchRunService.getBatchesByName("EOD_PROCESSING", 10);
		assertEquals(2, batches.size());
	}

	@Test
	void incrementRetryCountIncrementsCount() {
		long runId = batchRunService.startBatch("EOD_PROCESSING", "EOD", "{}");

		batchRunService.incrementRetryCount(runId);
		batchRunService.incrementRetryCount(runId);

		Optional<BatchRun> batch = batchRunService.getBatchStatus(runId);
		assertTrue(batch.isPresent());
		assertEquals(2, batch.get().retryCount());
	}
}