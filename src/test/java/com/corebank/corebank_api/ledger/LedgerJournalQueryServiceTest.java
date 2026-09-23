package com.corebank.corebank_api.ledger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

/**
 * Covers the part of {@link LedgerJournalQueryService} that is logic rather than SQL: the totals
 * and the balanced flag are derived from the postings that come back, so a journal cannot be
 * reported as balanced on the strength of anything except its own lines.
 *
 * <p>Container-free on purpose — the SQL itself is exercised by the integration suite, while this
 * guards the arithmetic that the dashboard renders as "difference: 0".
 */
@Tag("fast")
class LedgerJournalQueryServiceTest {

	private static final UUID JOURNAL_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");

	private final JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
	private final LedgerJournalQueryService service = new LedgerJournalQueryService(jdbcTemplate);

	@Test
	@DisplayName("an unknown journal is absent rather than an empty journal")
	void unknownJournalIsEmpty() {
		when(jdbcTemplate.query(any(String.class), any(RowMapper.class), eq(JOURNAL_ID)))
				.thenReturn(List.of());

		assertTrue(service.findJournal(JOURNAL_ID).isEmpty());
	}

	@Test
	@DisplayName("a null journal id is rejected without reaching the database")
	void nullJournalIdIsEmpty() {
		assertTrue(service.findJournal(null).isEmpty());
	}

	@Test
	@DisplayName("debits and credits are totalled per side and a matched journal is balanced")
	void balancedJournalReportsZeroDifference() {
		stub(List.of(
				posting(1L, "D", 700_000L),
				posting(2L, "C", 700_000L)));

		LedgerJournalQueryService.JournalView view = service.findJournal(JOURNAL_ID).orElseThrow();

		assertEquals(700_000L, view.totalDebitMinor());
		assertEquals(700_000L, view.totalCreditMinor());
		assertEquals(0L, view.differenceMinor());
		assertTrue(view.balanced());
	}

	@Test
	@DisplayName("several lines on one side are summed before the sides are compared")
	void multiLineJournalIsBalancedAcrossSides() {
		stub(List.of(
				posting(1L, "D", 400_000L),
				posting(2L, "D", 300_000L),
				posting(3L, "C", 700_000L)));

		LedgerJournalQueryService.JournalView view = service.findJournal(JOURNAL_ID).orElseThrow();

		assertEquals(700_000L, view.totalDebitMinor());
		assertEquals(700_000L, view.totalCreditMinor());
		assertTrue(view.balanced());
	}

	@Test
	@DisplayName("an unbalanced journal is reported as unbalanced, not rendered as if it were fine")
	void unbalancedJournalIsFlagged() {
		stub(List.of(
				posting(1L, "D", 700_000L),
				posting(2L, "C", 500_000L)));

		LedgerJournalQueryService.JournalView view = service.findJournal(JOURNAL_ID).orElseThrow();

		assertEquals(200_000L, view.differenceMinor());
		assertFalse(view.balanced());
	}

	@SuppressWarnings("unchecked")
	private void stub(List<LedgerJournalQueryService.PostingView> postings) {
		when(jdbcTemplate.query(any(String.class), any(RowMapper.class), eq(JOURNAL_ID)))
				.thenAnswer(invocation -> {
					String sql = invocation.getArgument(0);
					return sql.contains("FROM ledger_postings") ? postings : List.of(header());
				});
	}

	private LedgerJournalQueryService.JournalHeader header() {
		return new LedgerJournalQueryService.JournalHeader(
				JOURNAL_ID,
				"TRANSFER_INTERNAL",
				"TRANSFER",
				UUID.randomUUID(),
				"VND",
				null,
				"demo_admin",
				UUID.randomUUID(),
				Instant.parse("2026-09-22T14:32:05Z"),
				"c1e9",
				"8f2a");
	}

	private LedgerJournalQueryService.PostingView posting(long id, String side, long amountMinor) {
		return new LedgerJournalQueryService.PostingView(
				id,
				side,
				UUID.randomUUID(),
				"1001",
				"Customer deposits",
				"LIABILITY",
				UUID.randomUUID(),
				"DEMO-SRC-0001",
				amountMinor,
				"VND");
	}
}
