package com.corebank.corebank_api.ops.hotaccount;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.corebank.corebank_api.TestcontainersConfiguration;
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

@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = "spring.task.scheduling.enabled=false")
@AutoConfigureMockMvc
@WithMockUser(username = "viewer", roles = "USER")
class OpsHotAccountIntegrationTest {

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

	@BeforeEach
	void setUp() {
		jdbcTemplate.update("DELETE FROM ledger_account_balance_slots");
		jdbcTemplate.update("DELETE FROM hot_account_profiles");
		jdbcTemplate.update("DELETE FROM ledger_accounts WHERE account_code LIKE 'LEDGER-HOT-%'");
	}

	@Test
	void upsertProfile_forbiddenForUserRole() throws Exception {
		UUID ledgerAccountId = createLedgerAccount("LEDGER-HOT-FORBIDDEN");
		String body = objectMapper.writeValueAsString(Map.of(
				"slotCount", 4,
				"selectionStrategy", "HASH",
				"isActive", true));

		mockMvc.perform(put("/api/ops/hot-accounts/{ledgerAccountId}/profile", ledgerAccountId)
						.with(csrf())
						.contentType("application/json")
						.content(body))
				.andExpect(status().isForbidden());
	}

	@Test
	@WithMockUser(username = "ops-user", roles = "OPS")
	void upsertProfile_createsSlotsAndSupportsExpansion() throws Exception {
		UUID ledgerAccountId = createLedgerAccount("LEDGER-HOT-EXPAND");
		int auditCountBefore = countHotAccountAudit();

		mockMvc.perform(put("/api/ops/hot-accounts/{ledgerAccountId}/profile", ledgerAccountId)
						.with(csrf())
						.contentType("application/json")
						.content("{\"slotCount\":4,\"selectionStrategy\":\"HASH\",\"isActive\":true}"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.ledgerAccountId").value(ledgerAccountId.toString()))
				.andExpect(jsonPath("$.slotCount").value(4))
				.andExpect(jsonPath("$.selectionStrategy").value("HASH"))
				.andExpect(jsonPath("$.runtimeSelectionStrategyApplied").value("HASH"))
				.andExpect(jsonPath("$.runtimeStrategySemantics").value("NATIVE_HASH"))
				.andExpect(jsonPath("$.fallbackActive").value(false))
				.andExpect(jsonPath("$.fallbackReason").value(nullValue()))
				.andExpect(jsonPath("$.isActive").value(true))
				.andExpect(jsonPath("$.slots.length()").value(4))
				.andExpect(jsonPath("$.totalPostedBalanceMinor").value(0))
				.andExpect(jsonPath("$.totalAvailableBalanceMinor").value(0));

		Integer slotCountAfterFirstUpsert = jdbcTemplate.queryForObject(
				"SELECT COUNT(*) FROM ledger_account_balance_slots WHERE ledger_account_id = ?",
				Integer.class,
				ledgerAccountId);
		assertEquals(4, slotCountAfterFirstUpsert);

		mockMvc.perform(put("/api/ops/hot-accounts/{ledgerAccountId}/profile", ledgerAccountId)
						.with(csrf())
						.contentType("application/json")
						.content("{\"slotCount\":6,\"selectionStrategy\":\"ROUND_ROBIN\",\"isActive\":true}"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.slotCount").value(6))
				.andExpect(jsonPath("$.selectionStrategy").value("ROUND_ROBIN"))
				.andExpect(jsonPath("$.runtimeSelectionStrategyApplied").value("HASH"))
				.andExpect(jsonPath("$.runtimeStrategySemantics").value("HASH_FALLBACK"))
				.andExpect(jsonPath("$.fallbackActive").value(true))
				.andExpect(jsonPath("$.fallbackReason").value("CONFIGURED_STRATEGY_NOT_RUNTIME_IMPLEMENTED"))
				.andExpect(jsonPath("$.slots.length()").value(6));

		Integer slotCountAfterExpansion = jdbcTemplate.queryForObject(
				"SELECT COUNT(*) FROM ledger_account_balance_slots WHERE ledger_account_id = ?",
				Integer.class,
				ledgerAccountId);
		assertEquals(6, slotCountAfterExpansion);

		mockMvc.perform(get("/api/ops/hot-accounts/{ledgerAccountId}", ledgerAccountId))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.ledgerAccountId").value(ledgerAccountId.toString()))
				.andExpect(jsonPath("$.slotCount").value(6))
				.andExpect(jsonPath("$.selectionStrategy").value("ROUND_ROBIN"))
				.andExpect(jsonPath("$.runtimeSelectionStrategyApplied").value("HASH"))
				.andExpect(jsonPath("$.runtimeStrategySemantics").value("HASH_FALLBACK"))
				.andExpect(jsonPath("$.fallbackActive").value(true))
				.andExpect(jsonPath("$.fallbackReason").value("CONFIGURED_STRATEGY_NOT_RUNTIME_IMPLEMENTED"))
				.andExpect(jsonPath("$.slots.length()").value(6));

		int auditCountAfter = countHotAccountAudit();
		assertEquals(auditCountBefore + 2, auditCountAfter);
	}

	@Test
	@WithMockUser(username = "ops-user", roles = "OPS")
	void upsertProfile_rejectsReducingSlotCount() throws Exception {
		UUID ledgerAccountId = createLedgerAccount("LEDGER-HOT-CONFLICT");

		mockMvc.perform(put("/api/ops/hot-accounts/{ledgerAccountId}/profile", ledgerAccountId)
						.with(csrf())
						.contentType("application/json")
						.content("{\"slotCount\":5,\"selectionStrategy\":\"HASH\",\"isActive\":true}"))
				.andExpect(status().isOk());

		mockMvc.perform(put("/api/ops/hot-accounts/{ledgerAccountId}/profile", ledgerAccountId)
						.with(csrf())
						.contentType("application/json")
						.content("{\"slotCount\":3,\"selectionStrategy\":\"HASH\",\"isActive\":true}"))
				.andExpect(status().isConflict());
	}

	@Test
	@WithMockUser(username = "ops-user", roles = "OPS")
	void upsertProfile_validatesInputs() throws Exception {
		UUID ledgerAccountId = createLedgerAccount("LEDGER-HOT-INVALID");

		mockMvc.perform(put("/api/ops/hot-accounts/{ledgerAccountId}/profile", ledgerAccountId)
						.with(csrf())
						.contentType("application/json")
						.content("{\"slotCount\":1,\"selectionStrategy\":\"HASH\",\"isActive\":true}"))
				.andExpect(status().isBadRequest());

		mockMvc.perform(put("/api/ops/hot-accounts/{ledgerAccountId}/profile", ledgerAccountId)
						.with(csrf())
						.contentType("application/json")
						.content("{\"slotCount\":3,\"selectionStrategy\":\"UNKNOWN\",\"isActive\":true}"))
				.andExpect(status().isBadRequest());
	}

	@Test
	@WithMockUser(username = "admin-user", roles = "ADMIN")
	void getHotAccount_returnsNotFoundWhenProfileMissing() throws Exception {
		UUID ledgerAccountId = createLedgerAccount("LEDGER-HOT-MISSING");

		mockMvc.perform(get("/api/ops/hot-accounts/{ledgerAccountId}", ledgerAccountId))
				.andExpect(status().isNotFound());
	}

	@Test
	@WithMockUser(username = "ops-user", roles = "OPS")
	void getHotAccount_randomStrategy_reportsHashFallbackSemantics() throws Exception {
		UUID ledgerAccountId = createLedgerAccount("LEDGER-HOT-RANDOM");

		mockMvc.perform(put("/api/ops/hot-accounts/{ledgerAccountId}/profile", ledgerAccountId)
						.with(csrf())
						.contentType("application/json")
						.content("{\"slotCount\":4,\"selectionStrategy\":\"RANDOM\",\"isActive\":true}"))
				.andExpect(status().isOk());

		mockMvc.perform(get("/api/ops/hot-accounts/{ledgerAccountId}", ledgerAccountId))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.selectionStrategy").value("RANDOM"))
				.andExpect(jsonPath("$.runtimeSelectionStrategyApplied").value("HASH"))
				.andExpect(jsonPath("$.runtimeStrategySemantics").value("HASH_FALLBACK"))
				.andExpect(jsonPath("$.fallbackActive").value(true))
				.andExpect(jsonPath("$.fallbackReason").value("CONFIGURED_STRATEGY_NOT_RUNTIME_IMPLEMENTED"));
	}

	private UUID createLedgerAccount(String codePrefix) {
		UUID ledgerAccountId = UUID.randomUUID();
		String accountCode = codePrefix + "-" + ledgerAccountId.toString().substring(0, 8);

		jdbcTemplate.update(
				"""
				INSERT INTO ledger_accounts (
				    ledger_account_id,
				    account_code,
				    account_name,
				    account_type,
				    currency,
				    is_active
				) VALUES (?, ?, ?, 'ASSET', 'VND', true)
				""",
				ledgerAccountId,
				accountCode,
				"Hot Account " + accountCode);
		return ledgerAccountId;
	}

	private int countHotAccountAudit() {
		Integer count = jdbcTemplate.queryForObject(
				"""
				SELECT COUNT(*)
				FROM audit_events
				WHERE action = 'HOT_ACCOUNT_PROFILE_UPSERTED'
				  AND resource_type = 'HOT_ACCOUNT_PROFILE'
				""",
				Integer.class);
		return count == null ? 0 : count;
	}
}
