package com.corebank.corebank_api.account;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.corebank.corebank_api.TestcontainersConfiguration;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

/** Was previously untested: a row-mapping regression here (e.g. a renamed
 * column) would only have surfaced indirectly, through unrelated tests that
 * happen to touch a read path. */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
class BalanceQueryServiceTest {

	@Autowired
	private BalanceQueryService balanceQueryService;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@Test
	void findById_mapsEveryColumn() {
		UUID customerId = UUID.randomUUID();
		UUID productId = UUID.randomUUID();
		UUID customerAccountId = UUID.randomUUID();

		jdbcTemplate.update(
				"""
				INSERT INTO customers (customer_id, customer_type, full_name, email, phone, status, risk_band)
				VALUES (?, 'INDIVIDUAL', 'Balance Query Test', 'bq-test@example.com', '0900000002', 'ACTIVE', 'LOW')
				""",
				customerId);
		jdbcTemplate.update(
				"""
				INSERT INTO bank_products (product_id, product_code, product_name, product_type, currency, status)
				VALUES (?, ?, 'Balance Query Product', 'CHECKING', 'VND', 'ACTIVE')
				""",
				productId, "BQ-" + productId.toString().substring(0, 8));
		jdbcTemplate.update(
				"""
				INSERT INTO customer_accounts (
				    customer_account_id, customer_id, product_id, account_number, currency, status,
				    posted_balance_minor, available_balance_minor, version, created_at, updated_at
				) VALUES (?, ?, ?, ?, 'VND', 'ACTIVE', 1_500_000, 1_200_000, 3, NOW(), NOW())
				""",
				customerAccountId, customerId, productId, "BQ-ACC-" + customerAccountId.toString().substring(0, 8));

		CustomerAccount account = balanceQueryService.findById(customerAccountId).orElseThrow();

		assertEquals(customerAccountId, account.getCustomerAccountId());
		assertEquals(customerId, account.getCustomerId());
		assertEquals(productId, account.getProductId());
		assertEquals("VND", account.getCurrency());
		assertEquals("ACTIVE", account.getStatus());
		assertEquals(1_500_000L, account.getPostedBalanceMinor());
		assertEquals(1_200_000L, account.getAvailableBalanceMinor());
		assertEquals(3L, account.getVersion());
	}

	@Test
	void findById_returnsEmpty_whenAccountDoesNotExist() {
		assertTrue(balanceQueryService.findById(UUID.randomUUID()).isEmpty());
		assertFalse(balanceQueryService.findById(UUID.randomUUID()).isPresent());
	}
}
