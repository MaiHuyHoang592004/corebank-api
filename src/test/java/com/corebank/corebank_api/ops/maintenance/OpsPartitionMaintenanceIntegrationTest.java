package com.corebank.corebank_api.ops.maintenance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.corebank.corebank_api.TestcontainersConfiguration;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
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
import org.springframework.test.web.servlet.MvcResult;

@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = "spring.task.scheduling.enabled=false")
@AutoConfigureMockMvc
@WithMockUser(username = "viewer", roles = "USER")
class OpsPartitionMaintenanceIntegrationTest {

	private static final DateTimeFormatter MONTH_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM");
	private static final DateTimeFormatter PARTITION_SUFFIX_FORMATTER = DateTimeFormatter.ofPattern("yyyy_MM");

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

	@BeforeEach
	void setUp() {
		jdbcTemplate.update("DELETE FROM batch_runs WHERE batch_type = 'PARTITION_MAINTENANCE'");
		setRuntimeMode("RUNNING");
	}

	@Test
	void ensureFuturePartitions_forbiddenForUserRole() throws Exception {
		String body = objectMapper.writeValueAsString(Map.of(
				"fromMonth", "2099-01",
				"monthsAhead", 2));

		mockMvc.perform(post("/api/ops/maintenance/partitions/ensure-future")
						.with(csrf())
						.contentType("application/json")
						.content(body))
				.andExpect(status().isForbidden());
	}

	@Test
	@WithMockUser(username = "ops-user", roles = "OPS")
	void ensureFuturePartitions_returnsConflictWhenSystemModeIsRunning() throws Exception {
		String body = objectMapper.writeValueAsString(Map.of(
				"fromMonth", "2099-02",
				"monthsAhead", 2));

		mockMvc.perform(post("/api/ops/maintenance/partitions/ensure-future")
						.with(csrf())
						.contentType("application/json")
						.content(body))
				.andExpect(status().isConflict());
	}

	@Test
	@WithMockUser(username = "ops-user", roles = "OPS")
	void ensureFuturePartitions_createsAndIsIdempotent() throws Exception {
		setRuntimeMode("READ_ONLY");

		YearMonth fromMonth = YearMonth.now().plusYears(20).plusMonths(5);
		String fromMonthValue = fromMonth.format(MONTH_FORMATTER);
		int auditCountBefore = countPartitionMaintenanceAudit();
		String body = objectMapper.writeValueAsString(Map.of(
				"fromMonth", fromMonthValue,
				"monthsAhead", 2));

		MvcResult firstResult = mockMvc.perform(post("/api/ops/maintenance/partitions/ensure-future")
						.with(csrf())
						.contentType("application/json")
						.content(body))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.fromMonth").value(fromMonthValue))
				.andExpect(jsonPath("$.monthsAhead").value(2))
				.andExpect(jsonPath("$.errorCount").value(0))
				.andExpect(jsonPath("$.items.length()").value(6))
				.andReturn();

		JsonNode firstJson = objectMapper.readTree(firstResult.getResponse().getContentAsString());
		assertEquals(6, firstJson.get("createdCount").asInt() + firstJson.get("existingCount").asInt());
		long runId = firstJson.get("runId").asLong();

		Integer completedBatchCount = jdbcTemplate.queryForObject(
				"""
				SELECT COUNT(*)
				FROM batch_runs
				WHERE run_id = ?
				  AND batch_type = 'PARTITION_MAINTENANCE'
				  AND status = 'COMPLETED'
				""",
				Integer.class,
				runId);
		assertEquals(1, completedBatchCount);

		int auditCountAfter = countPartitionMaintenanceAudit();
		assertEquals(auditCountBefore + 1, auditCountAfter);

		assertPartitionExists("ledger_journals_p", fromMonth);
		assertPartitionExists("ledger_journals_p", fromMonth.plusMonths(1));
		assertPartitionExists("ledger_postings_p", fromMonth);
		assertPartitionExists("ledger_postings_p", fromMonth.plusMonths(1));
		assertPartitionExists("audit_events_p", fromMonth);
		assertPartitionExists("audit_events_p", fromMonth.plusMonths(1));

		mockMvc.perform(post("/api/ops/maintenance/partitions/ensure-future")
						.with(csrf())
						.contentType("application/json")
						.content(body))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.createdCount").value(0))
				.andExpect(jsonPath("$.existingCount").value(6))
				.andExpect(jsonPath("$.errorCount").value(0))
				.andExpect(jsonPath("$.items.length()").value(6));
	}

	@Test
	@WithMockUser(username = "admin-user", roles = "ADMIN")
	void ensureFuturePartitions_returnsBadRequestForInvalidInput() throws Exception {
		setRuntimeMode("MAINTENANCE");

		mockMvc.perform(post("/api/ops/maintenance/partitions/ensure-future")
						.with(csrf())
						.contentType("application/json")
						.content("{\"fromMonth\":\"2026/03\",\"monthsAhead\":2}"))
				.andExpect(status().isBadRequest());

		mockMvc.perform(post("/api/ops/maintenance/partitions/ensure-future")
						.with(csrf())
						.contentType("application/json")
						.content("{\"fromMonth\":\"2026-03\",\"monthsAhead\":13}"))
				.andExpect(status().isBadRequest());
	}

	private void assertPartitionExists(String parentTable, YearMonth month) {
		String partitionName = parentTable + "_" + month.format(PARTITION_SUFFIX_FORMATTER);
		Boolean exists = jdbcTemplate.queryForObject(
				"SELECT to_regclass(?) IS NOT NULL",
				Boolean.class,
				partitionName);
		assertTrue(Boolean.TRUE.equals(exists), "Expected partition to exist: " + partitionName);
	}

	private void setRuntimeMode(String status) {
		jdbcTemplate.update(
				"""
				UPDATE system_configs
				SET config_value = jsonb_set(config_value, '{status}', to_jsonb(?::text)),
				    updated_at = now(),
				    updated_by = 'test-suite'
				WHERE config_key = 'runtime_mode'
				""",
				status);
	}

	private int countPartitionMaintenanceAudit() {
		Integer count = jdbcTemplate.queryForObject(
				"""
				SELECT COUNT(*)
				FROM audit_events
				WHERE action = 'PARTITION_MAINTENANCE_EXECUTED'
				  AND resource_type = 'PARTITION_MAINTENANCE'
				""",
				Integer.class);
		return count == null ? 0 : count;
	}
}
