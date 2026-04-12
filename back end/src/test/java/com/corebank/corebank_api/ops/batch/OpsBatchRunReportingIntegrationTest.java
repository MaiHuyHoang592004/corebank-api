package com.corebank.corebank_api.ops.batch;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.corebank.corebank_api.TestcontainersConfiguration;
import java.time.Instant;
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
@WithMockUser(username = "viewer", roles = "USER")
class OpsBatchRunReportingIntegrationTest {

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@BeforeEach
	void setUp() {
		jdbcTemplate.update("DELETE FROM batch_runs");
	}

	@Test
	void listBatchRuns_forbiddenForUserRole() throws Exception {
		mockMvc.perform(get("/api/ops/batch-runs"))
				.andExpect(status().isForbidden());
	}

	@Test
	void getBatchRun_forbiddenForUserRole() throws Exception {
		mockMvc.perform(get("/api/ops/batch-runs/{runId}", 1L))
				.andExpect(status().isForbidden());
	}

	@Test
	@WithMockUser(username = "ops-user", roles = "OPS")
	void listBatchRuns_defaultsToLimit50AndSortsByCreatedAtDesc() throws Exception {
		Instant base = Instant.parse("2026-03-26T10:00:00Z");
		for (int i = 1; i <= 55; i++) {
			insertBatchRun(
					"BATCH-RUN-%02d".formatted(i),
					"RECONCILIATION",
					"COMPLETED",
					base.plusSeconds(i),
					0,
					null);
		}

		mockMvc.perform(get("/api/ops/batch-runs"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.limit").value(50))
				.andExpect(jsonPath("$.items.length()").value(50))
				.andExpect(jsonPath("$.items[0].batchName").value("BATCH-RUN-55"))
				.andExpect(jsonPath("$.items[49].batchName").value("BATCH-RUN-06"));
	}

	@Test
	@WithMockUser(username = "ops-user", roles = "OPS")
	void listBatchRuns_supportsBatchTypeAndStatusFilters() throws Exception {
		insertBatchRun("RECON-OK", "RECONCILIATION", "COMPLETED", Instant.parse("2026-03-26T11:00:00Z"), 1, null);
		insertBatchRun("RECON-FAILED", "RECONCILIATION", "FAILED", Instant.parse("2026-03-26T11:05:00Z"), 2, "timeout");
		insertBatchRun(
				"IDEMPOTENCY-OK",
				"IDEMPOTENCY_CLEANUP",
				"COMPLETED",
				Instant.parse("2026-03-26T11:10:00Z"),
				0,
				null);

		mockMvc.perform(get("/api/ops/batch-runs")
						.queryParam("batchType", "RECONCILIATION")
						.queryParam("status", "COMPLETED")
						.queryParam("limit", "50"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.limit").value(50))
				.andExpect(jsonPath("$.items.length()").value(1))
				.andExpect(jsonPath("$.items[0].batchName").value("RECON-OK"))
				.andExpect(jsonPath("$.items[0].batchType").value("RECONCILIATION"))
				.andExpect(jsonPath("$.items[0].status").value("COMPLETED"));
	}

	@Test
	@WithMockUser(username = "admin-user", roles = "ADMIN")
	void listBatchRuns_returnsBadRequestForInvalidStatusOrLimit() throws Exception {
		mockMvc.perform(get("/api/ops/batch-runs")
						.queryParam("status", "PENDING"))
				.andExpect(status().isBadRequest());

		mockMvc.perform(get("/api/ops/batch-runs")
						.queryParam("limit", "0"))
				.andExpect(status().isBadRequest());

		mockMvc.perform(get("/api/ops/batch-runs")
						.queryParam("limit", "201"))
				.andExpect(status().isBadRequest());
	}

	@Test
	@WithMockUser(username = "admin-user", roles = "ADMIN")
	void getBatchRun_returnsDetailAndNotFound() throws Exception {
		long runId = insertBatchRun(
				"PARTITION-CREATE-1",
				"PARTITION_MAINTENANCE",
				"COMPLETED",
				Instant.parse("2026-03-26T12:00:00Z"),
				3,
				"none");

		mockMvc.perform(get("/api/ops/batch-runs/{runId}", runId))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.runId").value(runId))
				.andExpect(jsonPath("$.batchName").value("PARTITION-CREATE-1"))
				.andExpect(jsonPath("$.batchType").value("PARTITION_MAINTENANCE"))
				.andExpect(jsonPath("$.status").value("COMPLETED"))
				.andExpect(jsonPath("$.retryCount").value(3));

		mockMvc.perform(get("/api/ops/batch-runs/{runId}", runId + 999))
				.andExpect(status().isNotFound());
	}

	private long insertBatchRun(
			String batchName,
			String batchType,
			String status,
			Instant createdAt,
			int retryCount,
			String errorMessage) {
		Long runId = jdbcTemplate.queryForObject(
				"""
				INSERT INTO batch_runs (
				    batch_name,
				    batch_type,
				    status,
				    started_at,
				    completed_at,
				    parameters_json,
				    result_json,
				    error_message,
				    retry_count,
				    created_at,
				    updated_at
				) VALUES (
				    ?,
				    ?,
				    ?,
				    CAST(? AS TIMESTAMP WITH TIME ZONE),
				    CASE WHEN ? = 'RUNNING' THEN NULL ELSE CAST(? AS TIMESTAMP WITH TIME ZONE) END,
				    CAST(? AS jsonb),
				    CAST(? AS jsonb),
				    ?,
				    ?,
				    CAST(? AS TIMESTAMP WITH TIME ZONE),
				    CAST(? AS TIMESTAMP WITH TIME ZONE)
				)
				RETURNING run_id
				""",
				Long.class,
				batchName,
				batchType,
				status,
				createdAt.toString(),
				status,
				createdAt.toString(),
				"{\"source\":\"test\"}",
				"{\"ok\":true}",
				errorMessage,
				retryCount,
				createdAt.toString(),
				createdAt.toString());

		return runId == null ? -1L : runId;
	}
}
