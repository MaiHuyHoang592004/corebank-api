package com.corebank.corebank_api.ops.system;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.corebank.corebank_api.TestcontainersConfiguration;
import com.corebank.corebank_api.common.CoreBankException;
import com.corebank.corebank_api.ledger.LedgerCommandService;
import com.corebank.corebank_api.payment.PaymentApplicationService;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
class SystemModeServiceTest {

	@Autowired
	private SystemModeService systemModeService;

	@Autowired
	private PaymentApplicationService paymentApplicationService;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@BeforeEach
	void setUp() {
		// Reset system mode to RUNNING before each test
		systemModeService.setMode(SystemModeService.SystemMode.RUNNING, "test");
		systemModeService.setEodOpen(true, "test");
	}

	@Test
	void getCurrentModeReturnsRunningByDefault() {
		SystemModeService.SystemMode mode = systemModeService.getCurrentMode();
		assertEquals(SystemModeService.SystemMode.RUNNING, mode);
	}

	@Test
	void isRunningReturnsTrueWhenRunning() {
		assertTrue(systemModeService.isRunning());
	}

	@Test
	void isWriteAllowedReturnsTrueWhenRunning() {
		assertTrue(systemModeService.isWriteAllowed());
	}

	@Test
	void isReadAllowedReturnsTrueWhenRunning() {
		assertTrue(systemModeService.isReadAllowed());
	}

	@Test
	void setModeChangesModeToEodLock() {
		systemModeService.setMode(SystemModeService.SystemMode.EOD_LOCK, "operator1");

		assertEquals(SystemModeService.SystemMode.EOD_LOCK, systemModeService.getCurrentMode());
		assertFalse(systemModeService.isRunning());
		assertFalse(systemModeService.isWriteAllowed());
		assertFalse(systemModeService.isReadAllowed());
	}

	@Test
	void setModeChangesModeToMaintenance() {
		systemModeService.setMode(SystemModeService.SystemMode.MAINTENANCE, "operator1");

		assertEquals(SystemModeService.SystemMode.MAINTENANCE, systemModeService.getCurrentMode());
		assertFalse(systemModeService.isRunning());
		assertFalse(systemModeService.isWriteAllowed());
		assertFalse(systemModeService.isReadAllowed());
	}

	@Test
	void setModeChangesModeToReadOnly() {
		systemModeService.setMode(SystemModeService.SystemMode.READ_ONLY, "operator1");

		assertEquals(SystemModeService.SystemMode.READ_ONLY, systemModeService.getCurrentMode());
		assertFalse(systemModeService.isRunning());
		assertFalse(systemModeService.isWriteAllowed());
		assertTrue(systemModeService.isReadAllowed());
	}

	@Test
	void enforceWriteAllowedThrowsWhenEodLock() {
		systemModeService.setMode(SystemModeService.SystemMode.EOD_LOCK, "operator1");

		assertThrows(
				CoreBankException.class,
				() -> systemModeService.enforceWriteAllowed());
	}

	@Test
	void enforceWriteAllowedThrowsWhenMaintenance() {
		systemModeService.setMode(SystemModeService.SystemMode.MAINTENANCE, "operator1");

		assertThrows(
				CoreBankException.class,
				() -> systemModeService.enforceWriteAllowed());
	}

	@Test
	void enforceReadAllowedThrowsWhenEodLock() {
		systemModeService.setMode(SystemModeService.SystemMode.EOD_LOCK, "operator1");

		assertThrows(
				CoreBankException.class,
				() -> systemModeService.enforceReadAllowed());
	}

	@Test
	void enforceReadAllowedThrowsWhenMaintenance() {
		systemModeService.setMode(SystemModeService.SystemMode.MAINTENANCE, "operator1");

		assertThrows(
				CoreBankException.class,
				() -> systemModeService.enforceReadAllowed());
	}

	@Test
	void enforceReadAllowedDoesNotThrowWhenReadOnly() {
		systemModeService.setMode(SystemModeService.SystemMode.READ_ONLY, "operator1");

		// Should not throw
		systemModeService.enforceReadAllowed();
	}

	@Test
	void setAndGetBusinessDate() {
		String businessDate = "2026-03-23";
		systemModeService.setBusinessDate(businessDate, "operator1");

		assertEquals(businessDate, systemModeService.getBusinessDate());
	}

	@Test
	void setEodOpenChangesStatus() {
		systemModeService.setEodOpen(false, "operator1");

		assertFalse(systemModeService.isEodOpen());

		systemModeService.setEodOpen(true, "operator1");

		assertTrue(systemModeService.isEodOpen());
	}

	@Test
	void eodLockBlocksPaymentHold() {
		// Seed test data
		UUID customerAccountId = seedAccount(10_000L, 10_000L, "VND");

		// Set system to EOD_LOCK
		systemModeService.setMode(SystemModeService.SystemMode.EOD_LOCK, "operator1");

		// Try to authorize hold - should fail
		assertThrows(
				CoreBankException.class,
				() -> paymentApplicationService.authorizeHold(
						new PaymentApplicationService.AuthorizeHoldRequest(
								"idem-eod-test-1",
								customerAccountId,
								null,
								1_000L,
								"VND",
								"MERCHANT_PAYMENT",
								"test hold in EOD",
								"tester",
								UUID.randomUUID(),
								UUID.randomUUID(),
								UUID.randomUUID(),
								"trace-eod-test-1")));
	}

	@Test
	void maintenanceBlocksPaymentHold() {
		// Seed test data
		UUID customerAccountId = seedAccount(10_000L, 10_000L, "VND");

		// Set system to MAINTENANCE
		systemModeService.setMode(SystemModeService.SystemMode.MAINTENANCE, "operator1");

		// Try to authorize hold - should fail
		assertThrows(
				CoreBankException.class,
				() -> paymentApplicationService.authorizeHold(
						new PaymentApplicationService.AuthorizeHoldRequest(
								"idem-maint-test-1",
								customerAccountId,
								null,
								1_000L,
								"VND",
								"MERCHANT_PAYMENT",
								"test hold in MAINTENANCE",
								"tester",
								UUID.randomUUID(),
								UUID.randomUUID(),
								UUID.randomUUID(),
								"trace-maint-test-1")));
	}

	private UUID seedAccount(long postedBalanceMinor, long availableBalanceMinor, String currency) {
		UUID customerId = UUID.randomUUID();
		UUID productId = UUID.randomUUID();
		UUID customerAccountId = UUID.randomUUID();

		jdbcTemplate.update(
				"""
				INSERT INTO customers (
				    customer_id,
				    customer_type,
				    full_name,
				    email,
				    phone,
				    status,
				    risk_band
				) VALUES (?, 'INDIVIDUAL', ?, ?, ?, 'ACTIVE', ?)
				""",
				customerId,
				"Test Customer",
				"customer@example.com",
				"0123456789",
				"LOW");

		jdbcTemplate.update(
				"""
				INSERT INTO bank_products (
				    product_id,
				    product_code,
				    product_name,
				    product_type,
				    currency,
				    status
				) VALUES (?, ?, ?, ?, ?, 'ACTIVE')
				""",
				productId,
				"CHK-" + productId.toString().substring(0, 8),
				"Checking",
				"CHECKING",
				currency);

		jdbcTemplate.update(
				"""
				INSERT INTO customer_accounts (
				    customer_account_id,
				    customer_id,
				    product_id,
				    account_number,
				    currency,
				    status,
				    posted_balance_minor,
				    available_balance_minor,
				    version
				) VALUES (?, ?, ?, ?, ?, 'ACTIVE', ?, ?, 0)
				""",
				customerAccountId,
				customerId,
				productId,
				"ACCT-" + customerAccountId.toString().substring(0, 8),
				currency,
				postedBalanceMinor,
				availableBalanceMinor);

		return customerAccountId;
	}
}