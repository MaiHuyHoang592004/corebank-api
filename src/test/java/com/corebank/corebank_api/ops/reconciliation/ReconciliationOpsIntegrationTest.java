package com.corebank.corebank_api.ops.reconciliation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.corebank.corebank_api.TestcontainersConfiguration;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import java.util.UUID;
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
class ReconciliationOpsIntegrationTest {

	private static final UUID DEFAULT_PRODUCT_ID = UUID.fromString("a1b2c3d4-e5f6-7890-abcd-ef1234567801");

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

	@BeforeEach
	void setUp() {
		jdbcTemplate.update("DELETE FROM reconciliation_breaks");
		jdbcTemplate.update("DELETE FROM batch_runs");
		jdbcTemplate.update("DELETE FROM account_balance_snapshots");
		jdbcTemplate.update("DELETE FROM customer_accounts");
		jdbcTemplate.update("DELETE FROM customers");
		jdbcTemplate.update("DELETE FROM audit_events WHERE action = 'RECONCILIATION_RUN_EXECUTED'");
		setRuntimeMode("RUNNING");
	}

	@Test
	void runReconciliation_forbiddenForUserRole() throws Exception {
		String body = objectMapper.writeValueAsString(Map.of(
				"businessDate", "2026-03-25",
				"limit", 10));

		mockMvc.perform(post("/api/ops/reconciliation/runs")
						.with(csrf())
						.contentType("application/json")
						.content(body))
				.andExpect(status().isForbidden());
	}

	@Test
	@WithMockUser(username = "ops-user", roles = "OPS")
	void runReconciliation_returnsConflictWhenSystemModeIsRunning() throws Exception {
		String body = objectMapper.writeValueAsString(Map.of("businessDate", "2026-03-25"));

		mockMvc.perform(post("/api/ops/reconciliation/runs")
						.with(csrf())
						.contentType("application/json")
						.content(body))
				.andExpect(status().isConflict());
	}

	@Test
	@WithMockUser(username = "ops-user", roles = "OPS")
	void runReconciliation_createsMissingAndMismatchBreaksAndWritesAudit() throws Exception {
		setRuntimeMode("READ_ONLY");

		UUID mismatchAccountId = createActiveAccount(1_000L, 900L);
		UUID missingAccountId = createActiveAccount(2_000L, 1_800L);
		UUID matchedAccountId = createActiveAccount(3_000L, 2_900L);

		insertSnapshot(mismatchAccountId, "2026-03-25", 900L, 900L);
		insertSnapshot(matchedAccountId, "2026-03-25", 3_000L, 2_900L);

		String body = objectMapper.writeValueAsString(Map.of(
				"businessDate", "2026-03-25",
				"limit", 10));

		MvcResult mvcResult = mockMvc.perform(post("/api/ops/reconciliation/runs")
						.with(csrf())
						.contentType("application/json")
						.content(body))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.businessDate").value("2026-03-25"))
				.andExpect(jsonPath("$.checkedCount").value(3))
				.andExpect(jsonPath("$.breakCount").value(2))
				.andExpect(jsonPath("$.missingCount").value(1))
				.andExpect(jsonPath("$.mismatchCount").value(1))
				.andExpect(jsonPath("$.truncated").value(false))
				.andReturn();

		JsonNode response = objectMapper.readTree(mvcResult.getResponse().getContentAsString());
		long runId = response.get("runId").asLong();

		Integer breakCount = jdbcTemplate.queryForObject(
				"SELECT COUNT(*) FROM reconciliation_breaks WHERE details_json->>'runId' = ?",
				Integer.class,
				String.valueOf(runId));
		assertEquals(2, breakCount);

		java.util.List<String> breakTypes = jdbcTemplate.queryForList(
				"""
				SELECT break_type
				FROM reconciliation_breaks
				WHERE details_json->>'runId' = ?
				ORDER BY break_type
				""",
				String.class,
				String.valueOf(runId));
		org.junit.jupiter.api.Assertions.assertIterableEquals(
				java.util.List.of("AMOUNT_MISMATCH", "MISSING_ENTRY"),
				breakTypes);

		Integer detailShapeCount = jdbcTemplate.queryForObject(
				"""
				SELECT COUNT(*)
				FROM reconciliation_breaks
				WHERE details_json->>'runId' = ?
				  AND details_json->>'businessDate' IS NOT NULL
				  AND details_json->>'customerAccountId' IS NOT NULL
				  AND details_json->>'accountPosted' IS NOT NULL
				  AND details_json->>'accountAvailable' IS NOT NULL
				  AND details_json->'snapshotPosted' IS NOT NULL
				  AND details_json->'snapshotAvailable' IS NOT NULL
				  AND details_json->>'deltaPosted' IS NOT NULL
				  AND details_json->>'deltaAvailable' IS NOT NULL
				""",
				Integer.class,
				String.valueOf(runId));
		assertEquals(2, detailShapeCount);

		Integer auditCount = jdbcTemplate.queryForObject(
				"""
				SELECT COUNT(*)
				FROM audit_events
				WHERE action = 'RECONCILIATION_RUN_EXECUTED'
				  AND resource_type = 'RECONCILIATION_RUN'
				  AND resource_id = ?
				""",
				Integer.class,
				String.valueOf(runId));
		assertEquals(1, auditCount);

		Integer completedBatchCount = jdbcTemplate.queryForObject(
				"""
				SELECT COUNT(*)
				FROM batch_runs
				WHERE run_id = ?
				  AND status = 'COMPLETED'
				  AND batch_type = 'RECONCILIATION'
				""",
				Integer.class,
				runId);
		assertEquals(1, completedBatchCount);

		java.util.List<String> referenceIds = jdbcTemplate.queryForList(
				"""
				SELECT reference_id
				FROM reconciliation_breaks
				WHERE details_json->>'runId' = ?
				""",
				String.class,
				String.valueOf(runId));
		org.junit.jupiter.api.Assertions.assertTrue(referenceIds.contains(mismatchAccountId.toString()));
		org.junit.jupiter.api.Assertions.assertTrue(referenceIds.contains(missingAccountId.toString()));
		org.junit.jupiter.api.Assertions.assertFalse(referenceIds.contains(matchedAccountId.toString()));
	}

	@Test
	@WithMockUser(username = "admin-user", roles = "ADMIN")
	void runReconciliation_returnsBadRequestWhenBusinessDateMissingOrInvalid() throws Exception {
		setRuntimeMode("READ_ONLY");

		mockMvc.perform(post("/api/ops/reconciliation/runs")
						.with(csrf())
						.contentType("application/json")
						.content("{}"))
				.andExpect(status().isBadRequest());

		mockMvc.perform(post("/api/ops/reconciliation/runs")
						.with(csrf())
						.contentType("application/json")
						.content("{\"businessDate\":\"2026/03/25\"}"))
				.andExpect(status().isBadRequest());
	}

	private UUID createActiveAccount(long postedBalanceMinor, long availableBalanceMinor) {
		UUID customerId = UUID.randomUUID();
		UUID customerAccountId = UUID.randomUUID();

		jdbcTemplate.update(
				"""
				INSERT INTO customers (customer_id, customer_type, full_name, email, phone, status, risk_band)
				VALUES (?, 'INDIVIDUAL', ?, ?, ?, 'ACTIVE', 'LOW')
				""",
				customerId,
				"Customer-" + customerId,
				"customer-" + customerId + "@example.com",
				"090-" + customerId.toString().substring(0, 8));

		jdbcTemplate.update(
				"""
				INSERT INTO customer_accounts (
				    customer_account_id,
				    customer_id,
				    product_id,
				    account_number,
				    currency,
				    status,
				    posted_balance_minor,
				    available_balance_minor,
				    version
				) VALUES (?, ?, ?, ?, 'VND', 'ACTIVE', ?, ?, 0)
				""",
				customerAccountId,
				customerId,
				DEFAULT_PRODUCT_ID,
				"ACC-" + customerAccountId.toString().substring(0, 8),
				postedBalanceMinor,
				availableBalanceMinor);
		return customerAccountId;
	}

	private void insertSnapshot(UUID customerAccountId, String snapshotDate, long postedBalance, long availableBalance) {
		jdbcTemplate.update(
				"""
				INSERT INTO account_balance_snapshots (
				    customer_account_id,
				    snapshot_date,
				    posted_balance,
				    available_balance,
				    currency
				) VALUES (?, CAST(? AS DATE), ?, ?, 'VND')
				""",
				customerAccountId,
				snapshotDate,
				postedBalance,
				availableBalance);
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
}
