package com.corebank.corebank_api.reporting;

import com.corebank.corebank_api.common.CoreBankException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Service to manage daily balance snapshots.
 *
 * <p>Creates daily snapshots of account balances for reconciliation and historical views.</p>
 */
@Service
public class SnapshotService {

	private final JdbcTemplate jdbcTemplate;

	public SnapshotService(JdbcTemplate jdbcTemplate) {
		this.jdbcTemplate = jdbcTemplate;
	}

	/**
	 * Create daily snapshot for all accounts.
	 *
	 * @param snapshotDate snapshot date
	 * @return number of snapshots created
	 */
	@Transactional
	public int createDailySnapshot(LocalDate snapshotDate) {
		return jdbcTemplate.update(
				"""
				INSERT INTO account_balance_snapshots (
				    customer_account_id, snapshot_date, 
				    posted_balance, available_balance, currency
				)
				SELECT 
				    customer_account_id,
				    ?::date,
				    posted_balance_minor,
				    available_balance_minor,
				    currency
				FROM customer_accounts
				WHERE status = 'ACTIVE'
				ON CONFLICT (customer_account_id, snapshot_date) DO NOTHING
				""",
				java.sql.Date.valueOf(snapshotDate));
	}

	/**
	 * Get snapshot by account and date.
	 *
	 * @param customerAccountId customer account ID
	 * @param snapshotDate snapshot date
	 * @return snapshot or empty if not found
	 */
	public Optional<BalanceSnapshot> getSnapshot(UUID customerAccountId, LocalDate snapshotDate) {
		List<BalanceSnapshot> snapshots = jdbcTemplate.query(
				"""
				SELECT snapshot_id, customer_account_id, snapshot_date,
				       posted_balance, available_balance, currency,
				       created_at
				FROM account_balance_snapshots
				WHERE customer_account_id = ?
				  AND snapshot_date = ?
				""",
				new SnapshotRowMapper(),
				customerAccountId,
				java.sql.Date.valueOf(snapshotDate));

		return snapshots.isEmpty() ? Optional.empty() : Optional.of(snapshots.get(0));
	}

	/**
	 * Get snapshots by date.
	 *
	 * @param snapshotDate snapshot date
	 * @param limit max number of records
	 * @return list of snapshots
	 */
	public List<BalanceSnapshot> getSnapshotsByDate(LocalDate snapshotDate, int limit) {
		return jdbcTemplate.query(
				"""
				SELECT snapshot_id, customer_account_id, snapshot_date,
				       posted_balance, available_balance, currency,
				       created_at
				FROM account_balance_snapshots
				WHERE snapshot_date = ?
				ORDER BY customer_account_id
				LIMIT ?
				""",
				new SnapshotRowMapper(),
				java.sql.Date.valueOf(snapshotDate),
				limit);
	}

	/**
	 * Get snapshots for account.
	 *
	 * @param customerAccountId customer account ID
	 * @param limit max number of records
	 * @return list of snapshots
	 */
	public List<BalanceSnapshot> getAccountSnapshots(UUID customerAccountId, int limit) {
		return jdbcTemplate.query(
				"""
				SELECT snapshot_id, customer_account_id, snapshot_date,
				       posted_balance, available_balance, currency,
				       created_at
				FROM account_balance_snapshots
				WHERE customer_account_id = ?
				ORDER BY snapshot_date DESC
				LIMIT ?
				""",
				new SnapshotRowMapper(),
				customerAccountId,
				limit);
	}

	/**
	 * Compare two snapshots for reconciliation.
	 *
	 * @param customerAccountId customer account ID
	 * @param date1 first date
	 * @param date2 second date
	 * @return comparison result or empty if either snapshot not found
	 */
	public Optional<SnapshotComparison> compareSnapshots(UUID customerAccountId, LocalDate date1, LocalDate date2) {
		Optional<BalanceSnapshot> snapshot1 = getSnapshot(customerAccountId, date1);
		Optional<BalanceSnapshot> snapshot2 = getSnapshot(customerAccountId, date2);

		if (snapshot1.isEmpty() || snapshot2.isEmpty()) {
			return Optional.empty();
		}

		BalanceSnapshot s1 = snapshot1.get();
		BalanceSnapshot s2 = snapshot2.get();

		return Optional.of(new SnapshotComparison(
				customerAccountId,
				date1,
				date2,
				s1.postedBalance(),
				s2.postedBalance(),
				s2.postedBalance() - s1.postedBalance(),
				s1.availableBalance(),
				s2.availableBalance(),
				s2.availableBalance() - s1.availableBalance(),
				s1.currency()));
	}

	// Row Mapper
	private static class SnapshotRowMapper implements RowMapper<BalanceSnapshot> {
		@Override
		public BalanceSnapshot mapRow(ResultSet rs, int rowNum) throws SQLException {
			return new BalanceSnapshot(
					rs.getLong("snapshot_id"),
					rs.getObject("customer_account_id", UUID.class),
					rs.getDate("snapshot_date").toLocalDate(),
					rs.getLong("posted_balance"),
					rs.getLong("available_balance"),
					rs.getString("currency"),
					rs.getTimestamp("created_at").toInstant());
		}
	}

	// Record definitions
	public record BalanceSnapshot(
			long snapshotId,
			UUID customerAccountId,
			LocalDate snapshotDate,
			long postedBalance,
			long availableBalance,
			String currency,
			Instant createdAt) {
	}

	public record SnapshotComparison(
			UUID customerAccountId,
			LocalDate date1,
			LocalDate date2,
			long postedBalance1,
			long postedBalance2,
			long postedBalanceChange,
			long availableBalance1,
			long availableBalance2,
			long availableBalanceChange,
			String currency) {
	}
}