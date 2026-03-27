package com.corebank.corebank_api.ledger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.corebank.corebank_api.TestcontainersConfiguration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = "spring.task.scheduling.enabled=false")
class LedgerHotAccountRuntimeIntegrationTest {

	@Autowired
	private LedgerCommandService ledgerCommandService;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@Test
	void postJournal_activeHotAccount_updatesSingleSlotAndAggregates() {
		UUID hotLedgerAccountId = createLedgerAccount("LEDGER-HOT-ACTIVE");
		UUID offsetLedgerAccountId = createLedgerAccount("LEDGER-HOT-OFFSET");
		insertHotProfile(hotLedgerAccountId, 4, "HASH", true);
		insertSlots(hotLedgerAccountId, 4);

		UUID referenceId = UUID.randomUUID();
		UUID journalId = ledgerCommandService.postJournal(new LedgerCommandService.PostJournalCommand(
				"HOT_RUNTIME_TEST",
				"HOT_RUNTIME_TEST_REF",
				referenceId,
				"VND",
				null,
				"test-suite",
				UUID.randomUUID(),
				List.of(
						new LedgerCommandService.PostingInstruction(
								hotLedgerAccountId,
								null,
								"D",
								700L,
								"VND",
								false),
						new LedgerCommandService.PostingInstruction(
								offsetLedgerAccountId,
								null,
								"C",
								700L,
								"VND",
								false))));

		Map<String, Object> aggregate = jdbcTemplate.queryForMap(
				"""
				SELECT COALESCE(SUM(posted_balance_minor), 0) AS posted_sum,
				       COALESCE(SUM(available_balance_minor), 0) AS available_sum
				FROM ledger_account_balance_slots
				WHERE ledger_account_id = ?
				""",
				hotLedgerAccountId);
		assertEquals(-700L, ((Number) aggregate.get("posted_sum")).longValue());
		assertEquals(-700L, ((Number) aggregate.get("available_sum")).longValue());

		List<Map<String, Object>> changedSlots = jdbcTemplate.queryForList(
				"""
				SELECT slot_no, posted_balance_minor, available_balance_minor
				FROM ledger_account_balance_slots
				WHERE ledger_account_id = ?
				  AND (posted_balance_minor <> 0 OR available_balance_minor <> 0)
				ORDER BY slot_no ASC
				""",
				hotLedgerAccountId);
		assertEquals(1, changedSlots.size());
		int expectedSlot = Math.floorMod(
				Objects.hash(referenceId, hotLedgerAccountId, "D", 700L),
				4);
		assertEquals(expectedSlot, ((Number) changedSlots.get(0).get("slot_no")).intValue());
		assertEquals(-700L, ((Number) changedSlots.get(0).get("posted_balance_minor")).longValue());
		assertEquals(-700L, ((Number) changedSlots.get(0).get("available_balance_minor")).longValue());

		Integer slottingAuditCount = jdbcTemplate.queryForObject(
				"""
				SELECT COUNT(*)
				FROM audit_events
				WHERE action = 'HOT_ACCOUNT_SLOTTING_APPLIED'
				  AND resource_type = 'LEDGER_JOURNAL'
				  AND resource_id = ?
				""",
				Integer.class,
				journalId.toString());
		assertEquals(1, slottingAuditCount);
	}

	@Test
	void postJournal_missingOrInactiveProfile_doesNotTouchSlots() {
		UUID noProfileLedgerAccountId = createLedgerAccount("LEDGER-HOT-MISSING");
		UUID inactiveLedgerAccountId = createLedgerAccount("LEDGER-HOT-INACTIVE");
		UUID offsetLedgerAccountId = createLedgerAccount("LEDGER-HOT-OFFSET-NOOP");
		insertHotProfile(inactiveLedgerAccountId, 3, "HASH", false);
		insertSlots(inactiveLedgerAccountId, 3);

		UUID journalNoProfile = ledgerCommandService.postJournal(new LedgerCommandService.PostJournalCommand(
				"HOT_RUNTIME_TEST",
				"HOT_RUNTIME_TEST_REF",
				UUID.randomUUID(),
				"VND",
				null,
				"test-suite",
				UUID.randomUUID(),
				List.of(
						new LedgerCommandService.PostingInstruction(
								noProfileLedgerAccountId,
								null,
								"D",
								400L,
								"VND",
								false),
						new LedgerCommandService.PostingInstruction(
								offsetLedgerAccountId,
								null,
								"C",
								400L,
								"VND",
								false))));

		UUID journalInactive = ledgerCommandService.postJournal(new LedgerCommandService.PostJournalCommand(
				"HOT_RUNTIME_TEST",
				"HOT_RUNTIME_TEST_REF",
				UUID.randomUUID(),
				"VND",
				null,
				"test-suite",
				UUID.randomUUID(),
				List.of(
						new LedgerCommandService.PostingInstruction(
								inactiveLedgerAccountId,
								null,
								"D",
								500L,
								"VND",
								false),
						new LedgerCommandService.PostingInstruction(
								offsetLedgerAccountId,
								null,
								"C",
								500L,
								"VND",
								false))));

		Integer noProfileSlots = jdbcTemplate.queryForObject(
				"SELECT COUNT(*) FROM ledger_account_balance_slots WHERE ledger_account_id = ?",
				Integer.class,
				noProfileLedgerAccountId);
		assertEquals(0, noProfileSlots);

		Map<String, Object> inactiveAggregate = jdbcTemplate.queryForMap(
				"""
				SELECT COALESCE(SUM(posted_balance_minor), 0) AS posted_sum,
				       COALESCE(SUM(available_balance_minor), 0) AS available_sum
				FROM ledger_account_balance_slots
				WHERE ledger_account_id = ?
				""",
				inactiveLedgerAccountId);
		assertEquals(0L, ((Number) inactiveAggregate.get("posted_sum")).longValue());
		assertEquals(0L, ((Number) inactiveAggregate.get("available_sum")).longValue());

		Integer slottingAuditCount = jdbcTemplate.queryForObject(
				"""
				SELECT COUNT(*)
				FROM audit_events
				WHERE action = 'HOT_ACCOUNT_SLOTTING_APPLIED'
				  AND resource_id IN (?, ?)
				""",
				Integer.class,
				journalNoProfile.toString(),
				journalInactive.toString());
		assertEquals(0, slottingAuditCount);
	}

	@Test
	void postJournal_roundRobinAndRandomFallbackToHash() {
		UUID roundRobinLedgerAccountId = createLedgerAccount("LEDGER-HOT-RR");
		UUID randomLedgerAccountId = createLedgerAccount("LEDGER-HOT-RAND");
		UUID offsetLedgerAccountId = createLedgerAccount("LEDGER-HOT-FALLBACK-OFFSET");
		insertHotProfile(roundRobinLedgerAccountId, 3, "ROUND_ROBIN", true);
		insertSlots(roundRobinLedgerAccountId, 3);
		insertHotProfile(randomLedgerAccountId, 3, "RANDOM", true);
		insertSlots(randomLedgerAccountId, 3);

		UUID journalRoundRobin = ledgerCommandService.postJournal(new LedgerCommandService.PostJournalCommand(
				"HOT_RUNTIME_TEST",
				"HOT_RUNTIME_TEST_REF",
				UUID.randomUUID(),
				"VND",
				null,
				"test-suite",
				UUID.randomUUID(),
				List.of(
						new LedgerCommandService.PostingInstruction(
								roundRobinLedgerAccountId,
								null,
								"C",
								250L,
								"VND",
								false),
						new LedgerCommandService.PostingInstruction(
								offsetLedgerAccountId,
								null,
								"D",
								250L,
								"VND",
								false))));

		UUID journalRandom = ledgerCommandService.postJournal(new LedgerCommandService.PostJournalCommand(
				"HOT_RUNTIME_TEST",
				"HOT_RUNTIME_TEST_REF",
				UUID.randomUUID(),
				"VND",
				null,
				"test-suite",
				UUID.randomUUID(),
				List.of(
						new LedgerCommandService.PostingInstruction(
								randomLedgerAccountId,
								null,
								"C",
								350L,
								"VND",
								false),
						new LedgerCommandService.PostingInstruction(
								offsetLedgerAccountId,
								null,
								"D",
								350L,
								"VND",
								false))));

		assertEquals(250L, sumSlotPosted(roundRobinLedgerAccountId));
		assertEquals(350L, sumSlotPosted(randomLedgerAccountId));

		List<String> appliedStrategies = jdbcTemplate.queryForList(
				"""
				SELECT after_state_json::text
				FROM audit_events
				WHERE action = 'HOT_ACCOUNT_SLOTTING_APPLIED'
				  AND resource_id IN (?, ?)
				ORDER BY created_at ASC
				""",
				String.class,
				journalRoundRobin.toString(),
				journalRandom.toString());
		assertEquals(2, appliedStrategies.size());
		assertTrue(appliedStrategies.stream().allMatch(text -> text.contains("HASH_FALLBACK")));
	}

	private UUID createLedgerAccount(String prefix) {
		UUID ledgerAccountId = UUID.randomUUID();
		String accountCode = prefix + "-" + ledgerAccountId.toString().substring(0, 8);
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
				"Runtime " + accountCode);
		return ledgerAccountId;
	}

	private void insertHotProfile(UUID ledgerAccountId, int slotCount, String strategy, boolean active) {
		jdbcTemplate.update(
				"""
				INSERT INTO hot_account_profiles (
				    ledger_account_id,
				    slot_count,
				    selection_strategy,
				    is_active
				) VALUES (?, ?, ?, ?)
				""",
				ledgerAccountId,
				slotCount,
				strategy,
				active);
	}

	private void insertSlots(UUID ledgerAccountId, int slotCount) {
		for (int slotNo = 0; slotNo < slotCount; slotNo++) {
			jdbcTemplate.update(
					"""
					INSERT INTO ledger_account_balance_slots (
					    ledger_account_id,
					    slot_no,
					    posted_balance_minor,
					    available_balance_minor,
					    updated_at
					) VALUES (?, ?, 0, 0, now())
					""",
					ledgerAccountId,
					slotNo);
		}
	}

	private long sumSlotPosted(UUID ledgerAccountId) {
		Long value = jdbcTemplate.queryForObject(
				"""
				SELECT COALESCE(SUM(posted_balance_minor), 0)
				FROM ledger_account_balance_slots
				WHERE ledger_account_id = ?
				""",
				Long.class,
				ledgerAccountId);
		return value == null ? 0L : value;
	}
}
