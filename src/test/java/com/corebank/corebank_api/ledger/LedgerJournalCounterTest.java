package com.corebank.corebank_api.ledger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.corebank.corebank_api.account.AccountBalanceRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Guards the one property that makes {@code corebank.ledger.journals.posted} usable as a money
 * metric: it counts journals that <em>committed</em>, not journals that were inserted.
 *
 * <p>This was a real design defect, caught in review before it shipped. The obvious implementation
 * — increment the counter right after {@code INSERT INTO ledger_journals} — is wrong, because
 * {@code postJournal} is {@code @Transactional} and everything after that insert (the postings
 * loop, the posted-balance updates, the slotting audit) can still fail and roll the journal back.
 * The counter would have kept an increment for money that does not exist.
 *
 * <p>These tests use the real {@link TransactionSynchronizationManager} rather than a database, so
 * they stay container-free while still exercising the exact mechanism the fix relies on: the
 * increment is deferred to {@code afterCommit}, and it attaches to whichever transaction is
 * current — which, for money commands arriving through {@code IdempotentMoneyCommandTemplate}, is
 * an outer transaction that commits long after {@code postJournal} returns.
 */
@Tag("fast")
class LedgerJournalCounterTest {

	private static final String COUNTER = "corebank.ledger.journals.posted";

	private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
	private final JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
	private final AccountBalanceRepository accountBalanceRepository = mock(AccountBalanceRepository.class);
	private final HotAccountSlotRuntimeService hotAccountSlotRuntimeService =
			mock(HotAccountSlotRuntimeService.class);

	private final LedgerCommandService service = new LedgerCommandService(
			jdbcTemplate, accountBalanceRepository, hotAccountSlotRuntimeService, registry);

	@AfterEach
	void clearSynchronizations() {
		if (TransactionSynchronizationManager.isSynchronizationActive()) {
			TransactionSynchronizationManager.clearSynchronization();
		}
	}

	@Test
	@DisplayName("a journal is not counted until the surrounding transaction commits")
	void notCountedBeforeCommit() {
		stubChainLookup();
		TransactionSynchronizationManager.initSynchronization();

		service.postJournal(balancedJournal());

		// postJournal has returned and the INSERT has been issued, but nothing has committed yet.
		// This is precisely the window in which an inline increment would already have fired.
		assertEquals(0.0, count(), "journal counted before commit");

		fireAfterCommit();
		assertEquals(1.0, count(), "journal not counted after commit");
	}

	@Test
	@DisplayName("a journal rolled back after its INSERT is never counted")
	void notCountedWhenRolledBack() {
		stubChainLookup();
		// Fail after the journal row has already been written. appendSlottingAudit is the last
		// thing postJournal does, so this reproduces the general shape of the defect: the journal
		// INSERT succeeded, something later threw, and the whole transaction rolls back.
		doThrow(new IllegalStateException("slotting audit failed"))
				.when(hotAccountSlotRuntimeService)
				.appendSlottingAudit(any(), any(), any());
		TransactionSynchronizationManager.initSynchronization();

		assertThrows(IllegalStateException.class, () -> service.postJournal(balancedJournal()));

		// No synchronization should have been registered at all, so even a commit that somehow
		// followed could not resurrect the increment.
		assertEquals(
				0,
				TransactionSynchronizationManager.getSynchronizations().size(),
				"a rolled-back journal registered a commit callback");
		fireAfterCommit();
		assertEquals(0.0, count(), "rolled-back journal was counted");
	}

	@Test
	@DisplayName("outside a transaction the insert is already durable, so it counts immediately")
	void countedImmediatelyWithoutTransaction() {
		stubChainLookup();

		service.postJournal(balancedJournal());

		assertEquals(1.0, count());
	}

	/**
	 * The hash-chain lookup is the only collaborator call whose return value postJournal
	 * dereferences; an unstubbed mock would hand back null and fail for the wrong reason.
	 */
	private void stubChainLookup() {
		when(jdbcTemplate.query(anyString(), any(RowMapper.class))).thenReturn(List.of());
		when(hotAccountSlotRuntimeService.lockActiveContextsInDeterministicOrder(any()))
				.thenReturn(Map.of());
	}

	private void fireAfterCommit() {
		for (TransactionSynchronization synchronization :
				List.copyOf(TransactionSynchronizationManager.getSynchronizations())) {
			synchronization.afterCommit();
		}
	}

	private double count() {
		Counter counter = registry.find(COUNTER).counter();
		return counter == null ? 0.0 : counter.count();
	}

	/** Two equal-and-opposite postings with no customer account, so no balance update is attempted. */
	private LedgerCommandService.PostJournalCommand balancedJournal() {
		return new LedgerCommandService.PostJournalCommand(
				"TRANSFER",
				"TEST",
				UUID.randomUUID(),
				"VND",
				null,
				"test-actor",
				UUID.randomUUID(),
				List.of(
						new LedgerCommandService.PostingInstruction(
								UUID.randomUUID(), null, "D", 1_000L, "VND", false),
						new LedgerCommandService.PostingInstruction(
								UUID.randomUUID(), null, "C", 1_000L, "VND", false)));
	}
}
