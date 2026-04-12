package com.corebank.corebank_api.limits;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.corebank.corebank_api.TestcontainersConfiguration;
import com.corebank.corebank_api.common.CoreBankException;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
class LimitCheckServiceTest {

	@Autowired
	private LimitCheckService limitCheckService;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	private UUID testAccountId;
	private UUID standardProfileId;

	@BeforeEach
	void setUp() {
		// Create test account
		testAccountId = UUID.randomUUID();
		UUID customerId = UUID.randomUUID();
		// Use existing product from V4__seed_data.sql
		UUID productId = UUID.fromString("a1b2c3d4-e5f6-7890-abcd-ef1234567801");

		jdbcTemplate.update(
				"""
				INSERT INTO customers (customer_id, customer_type, full_name, email, phone, status, risk_band)
				VALUES (?, 'INDIVIDUAL', 'Test Customer', 'test@example.com', '1234567890', 'ACTIVE', 'LOW')
				""",
				customerId);

		jdbcTemplate.update(
				"""
				INSERT INTO customer_accounts (customer_account_id, customer_id, product_id, account_number, currency, status, posted_balance_minor, available_balance_minor, version)
				VALUES (?, ?, ?, ?, 'VND', 'ACTIVE', 10000000000, 10000000000, 0)
				""",
				testAccountId, customerId, productId, "TEST-" + testAccountId.toString().substring(0, 8));

		// Get standard profile ID
		standardProfileId = jdbcTemplate.queryForObject(
				"SELECT profile_id FROM limit_profiles WHERE profile_code = 'STANDARD'",
				UUID.class);

		// Assign STANDARD profile to test account
		jdbcTemplate.update(
				"""
				INSERT INTO limit_assignments (customer_account_id, profile_id, currency)
				VALUES (?, ?, 'VND')
				""",
				testAccountId, standardProfileId);
	}

	@Test
	void enforceLimitsAllowsTransactionWithinLimits() {
		// Transaction amount within limits (1 billion VND < 1 billion VND limit)
		assertDoesNotThrow(() ->
				limitCheckService.enforceLimits(testAccountId, 500_000_000L, "VND"));
	}

	@Test
	void enforceLimitsThrowsWhenTransactionalLimitExceeded() {
		// Transaction amount exceeds limit (1.5 billion VND > 1 billion VND limit)
		assertThrows(
				CoreBankException.class,
				() -> limitCheckService.enforceLimits(testAccountId, 1_500_000_000L, "VND"));
	}

	@Test
	void enforceLimitsAllowsTransactionWhenNoProfileAssigned() {
		UUID accountWithoutLimits = UUID.randomUUID();
		UUID customerId = UUID.randomUUID();
		// Use existing product from V4__seed_data.sql
		UUID productId = UUID.fromString("a1b2c3d4-e5f6-7890-abcd-ef1234567801");

		jdbcTemplate.update(
				"""
				INSERT INTO customers (customer_id, customer_type, full_name, email, phone, status, risk_band)
				VALUES (?, 'INDIVIDUAL', 'No Limit Customer', 'nolimit@example.com', '1234567890', 'ACTIVE', 'LOW')
				""",
				customerId);

		jdbcTemplate.update(
				"""
				INSERT INTO customer_accounts (customer_account_id, customer_id, product_id, account_number, currency, status, posted_balance_minor, available_balance_minor, version)
				VALUES (?, ?, ?, ?, 'VND', 'ACTIVE', 10000000000, 10000000000, 0)
				""",
				accountWithoutLimits, customerId, productId, "TEST-" + accountWithoutLimits.toString().substring(0, 8));

		// No limit profile assigned - should allow any amount
		assertDoesNotThrow(() ->
				limitCheckService.enforceLimits(accountWithoutLimits, 5_000_000_000L, "VND"));
	}

	@Test
	void incrementUsageIncrementsAmountCounter() {
		// Get DAILY_AMOUNT_LIMIT rule ID
		UUID dailyAmountRuleId = jdbcTemplate.queryForObject(
				"SELECT rule_id FROM limit_rules WHERE rule_name = 'DAILY_AMOUNT_LIMIT'",
				UUID.class);

		// First increment
		assertDoesNotThrow(() ->
				limitCheckService.incrementUsage(testAccountId, 1_000_000L, "VND"));

		// Verify counter was created and incremented
		Long usedValue = jdbcTemplate.queryForObject(
				"""
				SELECT used_value FROM limit_usage_counters
				WHERE customer_account_id = ?
				  AND rule_id = ?
				""",
				Long.class,
				testAccountId,
				dailyAmountRuleId);

		assert usedValue != null && usedValue == 1_000_000L;
	}

	@Test
	void incrementUsageIncrementsCountCounter() {
		// Get DAILY_COUNT_LIMIT rule ID
		UUID dailyCountRuleId = jdbcTemplate.queryForObject(
				"SELECT rule_id FROM limit_rules WHERE rule_name = 'DAILY_COUNT_LIMIT'",
				UUID.class);

		// Increment count
		assertDoesNotThrow(() ->
				limitCheckService.incrementUsage(testAccountId, 0L, "VND"));

		// Verify counter was created and incremented
		Integer usageCount = jdbcTemplate.queryForObject(
				"""
				SELECT usage_count FROM limit_usage_counters
				WHERE customer_account_id = ?
				  AND rule_id = ?
				""",
				Integer.class,
				testAccountId,
				dailyCountRuleId);

		assert usageCount != null && usageCount == 1;
	}

	@Test
	void enforceLimitsThrowsWhenDailyAmountLimitExceeded() {
		// Get DAILY_AMOUNT_LIMIT rule ID
		UUID dailyAmountRuleId = jdbcTemplate.queryForObject(
				"SELECT rule_id FROM limit_rules WHERE rule_name = 'DAILY_AMOUNT_LIMIT'",
				UUID.class);

		// Use up most of the daily limit
		limitCheckService.incrementUsage(testAccountId, 4_500_000_000L, "VND");

		// Verify counter was created
		Long usedValue = jdbcTemplate.queryForObject(
				"""
				SELECT used_value FROM limit_usage_counters
				WHERE customer_account_id = ?
				  AND rule_id = ?
				""",
				Long.class,
				testAccountId,
				dailyAmountRuleId);

		assert usedValue != null && usedValue == 4_500_000_000L;

		// Try to transfer more than remaining daily limit
		assertThrows(
				CoreBankException.class,
				() -> limitCheckService.enforceLimits(testAccountId, 600_000_000L, "VND"));
	}

	@Test
	void enforceLimitsThrowsWhenDailyCountLimitExceeded() {
		// Get DAILY_COUNT_LIMIT rule ID
		UUID dailyCountRuleId = jdbcTemplate.queryForObject(
				"SELECT rule_id FROM limit_rules WHERE rule_name = 'DAILY_COUNT_LIMIT'",
				UUID.class);

		// Use up all count limit (100 transactions)
		for (int i = 0; i < 100; i++) {
			limitCheckService.incrementUsage(testAccountId, 1_000_000L, "VND");
		}

		// Verify counter was created
		Integer usageCount = jdbcTemplate.queryForObject(
				"""
				SELECT usage_count FROM limit_usage_counters
				WHERE customer_account_id = ?
				  AND rule_id = ?
				""",
				Integer.class,
				testAccountId,
				dailyCountRuleId);

		assert usageCount != null && usageCount == 100;

		// Try one more transaction
		assertThrows(
				CoreBankException.class,
				() -> limitCheckService.enforceLimits(testAccountId, 1_000_000L, "VND"));
	}

	@Test
	void enforceLimitsDoesNotCheckInactiveRules() {
		// Deactivate transactional limit rule
		jdbcTemplate.update(
				"""
				UPDATE limit_rules
				SET status = 'INACTIVE'
				WHERE profile_id = ? AND rule_name = 'TRANSACTION_AMOUNT_LIMIT'
				""",
				standardProfileId);

		// Should allow transaction that would have exceeded the now-inactive rule
		assertDoesNotThrow(() ->
				limitCheckService.enforceLimits(testAccountId, 1_500_000_000L, "VND"));
	}
}