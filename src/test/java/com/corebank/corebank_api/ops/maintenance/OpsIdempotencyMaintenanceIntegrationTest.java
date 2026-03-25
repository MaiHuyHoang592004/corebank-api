package com.corebank.corebank_api.ops.maintenance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.corebank.corebank_api.TestcontainersConfiguration;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
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

@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = "spring.task.scheduling.enabled=false")
@AutoConfigureMockMvc
@WithMockUser(username = "viewer", roles = "USER")
class OpsIdempotencyMaintenanceIntegrationTest {

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

	@BeforeEach
	void setUp() {
		jdbcTemplate.update("DELETE FROM batch_runs WHERE batch_type = 'IDEMPOTENCY_CLEANUP'");
		jdbcTemplate.update("DELETE FROM idempotency_keys");
		setRuntimeMode("RUNNING");
	}

	@Test
	void cleanupIdempotency_forbiddenForUserRole() throws Exception {
		String body = objectMapper.writeValueAsString(Map.of(
				"limit", 1000,
				"dryRun", true));

		mockMvc.perform(post("/api/ops/maintenance/idempotency/cleanup")
						.with(csrf())
						.contentType("application/json")
						.content(body))
				.andExpect(status().isForbidden());
	}

	@Test
	@WithMockUser(username = "ops-user", roles = "OPS")
	void cleanupIdempotency_returnsConflictWhenSystemModeIsRunning() throws Exception {
		String body = objectMapper.writeValueAsString(Map.of(
				"limit", 1000,
				"dryRun", true));

		mockMvc.perform(post("/api/ops/maintenance/idempotency/cleanup")
						.with(csrf())
						.contentType("application/json")
						.content(body))
				.andExpect(status().isConflict());
	}

	@Test
	@WithMockUser(username = "ops-user", roles = "OPS")
	void cleanupIdempotency_defaultsToDryRunAndDoesNotDeleteData() throws Exception {
		setRuntimeMode("READ_ONLY");
		int auditCountBefore = countIdempotencyCleanupAudit();

		insertTerminalRecord("idem-succeeded-expired", "SUCCEEDED", 30);
		insertTerminalRecord("idem-failed-expired", "FAILED", 26);
		insertInProgressRecord("idem-inprogress-expired", 36);
		insertTerminalRecord("idem-succeeded-not-expired", "SUCCEEDED", 6);

		mockMvc.perform(post("/api/ops/maintenance/idempotency/cleanup")
						.with(csrf()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.dryRun").value(true))
				.andExpect(jsonPath("$.limit").value(1000))
				.andExpect(jsonPath("$.candidateCount").value(2))
				.andExpect(jsonPath("$.deletedCount").value(0))
				.andExpect(jsonPath("$.truncated").value(false));

		assertEquals(4, count("SELECT COUNT(*) FROM idempotency_keys"));
		assertEquals(1, count(
				"SELECT COUNT(*) FROM batch_runs WHERE batch_type = 'IDEMPOTENCY_CLEANUP' AND status = 'COMPLETED'"));
		assertEquals(auditCountBefore + 1, countIdempotencyCleanupAudit());
	}

	@Test
	@WithMockUser(username = "admin-user", roles = "ADMIN")
	void cleanupIdempotency_deletesExpiredTerminalRecordsWithLimitAndMarksTruncated() throws Exception {
		setRuntimeMode("MAINTENANCE");
		int auditCountBefore = countIdempotencyCleanupAudit();

		insertTerminalRecord("idem-terminal-oldest", "SUCCEEDED", 40);
		insertTerminalRecord("idem-terminal-middle", "FAILED", 35);
		insertTerminalRecord("idem-terminal-newest", "SUCCEEDED", 30);
		insertTerminalRecord("idem-terminal-not-expired", "SUCCEEDED", 10);
		insertInProgressRecord("idem-inprogress-expired", 50);

		String body = objectMapper.writeValueAsString(Map.of(
				"limit", 2,
				"dryRun", false));

		mockMvc.perform(post("/api/ops/maintenance/idempotency/cleanup")
						.with(csrf())
						.contentType("application/json")
						.content(body))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.dryRun").value(false))
				.andExpect(jsonPath("$.limit").value(2))
				.andExpect(jsonPath("$.candidateCount").value(3))
				.andExpect(jsonPath("$.deletedCount").value(2))
				.andExpect(jsonPath("$.truncated").value(true));

		assertEquals(0, count(
				"SELECT COUNT(*) FROM idempotency_keys WHERE idempotency_key = 'idem-terminal-oldest'"));
		assertEquals(0, count(
				"SELECT COUNT(*) FROM idempotency_keys WHERE idempotency_key = 'idem-terminal-middle'"));
		assertEquals(1, count(
				"SELECT COUNT(*) FROM idempotency_keys WHERE idempotency_key = 'idem-terminal-newest'"));
		assertEquals(1, count(
				"SELECT COUNT(*) FROM idempotency_keys WHERE idempotency_key = 'idem-terminal-not-expired'"));
		assertEquals(1, count(
				"SELECT COUNT(*) FROM idempotency_keys WHERE idempotency_key = 'idem-inprogress-expired'"));
		assertEquals(auditCountBefore + 1, countIdempotencyCleanupAudit());
	}

	@Test
	@WithMockUser(username = "admin-user", roles = "ADMIN")
	void cleanupIdempotency_returnsBadRequestForInvalidLimit() throws Exception {
		setRuntimeMode("READ_ONLY");

		mockMvc.perform(post("/api/ops/maintenance/idempotency/cleanup")
						.with(csrf())
						.contentType("application/json")
						.content("{\"limit\":0,\"dryRun\":false}"))
				.andExpect(status().isBadRequest());

		mockMvc.perform(post("/api/ops/maintenance/idempotency/cleanup")
						.with(csrf())
						.contentType("application/json")
						.content("{\"limit\":10001,\"dryRun\":false}"))
				.andExpect(status().isBadRequest());
	}

	private void insertTerminalRecord(String key, String status, int expiresHoursAgo) {
		Instant expiresAt = Instant.now().minus(Duration.ofHours(expiresHoursAgo));
		Instant createdAt = expiresAt.minus(Duration.ofHours(2));
		Instant completedAt = expiresAt.minus(Duration.ofHours(1));

		jdbcTemplate.update(
				"""
				INSERT INTO idempotency_keys (
				    idempotency_key,
				    request_hash,
				    status,
				    response_body,
				    created_at,
				    completed_at,
				    expires_at
				) VALUES (?, ?, ?, CAST(? AS jsonb), ?, ?, ?)
				""",
				key,
				"hash-" + key,
				status,
				"{}",
				Timestamp.from(createdAt),
				Timestamp.from(completedAt),
				Timestamp.from(expiresAt));
	}

	private void insertInProgressRecord(String key, int expiresHoursAgo) {
		Instant expiresAt = Instant.now().minus(Duration.ofHours(expiresHoursAgo));
		Instant createdAt = expiresAt.minus(Duration.ofHours(2));

		jdbcTemplate.update(
				"""
				INSERT INTO idempotency_keys (
				    idempotency_key,
				    request_hash,
				    status,
				    response_body,
				    created_at,
				    completed_at,
				    expires_at
				) VALUES (?, ?, 'IN_PROGRESS', NULL, ?, NULL, ?)
				""",
				key,
				"hash-" + key,
				Timestamp.from(createdAt),
				Timestamp.from(expiresAt));
	}

	private int count(String sql, Object... args) {
		Integer count = jdbcTemplate.queryForObject(sql, Integer.class, args);
		return count == null ? 0 : count;
	}

	private int countIdempotencyCleanupAudit() {
		return count(
				"""
				SELECT COUNT(*)
				FROM audit_events
				WHERE action = 'IDEMPOTENCY_KEYS_CLEANED'
				  AND resource_type = 'IDEMPOTENCY_CLEANUP'
				""");
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
