package com.corebank.corebank_api.ledger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.corebank.corebank_api.TestcontainersConfiguration;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = "spring.task.scheduling.enabled=false")
class LedgerHotAccountConcurrencyIntegrationTest {

	@Autowired
	private LedgerCommandService ledgerCommandService;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@Test
	void concurrentJournals_onSameHotAccount_keepsAggregateCorrectAndDoesNotDeadlock() throws Exception {
		UUID hotLedgerAccountId = createLedgerAccount("LEDGER-HOT-CONCURRENCY");
		UUID offsetLedgerAccountId = createLedgerAccount("LEDGER-HOT-CONCURRENCY-OFFSET");
		insertHotProfile(hotLedgerAccountId, 8, "HASH", true);
		insertSlots(hotLedgerAccountId, 8);

		int taskCount = 24;
		long amountMinor = 100L;
		ExecutorService executor = Executors.newFixedThreadPool(taskCount);
		CountDownLatch readyLatch = new CountDownLatch(taskCount);
		CountDownLatch startLatch = new CountDownLatch(1);
		AtomicReference<Throwable> firstError = new AtomicReference<>();
		List<Future<?>> futures = new ArrayList<>();

		try {
			for (int i = 0; i < taskCount; i++) {
				final int idx = i;
				futures.add(executor.submit(() -> {
					readyLatch.countDown();
					startLatch.await();
					try {
						ledgerCommandService.postJournal(new LedgerCommandService.PostJournalCommand(
								"HOT_RUNTIME_CONCURRENCY",
								"HOT_RUNTIME_CONCURRENCY_REF",
								UUID.nameUUIDFromBytes(("hot-concurrency-" + idx).getBytes(StandardCharsets.UTF_8)),
								"VND",
								null,
								"test-suite",
								UUID.randomUUID(),
								List.of(
										new LedgerCommandService.PostingInstruction(
												hotLedgerAccountId,
												null,
												"D",
												amountMinor,
												"VND",
												false),
										new LedgerCommandService.PostingInstruction(
												offsetLedgerAccountId,
												null,
												"C",
												amountMinor,
												"VND",
												false))));
					} catch (Throwable ex) {
						firstError.compareAndSet(null, ex);
						throw ex;
					}
					return null;
				}));
			}

			assertTrue(readyLatch.await(30, TimeUnit.SECONDS));
			startLatch.countDown();

			for (Future<?> future : futures) {
				future.get(45, TimeUnit.SECONDS);
			}
		} finally {
			executor.shutdownNow();
			executor.awaitTermination(10, TimeUnit.SECONDS);
		}

		assertFalse(firstError.get() != null, "Unexpected concurrency failure: " + firstError.get());
		long expectedDelta = -taskCount * amountMinor;
		assertEquals(expectedDelta, sumSlotPosted(hotLedgerAccountId));
		assertEquals(expectedDelta, sumSlotAvailable(hotLedgerAccountId));

		Integer journalCount = jdbcTemplate.queryForObject(
				"""
				SELECT COUNT(*)
				FROM ledger_journals
				WHERE journal_type = 'HOT_RUNTIME_CONCURRENCY'
				""",
				Integer.class);
		assertEquals(taskCount, journalCount);

		Integer auditCount = jdbcTemplate.queryForObject(
				"""
				SELECT COUNT(*)
				FROM audit_events
				WHERE action = 'HOT_ACCOUNT_SLOTTING_APPLIED'
				  AND (after_state_json ->> 'referenceType') = 'HOT_RUNTIME_CONCURRENCY_REF'
				""",
				Integer.class);
		assertEquals(taskCount, auditCount);
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

	private long sumSlotAvailable(UUID ledgerAccountId) {
		Long value = jdbcTemplate.queryForObject(
				"""
				SELECT COALESCE(SUM(available_balance_minor), 0)
				FROM ledger_account_balance_slots
				WHERE ledger_account_id = ?
				""",
				Long.class,
				ledgerAccountId);
		return value == null ? 0L : value;
	}
}
