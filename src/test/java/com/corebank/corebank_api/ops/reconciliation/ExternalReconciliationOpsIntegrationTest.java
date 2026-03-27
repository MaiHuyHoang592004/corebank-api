package com.corebank.corebank_api.ops.reconciliation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.corebank.corebank_api.TestcontainersConfiguration;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
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
class ExternalReconciliationOpsIntegrationTest {

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@Autowired
	private ExternalReconciliationService externalReconciliationService;

	@Autowired
	private DataSource dataSource;

	private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

	@BeforeEach
	void setUp() {
		jdbcTemplate.execute("TRUNCATE TABLE ledger_postings, ledger_journals, audit_events RESTART IDENTITY CASCADE");
		jdbcTemplate.update("DELETE FROM external_settlement_entries");
		jdbcTemplate.update("DELETE FROM external_settlement_statements");
		jdbcTemplate.update("DELETE FROM reconciliation_breaks");
		jdbcTemplate.update("DELETE FROM batch_runs");
		jdbcTemplate.update("DELETE FROM ledger_accounts WHERE account_code LIKE 'LEDGER-EXT-%'");
		setRuntimeMode("READ_ONLY");
	}

	@Test
	void runExternalReconciliation_forbiddenForUserRole() throws Exception {
		String body = requestBody(
				"stmt-user-1",
				List.of(entryPayload("E-1", "PAYMENT_ORDER", UUID.randomUUID().toString(), "VND", 100_000L, "POSTED")),
				false,
				2000);

		mockMvc.perform(post("/api/ops/reconciliation/external/runs")
						.with(csrf())
						.contentType("application/json")
						.content(body))
				.andExpect(status().isForbidden());
	}

	@Test
	@WithMockUser(username = "ops-user", roles = "OPS")
	void runExternalReconciliation_returnsConflictWhenSystemModeIsRunning() throws Exception {
		setRuntimeMode("RUNNING");

		String body = requestBody(
				"stmt-running-1",
				List.of(entryPayload("E-1", "PAYMENT_ORDER", UUID.randomUUID().toString(), "VND", 100_000L, "POSTED")),
				false,
				2000);

		mockMvc.perform(post("/api/ops/reconciliation/external/runs")
						.with(csrf())
						.contentType("application/json")
						.content(body))
				.andExpect(status().isConflict());
	}

	@Test
	@WithMockUser(username = "ops-user", roles = "OPS")
	void runExternalReconciliation_dryRunDoesNotMutateEvidenceTablesButCreatesBatchRun() throws Exception {
		UUID referenceId = UUID.randomUUID();
		insertJournalWithPosting("PAYMENT_ORDER", referenceId, "VND", 100_000L, "2026-03-25T08:00:00Z");

		String body = requestBody(
				"stmt-dryrun-1",
				List.of(
						entryPayload("E-1", "PAYMENT_ORDER", referenceId.toString(), "VND", 100_000L, "POSTED"),
						entryPayload("E-2", "PAYMENT_ORDER", UUID.randomUUID().toString(), "VND", 50_000L, "POSTED")),
				true,
				2000);

		mockMvc.perform(post("/api/ops/reconciliation/external/runs")
						.with(csrf())
						.contentType("application/json")
						.content(body))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.dryRun").value(true))
				.andExpect(jsonPath("$.breakCount").value(1))
				.andExpect(jsonPath("$.orphanExternalCount").value(1));

		assertTableCount("external_settlement_statements", 0);
		assertTableCount("external_settlement_entries", 0);
		assertTableCount("reconciliation_breaks", 0);
		assertTableCount("batch_runs", 1);
	}

	@Test
	@WithMockUser(username = "ops-user", roles = "OPS")
	void runExternalReconciliation_rerunCreatesNewVersionAndSingleLatest() throws Exception {
		UUID referenceId = UUID.randomUUID();
		insertJournalWithPosting("PAYMENT_ORDER", referenceId, "VND", 100_000L, "2026-03-25T08:05:00Z");

		String body = requestBody(
				"stmt-rerun-1",
				List.of(entryPayload("E-1", "PAYMENT_ORDER", referenceId.toString(), "VND", 100_000L, "POSTED")),
				false,
				2000);

		mockMvc.perform(post("/api/ops/reconciliation/external/runs")
						.with(csrf())
						.contentType("application/json")
						.content(body))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.statementVersion").value(1));

		mockMvc.perform(post("/api/ops/reconciliation/external/runs")
						.with(csrf())
						.contentType("application/json")
						.content(body))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.statementVersion").value(2));

		assertTableCount("external_settlement_statements", 2);
		Integer latestCount = jdbcTemplate.queryForObject(
				"SELECT COUNT(*) FROM external_settlement_statements WHERE statement_ref = ? AND is_latest = true",
				Integer.class,
				"stmt-rerun-1");
		assertEquals(1, latestCount);

		List<Integer> versions = jdbcTemplate.queryForList(
				"SELECT version_no FROM external_settlement_statements WHERE statement_ref = ? ORDER BY version_no",
				Integer.class,
				"stmt-rerun-1");
		assertEquals(List.of(1, 2), versions);
	}

	@Test
	void externalReconciliation_deterministicForReorderedEntries() {
		UUID referenceId = UUID.randomUUID();
		insertJournalWithPosting("PAYMENT_ORDER", referenceId, "VND", 100_000L, "2026-03-25T09:00:00Z");

		ExternalReconciliationService.ExternalSettlementEntryInput matchEntry = entryInput(
				"E-match",
				"PAYMENT_ORDER",
				referenceId.toString(),
				"VND",
				100_000L,
				"POSTED");
		ExternalReconciliationService.ExternalSettlementEntryInput orphanEntry = entryInput(
				"E-orphan",
				"PAYMENT_ORDER",
				UUID.randomUUID().toString(),
				"VND",
				90_000L,
				"POSTED");

		ExternalReconciliationService.ExternalReconciliationRunResult first = externalReconciliationService.run(
				new ExternalReconciliationService.ExternalReconciliationRunCommand(
						"stmt-order-a",
						"PROVIDER-A",
						LocalDate.parse("2026-03-25"),
						2000,
						true,
						List.of(matchEntry, orphanEntry),
						"ops-user"));

		ExternalReconciliationService.ExternalReconciliationRunResult second = externalReconciliationService.run(
				new ExternalReconciliationService.ExternalReconciliationRunCommand(
						"stmt-order-b",
						"PROVIDER-A",
						LocalDate.parse("2026-03-25"),
						2000,
						true,
						List.of(orphanEntry, matchEntry),
						"ops-user"));

		assertEquals(first.breakCount(), second.breakCount());
		assertEquals(first.orphanExternalCount(), second.orphanExternalCount());
		assertEquals(first.matchedCount(), second.matchedCount());
		assertEquals(first.truncated(), second.truncated());
	}

	@Test
	void externalReconciliation_marksTruncatedGroupAsAmbiguous() {
		String sharedRef = UUID.randomUUID().toString();
		ExternalReconciliationService.ExternalReconciliationRunResult result = externalReconciliationService.run(
				new ExternalReconciliationService.ExternalReconciliationRunCommand(
						"stmt-truncated-1",
						"PROVIDER-A",
						LocalDate.parse("2026-03-25"),
						2,
						true,
						List.of(
								entryInput("E-1", "PAYMENT_ORDER", sharedRef, "VND", 100_000L, "POSTED"),
								entryInput("E-2", "PAYMENT_ORDER", sharedRef, "VND", 100_000L, "POSTED"),
								entryInput("E-3", "PAYMENT_ORDER", sharedRef, "VND", 100_000L, "POSTED")),
						"ops-user"));

		assertTrue(result.truncated());
		assertEquals(1, result.ambiguousCount());
		assertEquals(1, result.breakCount());
	}

	@Test
	void externalReconciliation_blocksWhileAdvisoryLockHeldOnSameStatementRef() throws Exception {
		String statementRef = "stmt-lock-1";
		ExternalReconciliationService.ExternalReconciliationRunCommand command = new ExternalReconciliationService.ExternalReconciliationRunCommand(
				statementRef,
				"PROVIDER-A",
				LocalDate.parse("2026-03-25"),
				2000,
				false,
				List.of(entryInput("E-1", "PAYMENT_ORDER", UUID.randomUUID().toString(), "VND", 100_000L, "POSTED")),
				"ops-user");

		ExecutorService executor = Executors.newSingleThreadExecutor();
		try (Connection connection = dataSource.getConnection()) {
			connection.setAutoCommit(false);
			try (PreparedStatement lockStatement = connection.prepareStatement(
					"SELECT pg_advisory_xact_lock(hashtext(?))")) {
				lockStatement.setString(1, "external-reconciliation:" + statementRef);
				lockStatement.execute();
			}

			Future<ExternalReconciliationService.ExternalReconciliationRunResult> future =
					executor.submit(() -> externalReconciliationService.run(command));

			Thread.sleep(300);
			assertFalse(future.isDone());

			connection.commit();
			ExternalReconciliationService.ExternalReconciliationRunResult result = future.get(10, TimeUnit.SECONDS);
			assertNotNull(result);
			assertEquals(statementRef, result.statementRef());
			assertEquals(1, result.statementVersion());
		} finally {
			executor.shutdownNow();
		}
	}

	@Test
	void externalReconciliation_parallelImportsKeepSingleLatest() throws Exception {
		String statementRef = "stmt-race-1";
		ExecutorService executor = Executors.newFixedThreadPool(2);
		CyclicBarrier barrier = new CyclicBarrier(2);
		try {
			Callable<ExternalReconciliationService.ExternalReconciliationRunResult> task = () -> {
				barrier.await(5, TimeUnit.SECONDS);
				return externalReconciliationService.run(new ExternalReconciliationService.ExternalReconciliationRunCommand(
						statementRef,
						"PROVIDER-A",
						LocalDate.parse("2026-03-25"),
						2000,
						false,
						List.of(entryInput("E-1", "PAYMENT_ORDER", UUID.randomUUID().toString(), "VND", 100_000L, "POSTED")),
						"ops-user"));
			};

			Future<ExternalReconciliationService.ExternalReconciliationRunResult> first = executor.submit(task);
			Future<ExternalReconciliationService.ExternalReconciliationRunResult> second = executor.submit(task);
			first.get(20, TimeUnit.SECONDS);
			second.get(20, TimeUnit.SECONDS);

			List<Integer> versions = jdbcTemplate.queryForList(
					"SELECT version_no FROM external_settlement_statements WHERE statement_ref = ? ORDER BY version_no",
					Integer.class,
					statementRef);
			assertEquals(List.of(1, 2), versions);

			Integer latestCount = jdbcTemplate.queryForObject(
					"SELECT COUNT(*) FROM external_settlement_statements WHERE statement_ref = ? AND is_latest = true",
					Integer.class,
					statementRef);
			assertEquals(1, latestCount);
		} finally {
			executor.shutdownNow();
		}
	}

	@Test
	void migrationV25_createsRequiredIndexes() {
		assertIndexExists("ux_ext_stmt_single_latest");
		assertIndexExists("ix_ext_stmt_ref_version_desc");
		assertIndexExists("ix_ext_entries_match_key");
		assertIndexExists("ix_ledger_journals_ref_id_currency_created");
	}

	private String requestBody(
			String statementRef,
			List<Map<String, Object>> entries,
			boolean dryRun,
			int processingLimit) throws Exception {
		return objectMapper.writeValueAsString(Map.of(
				"statementRef", statementRef,
				"provider", "PROVIDER-A",
				"statementDate", "2026-03-25",
				"processingLimit", processingLimit,
				"dryRun", dryRun,
				"entries", entries));
	}

	private Map<String, Object> entryPayload(
			String externalRef,
			String referenceType,
			String referenceId,
			String currency,
			long amountMinor,
			String status) {
		return Map.of(
				"externalRef", externalRef,
				"referenceType", referenceType,
				"referenceId", referenceId,
				"currency", currency,
				"amountMinor", amountMinor,
				"status", status,
				"metadata", Map.of("source", "test"));
	}

	private ExternalReconciliationService.ExternalSettlementEntryInput entryInput(
			String externalRef,
			String referenceType,
			String referenceId,
			String currency,
			long amountMinor,
			String status) {
		return new ExternalReconciliationService.ExternalSettlementEntryInput(
				externalRef,
				referenceType,
				referenceId,
				currency,
				amountMinor,
				status,
				Map.of("source", "test"));
	}

	private void insertJournalWithPosting(
			String referenceType,
			UUID referenceId,
			String currency,
			long amountMinor,
			String createdAtIso) {
		UUID journalId = UUID.randomUUID();
		UUID debitLedgerAccountId = createLedgerAccount("ASSET");
		UUID creditLedgerAccountId = createLedgerAccount("LIABILITY");

		jdbcTemplate.update(
				"""
				INSERT INTO ledger_journals (
				    journal_id,
				    journal_type,
				    reference_type,
				    reference_id,
				    currency,
				    reversal_of_journal_id,
				    created_by_actor,
				    correlation_id,
				    created_at,
				    prev_row_hash,
				    row_hash
				) VALUES (?, 'EXTERNAL_TEST', ?, ?, ?, NULL, 'test-suite', NULL, CAST(? AS TIMESTAMP WITH TIME ZONE), ?, ?)
				""",
				journalId,
				referenceType,
				referenceId,
				currency,
				createdAtIso,
				new byte[] {0x01},
				new byte[] {0x02});

		jdbcTemplate.update(
				"""
				INSERT INTO ledger_postings (
				    journal_id,
				    ledger_account_id,
				    customer_account_id,
				    entry_side,
				    amount_minor,
				    currency,
				    created_at
				) VALUES (?, ?, NULL, 'D', ?, ?, CAST(? AS TIMESTAMP WITH TIME ZONE))
				""",
				journalId,
				debitLedgerAccountId,
				amountMinor,
				currency,
				createdAtIso);

		jdbcTemplate.update(
				"""
				INSERT INTO ledger_postings (
				    journal_id,
				    ledger_account_id,
				    customer_account_id,
				    entry_side,
				    amount_minor,
				    currency,
				    created_at
				) VALUES (?, ?, NULL, 'C', ?, ?, CAST(? AS TIMESTAMP WITH TIME ZONE))
				""",
				journalId,
				creditLedgerAccountId,
				amountMinor,
				currency,
				createdAtIso);
	}

	private UUID createLedgerAccount(String accountType) {
		UUID ledgerAccountId = UUID.randomUUID();
		String accountCode = "LEDGER-EXT-" + ledgerAccountId.toString().substring(0, 8);
		jdbcTemplate.update(
				"""
				INSERT INTO ledger_accounts (
				    ledger_account_id,
				    account_code,
				    account_name,
				    account_type,
				    currency,
				    is_active
				) VALUES (?, ?, ?, ?, 'VND', true)
				""",
				ledgerAccountId,
				accountCode,
				"External " + accountCode,
				accountType);
		return ledgerAccountId;
	}

	private void assertTableCount(String tableName, int expectedCount) {
		Integer count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM " + tableName, Integer.class);
		assertEquals(expectedCount, count == null ? 0 : count);
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

	private void assertIndexExists(String indexName) {
		Integer count = jdbcTemplate.queryForObject(
				"""
				SELECT COUNT(*)
				FROM pg_indexes
				WHERE schemaname = 'public'
				  AND indexname = ?
				""",
				Integer.class,
				indexName);
		assertTrue((count == null ? 0 : count) > 0, "Missing index: " + indexName);
	}
}
