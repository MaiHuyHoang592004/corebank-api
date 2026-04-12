package com.corebank.corebank_api.account;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;

/**
 * Read-only balance service against the authoritative customer_accounts table.
 *
 * <p>This service must not lock rows or mutate balances.</p>
 */
@Service
public class BalanceQueryService {

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

	public BalanceQueryService(JdbcTemplate jdbcTemplate) {
		this.jdbcTemplate = jdbcTemplate;
	}

	public Optional<CustomerAccount> findById(UUID customerAccountId) {
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
				""",
				CUSTOMER_ACCOUNT_ROW_MAPPER,
				customerAccountId)
			.stream()
			.findFirst();
	}
}