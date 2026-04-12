package com.corebank.corebank_api.account;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Write-side repository for explicit locking and balance mutation on
 * the authoritative customer_accounts table.
 *
 * <p>No business rules belong here. Locking and mutation are explicit.</p>
 */
@Repository
public class AccountBalanceRepository {

	private static final RowMapper<CustomerAccount> CUSTOMER_ACCOUNT_ROW_MAPPER =
			new RowMapper<>() {
				@Override
				public CustomerAccount mapRow(ResultSet rs, int rowNum) throws SQLException {
					CustomerAccount account = new CustomerAccount();
					account.setCustomerAccountId(UUID.fromString(rs.getString("customer_account_id")));
					account.setCustomerId(UUID.fromString(rs.getString("customer_id")));
					account.setProductId(UUID.fromString(rs.getString("product_id")));
					account.setAccountNumber(rs.getString("account_number"));
					account.setCurrency(rs.getString("currency"));
					account.setStatus(rs.getString("status"));
					account.setPostedBalanceMinor(rs.getLong("posted_balance_minor"));
					account.setAvailableBalanceMinor(rs.getLong("available_balance_minor"));
					account.setVersion(rs.getLong("version"));
					account.setCreatedAt(rs.getTimestamp("created_at").toInstant());
					account.setUpdatedAt(rs.getTimestamp("updated_at").toInstant());
					return account;
				}
			};

	private final JdbcTemplate jdbcTemplate;
	private final NamedParameterJdbcTemplate namedParameterJdbcTemplate;

	public AccountBalanceRepository(
			JdbcTemplate jdbcTemplate,
			NamedParameterJdbcTemplate namedParameterJdbcTemplate) {
		this.jdbcTemplate = jdbcTemplate;
		this.namedParameterJdbcTemplate = namedParameterJdbcTemplate;
	}

	public Optional<CustomerAccount> lockById(UUID customerAccountId) {
		return jdbcTemplate.query(
				"""
				SELECT customer_account_id,
				       customer_id,
				       product_id,
				       account_number,
				       currency,
				       status,
				       posted_balance_minor,
				       available_balance_minor,
				       version,
				       created_at,
				       updated_at
				FROM customer_accounts
				WHERE customer_account_id = ?
				FOR UPDATE
				""",
				CUSTOMER_ACCOUNT_ROW_MAPPER,
				customerAccountId)
			.stream()
			.findFirst();
	}

	public List<CustomerAccount> lockByIdsInDeterministicOrder(Collection<UUID> customerAccountIds) {
		if (customerAccountIds == null || customerAccountIds.isEmpty()) {
			return Collections.emptyList();
		}

		List<UUID> sortedIds = new ArrayList<>(customerAccountIds);
		sortedIds.sort(UUID::compareTo);

		return namedParameterJdbcTemplate.query(
				"""
				SELECT customer_account_id,
				       customer_id,
				       product_id,
				       account_number,
				       currency,
				       status,
				       posted_balance_minor,
				       available_balance_minor,
				       version,
				       created_at,
				       updated_at
				FROM customer_accounts
				WHERE customer_account_id IN (:ids)
				ORDER BY customer_account_id ASC
				FOR UPDATE
				""",
				new MapSqlParameterSource("ids", sortedIds),
				CUSTOMER_ACCOUNT_ROW_MAPPER);
	}

	public boolean updateAvailableBalance(UUID customerAccountId, long newAvailableBalanceMinor, long expectedVersion) {
		int updatedRows = jdbcTemplate.update(
				"""
				UPDATE customer_accounts
				SET available_balance_minor = ?,
				    version = version + 1,
				    updated_at = now()
				WHERE customer_account_id = ?
				  AND version = ?
				""",
				newAvailableBalanceMinor,
				customerAccountId,
				expectedVersion);

		return updatedRows == 1;
	}
}