package com.corebank.corebank_api.ledger;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Reads a posted journal back together with its debit and credit lines.
 *
 * <p>Every money command in this system ends in a balanced journal, but until now nothing could
 * read one back: the write responses carry a {@code journalId} and the read models carry events,
 * so the postings themselves — the double entry that the whole design rests on — were reachable
 * only from SQL. This is what a reviewer asks to see first, and what the demo needs in order to
 * show the invariant rather than assert it.
 *
 * <p>The totals and the balanced flag are derived here rather than trusted from the caller, so a
 * journal that somehow posted unbalanced would show up as unbalanced instead of being rendered as
 * if it were fine.
 */
@Service
public class LedgerJournalQueryService {

	private static final String HEADER_SQL = """
			SELECT journal_id,
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
			FROM ledger_journals
			WHERE journal_id = ?
			""";

	/**
	 * Debits before credits, which is how a journal is read on paper; {@code posting_id} keeps the
	 * order inside each side stable across calls.
	 */
	private static final String POSTINGS_SQL = """
			SELECT p.posting_id,
			       p.entry_side,
			       p.ledger_account_id,
			       la.account_code,
			       la.account_name,
			       la.account_type,
			       p.customer_account_id,
			       ca.account_number,
			       p.amount_minor,
			       p.currency
			FROM ledger_postings p
			JOIN ledger_accounts la ON la.ledger_account_id = p.ledger_account_id
			LEFT JOIN customer_accounts ca ON ca.customer_account_id = p.customer_account_id
			WHERE p.journal_id = ?
			ORDER BY CASE p.entry_side WHEN 'D' THEN 0 ELSE 1 END, p.posting_id
			""";

	private final JdbcTemplate jdbcTemplate;

	public LedgerJournalQueryService(JdbcTemplate jdbcTemplate) {
		this.jdbcTemplate = jdbcTemplate;
	}

	public Optional<JournalView> findJournal(UUID journalId) {
		if (journalId == null) {
			return Optional.empty();
		}

		List<JournalHeader> headers = jdbcTemplate.query(HEADER_SQL, this::mapHeader, journalId);
		if (headers.isEmpty()) {
			return Optional.empty();
		}

		JournalHeader header = headers.get(0);
		List<PostingView> postings = jdbcTemplate.query(POSTINGS_SQL, this::mapPosting, journalId);

		long totalDebitMinor = totalFor(postings, "D");
		long totalCreditMinor = totalFor(postings, "C");
		long differenceMinor = totalDebitMinor - totalCreditMinor;

		return Optional.of(new JournalView(
				header.journalId(),
				header.journalType(),
				header.referenceType(),
				header.referenceId(),
				header.currency(),
				header.reversalOfJournalId(),
				header.createdByActor(),
				header.correlationId(),
				header.createdAt(),
				postings,
				totalDebitMinor,
				totalCreditMinor,
				differenceMinor,
				differenceMinor == 0L,
				header.rowHash(),
				header.prevRowHash()));
	}

	private long totalFor(List<PostingView> postings, String entrySide) {
		return postings.stream()
				.filter(posting -> entrySide.equals(posting.entrySide()))
				.mapToLong(PostingView::amountMinor)
				.sum();
	}

	private JournalHeader mapHeader(ResultSet rs, int rowNum) throws SQLException {
		return new JournalHeader(
				rs.getObject("journal_id", UUID.class),
				rs.getString("journal_type"),
				rs.getString("reference_type"),
				rs.getObject("reference_id", UUID.class),
				rs.getString("currency"),
				rs.getObject("reversal_of_journal_id", UUID.class),
				rs.getString("created_by_actor"),
				rs.getObject("correlation_id", UUID.class),
				readInstant(rs, "created_at"),
				toHex(rs.getBytes("prev_row_hash")),
				toHex(rs.getBytes("row_hash")));
	}

	private PostingView mapPosting(ResultSet rs, int rowNum) throws SQLException {
		return new PostingView(
				rs.getLong("posting_id"),
				rs.getString("entry_side"),
				rs.getObject("ledger_account_id", UUID.class),
				rs.getString("account_code"),
				rs.getString("account_name"),
				rs.getString("account_type"),
				rs.getObject("customer_account_id", UUID.class),
				rs.getString("account_number"),
				rs.getLong("amount_minor"),
				rs.getString("currency"));
	}

	private Instant readInstant(ResultSet rs, String column) throws SQLException {
		java.sql.Timestamp timestamp = rs.getTimestamp(column);
		return timestamp == null ? null : timestamp.toInstant();
	}

	/**
	 * The chain hashes are returned in full rather than truncated, so that two journals read from
	 * this endpoint can actually be checked against each other: the later journal's
	 * {@code prevRowHashHex} equals the earlier one's {@code rowHashHex}. Shortening them for
	 * display is the caller's decision.
	 */
	private String toHex(byte[] hash) {
		return hash == null ? null : HexFormat.of().formatHex(hash);
	}

	/** Package-private so the query's assembly step can be exercised without a database. */
	record JournalHeader(
			UUID journalId,
			String journalType,
			String referenceType,
			UUID referenceId,
			String currency,
			UUID reversalOfJournalId,
			String createdByActor,
			UUID correlationId,
			Instant createdAt,
			String prevRowHash,
			String rowHash) {
	}

	public record JournalView(
			UUID journalId,
			String journalType,
			String referenceType,
			UUID referenceId,
			String currency,
			UUID reversalOfJournalId,
			String createdByActor,
			UUID correlationId,
			Instant createdAt,
			List<PostingView> postings,
			long totalDebitMinor,
			long totalCreditMinor,
			long differenceMinor,
			boolean balanced,
			String rowHashHex,
			String prevRowHashHex) {
	}

	public record PostingView(
			long postingId,
			String entrySide,
			UUID ledgerAccountId,
			String ledgerAccountCode,
			String ledgerAccountName,
			String ledgerAccountType,
			UUID customerAccountId,
			String customerAccountNumber,
			long amountMinor,
			String currency) {
	}
}
