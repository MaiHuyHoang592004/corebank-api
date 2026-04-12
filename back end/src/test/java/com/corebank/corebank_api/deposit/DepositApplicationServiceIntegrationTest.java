package com.corebank.corebank_api.deposit;

import com.corebank.corebank_api.TestcontainersConfiguration;
import com.corebank.corebank_api.account.AccountBalanceRepository;
import com.corebank.corebank_api.account.CustomerAccount;
import com.corebank.corebank_api.common.CoreBankException;
import com.corebank.corebank_api.integration.IdempotencyService;
import com.corebank.corebank_api.integration.OutboxService;
import com.corebank.corebank_api.ledger.LedgerCommandService;
import com.corebank.corebank_api.ops.audit.AuditService;
import com.corebank.corebank_api.ops.system.SystemModeService;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.junit.jupiter.api.Assertions.*;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
public class DepositApplicationServiceIntegrationTest {

	@Autowired
	private DepositApplicationService depositApplicationService;

	@Autowired
	private AccountBalanceRepository accountBalanceRepository;

	@Autowired
	private DepositContractRepository depositContractRepository;

	@Autowired
	private DepositAccrualRepository depositAccrualRepository;

	@Autowired
	private DepositEventRepository depositEventRepository;

	@Autowired
	private IdempotencyService idempotencyService;

	@Autowired
	private OutboxService outboxService;

	@Autowired
	private AuditService auditService;

	@Autowired
	private SystemModeService systemModeService;

	@Autowired
	private LedgerCommandService ledgerCommandService;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	private UUID customerAccountId;
	private UUID productId;
	private UUID productVersionId;
	private UUID debitLedgerAccountId;
	private UUID creditLedgerAccountId;
	private String accountNumber;

	@BeforeEach
	void setUp() {
		// Setup test data
		UUID customerId = UUID.randomUUID();
		customerAccountId = UUID.randomUUID();
		productId = UUID.randomUUID();
		productVersionId = UUID.randomUUID();
		debitLedgerAccountId = UUID.randomUUID();
		creditLedgerAccountId = UUID.randomUUID();
		accountNumber = "TEST-" + customerAccountId.toString().substring(0, 8);
		systemModeService.setMode(SystemModeService.SystemMode.RUNNING, "test");

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
				"Deposit Test Customer",
				"deposit@example.com",
				"0900000000",
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
				) VALUES (?, ?, ?, 'TERM_DEPOSIT', ?, 'ACTIVE')
				""",
				productId,
				"TD-" + productId.toString().substring(0, 8),
				"Term Deposit",
				"VND");

		jdbcTemplate.update(
				"""
				INSERT INTO bank_product_versions (
				    product_version_id,
				    product_id,
				    version_no,
				    effective_from,
				    effective_to,
				    status,
				    configuration_json,
				    created_at
				) VALUES (?, ?, 1, now() - interval '1 day', NULL, 'ACTIVE', '{}'::jsonb, now())
				""",
				productVersionId,
				productId);

		jdbcTemplate.update(
				"""
				INSERT INTO ledger_accounts (
				    ledger_account_id,
				    account_code,
				    account_name,
				    account_type,
				    currency,
				    is_active
				) VALUES (?, ?, ?, 'LIABILITY', ?, true)
				""",
				debitLedgerAccountId,
				"DEP-DEBIT-" + debitLedgerAccountId.toString().substring(0, 8),
				"Deposit Funding Liability",
				"VND");

		jdbcTemplate.update(
				"""
				INSERT INTO ledger_accounts (
				    ledger_account_id,
				    account_code,
				    account_name,
				    account_type,
				    currency,
				    is_active
				) VALUES (?, ?, ?, 'ASSET', ?, true)
				""",
				creditLedgerAccountId,
				"DEP-CREDIT-" + creditLedgerAccountId.toString().substring(0, 8),
				"Deposit Funding Asset",
				"VND");

		// Create test customer account with balance
		jdbcTemplate.update(
				"INSERT INTO customer_accounts (customer_account_id, customer_id, product_id, account_number, currency, status, posted_balance_minor, available_balance_minor, version, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, NOW(), NOW())",
				customerAccountId,
				customerId,
				productId,
				accountNumber,
				"VND",
				"ACTIVE",
				10000000L, // 10 million VND
				10000000L,
				0L);
	}

	@Test
	void testOpenDeposit_Success() {
		// Arrange
		UUID correlationId = UUID.randomUUID();
		UUID requestId = UUID.randomUUID();
		DepositApplicationService.OpenDepositRequest request = new DepositApplicationService.OpenDepositRequest(
				"test-idempotency-key",
				customerAccountId,
				productId,
				productVersionId,
				5000000L, // 5 million VND
				"VND",
				6.5,
				12,
				1.0,
				false,
				debitLedgerAccountId,
				creditLedgerAccountId,
				"test-actor",
				correlationId,
				requestId,
				UUID.randomUUID(),
				"test-trace"
		);

		// Act
		DepositApplicationService.OpenDepositResponse response = depositApplicationService.openDeposit(request);

		// Assert
		assertNotNull(response);
		assertNotNull(response.contractId());
		assertEquals(customerAccountId, response.customerAccountId());
		assertEquals(5000000L, response.principalAmountMinor());
		assertEquals("VND", response.currency());
		assertEquals(6.5, response.interestRate());
		assertEquals("ACTIVE", response.status());

		// Verify account balance was updated
		CustomerAccount updatedAccount = accountBalanceRepository.lockById(customerAccountId).orElseThrow();
		assertEquals(5000000L, updatedAccount.getAvailableBalanceMinor()); // 10M - 5M = 5M

		// Verify deposit contract was created
		assertTrue(depositContractRepository.findById(response.contractId()).isPresent());

		// Verify deposit event was created
		assertTrue(count(
				"SELECT COUNT(*) FROM deposit_events WHERE contract_id = ?",
				response.contractId()) > 0);

		// Verify outbox envelope metadata
		assertEquals(1, count(
				"SELECT COUNT(*) FROM outbox_events WHERE event_type = 'DEPOSIT_OPENED' AND aggregate_id = ?",
				response.contractId().toString()));

		var outboxEnvelope = jdbcTemplate.queryForMap(
				"""
				SELECT event_data->>'schemaVersion' AS schema_version,
				       event_data->>'correlationId' AS correlation_id,
				       event_data->>'requestId' AS request_id,
				       event_data->>'actor' AS actor
				FROM outbox_events
				WHERE event_type = 'DEPOSIT_OPENED' AND aggregate_id = ?
				ORDER BY id DESC
				LIMIT 1
				""",
				response.contractId().toString());

		assertEquals("v1", outboxEnvelope.get("schema_version"));
		assertEquals(correlationId.toString(), outboxEnvelope.get("correlation_id"));
		assertEquals(requestId.toString(), outboxEnvelope.get("request_id"));
		assertEquals("test-actor", outboxEnvelope.get("actor"));

		// Verify idempotency
		DepositApplicationService.OpenDepositResponse response2 = depositApplicationService.openDeposit(request);
		assertEquals(response.contractId(), response2.contractId());
	}

	@Test
	void testOpenDeposit_InsufficientFunds() {
		// Arrange
		DepositApplicationService.OpenDepositRequest request = new DepositApplicationService.OpenDepositRequest(
				"test-idempotency-key-2",
				customerAccountId,
				productId,
				productVersionId,
				15000000L, // More than available balance
				"VND",
				6.5,
				12,
				1.0,
				false,
				debitLedgerAccountId,
				creditLedgerAccountId,
				"test-actor",
				UUID.randomUUID(),
				UUID.randomUUID(),
				UUID.randomUUID(),
				"test-trace"
		);

		// Act & Assert
		assertThrows(CoreBankException.class, () -> {
			depositApplicationService.openDeposit(request);
		});

		// Verify account balance was not updated
		CustomerAccount updatedAccount = accountBalanceRepository.lockById(customerAccountId).orElseThrow();
		assertEquals(10000000L, updatedAccount.getAvailableBalanceMinor());
	}

	@Test
	void testOpenDeposit_InvalidInterestRate() {
		// Arrange
		DepositApplicationService.OpenDepositRequest request = new DepositApplicationService.OpenDepositRequest(
				"test-idempotency-key-3",
				customerAccountId,
				productId,
				productVersionId,
				1000000L,
				"VND",
				150.0, // Invalid interest rate > 100%
				12,
				1.0,
				false,
				debitLedgerAccountId,
				creditLedgerAccountId,
				"test-actor",
				UUID.randomUUID(),
				UUID.randomUUID(),
				UUID.randomUUID(),
				"test-trace"
		);

		// Act & Assert
		assertThrows(CoreBankException.class, () -> {
			depositApplicationService.openDeposit(request);
		});
	}

	@Test
	void testOpenDeposit_RejectsMismatchedProductVersionWhenGovernedVersionExists() {
		DepositApplicationService.OpenDepositRequest request = new DepositApplicationService.OpenDepositRequest(
				"test-idempotency-key-version-mismatch",
				customerAccountId,
				productId,
				UUID.randomUUID(),
				1_000_000L,
				"VND",
				6.5,
				12,
				1.0,
				false,
				debitLedgerAccountId,
				creditLedgerAccountId,
				"test-actor",
				UUID.randomUUID(),
				UUID.randomUUID(),
				UUID.randomUUID(),
				"test-trace");

		assertThrows(CoreBankException.class, () -> depositApplicationService.openDeposit(request));
		assertEquals(0, count(
				"SELECT COUNT(*) FROM deposit_contracts WHERE customer_account_id = ?",
				customerAccountId));
	}

	@Test
	void testAccrueInterest_Success() {
		// Arrange - First create a deposit contract
		DepositApplicationService.OpenDepositRequest openRequest = new DepositApplicationService.OpenDepositRequest(
				"test-idempotency-key-4",
				customerAccountId,
				productId,
				productVersionId,
				5000000L,
				"VND",
				6.5,
				12,
				1.0,
				false,
				debitLedgerAccountId,
				creditLedgerAccountId,
				"test-actor",
				UUID.randomUUID(),
				UUID.randomUUID(),
				UUID.randomUUID(),
				"test-trace"
		);

		DepositApplicationService.OpenDepositResponse openResponse = depositApplicationService.openDeposit(openRequest);
		UUID contractId = openResponse.contractId();

		// Now accrue interest
		DepositApplicationService.AccrueInterestRequest request = new DepositApplicationService.AccrueInterestRequest(
				"test-idempotency-key-5",
				contractId,
				debitLedgerAccountId,
				creditLedgerAccountId,
				"test-actor",
				UUID.randomUUID(),
				UUID.randomUUID(),
				UUID.randomUUID(),
				"test-trace"
		);

		// Act
		DepositApplicationService.AccrueInterestResponse response = depositApplicationService.accrueInterest(request);

		// Assert
		assertNotNull(response);
		assertEquals(contractId, response.contractId());
		assertNotNull(response.accrualId());
		assertEquals(LocalDate.now(), response.accrualDate());
		assertTrue(response.accruedInterest() > 0);
		assertTrue(response.runningBalance() > 0);

		// Verify accrual was created
		assertTrue(depositAccrualRepository.findByContractIdAndAccrualDate(contractId, LocalDate.now()).isPresent());

		// Verify accrual event was created
		assertTrue(count(
				"SELECT COUNT(*) FROM deposit_events WHERE contract_id = ? AND event_type = 'ACCURED'",
				contractId) > 0);
	}

	@Test
	void testAccrueInterest_AlreadyAccruedToday() {
		// Arrange - First create a deposit contract and accrue interest
		DepositApplicationService.OpenDepositRequest openRequest = new DepositApplicationService.OpenDepositRequest(
				"test-idempotency-key-6",
				customerAccountId,
				productId,
				productVersionId,
				5000000L,
				"VND",
				6.5,
				12,
				1.0,
				false,
				debitLedgerAccountId,
				creditLedgerAccountId,
				"test-actor",
				UUID.randomUUID(),
				UUID.randomUUID(),
				UUID.randomUUID(),
				"test-trace"
		);

		DepositApplicationService.OpenDepositResponse openResponse = depositApplicationService.openDeposit(openRequest);
		UUID contractId = openResponse.contractId();

		// First accrual
		DepositApplicationService.AccrueInterestRequest request1 = new DepositApplicationService.AccrueInterestRequest(
				"test-idempotency-key-7",
				contractId,
				debitLedgerAccountId,
				creditLedgerAccountId,
				"test-actor",
				UUID.randomUUID(),
				UUID.randomUUID(),
				UUID.randomUUID(),
				"test-trace"
		);

		depositApplicationService.accrueInterest(request1);

		// Second accrual on same day should fail
		DepositApplicationService.AccrueInterestRequest request2 = new DepositApplicationService.AccrueInterestRequest(
				"test-idempotency-key-8",
				contractId,
				debitLedgerAccountId,
				creditLedgerAccountId,
				"test-actor",
				UUID.randomUUID(),
				UUID.randomUUID(),
				UUID.randomUUID(),
				"test-trace"
		);

		// Act & Assert
		assertThrows(CoreBankException.class, () -> {
			depositApplicationService.accrueInterest(request2);
		});
	}

	@Test
	void testProcessMaturity_Success() {
		// Arrange - First create a deposit contract
		DepositApplicationService.OpenDepositRequest openRequest = new DepositApplicationService.OpenDepositRequest(
				"test-idempotency-key-9",
				customerAccountId,
				productId,
				productVersionId,
				5000000L,
				"VND",
				6.5,
				12,
				1.0,
				false,
				debitLedgerAccountId,
				creditLedgerAccountId,
				"test-actor",
				UUID.randomUUID(),
				UUID.randomUUID(),
				UUID.randomUUID(),
				"test-trace"
		);

		DepositApplicationService.OpenDepositResponse openResponse = depositApplicationService.openDeposit(openRequest);
		UUID contractId = openResponse.contractId();

		// Accrue some interest
		DepositApplicationService.AccrueInterestRequest accrueRequest = new DepositApplicationService.AccrueInterestRequest(
				"test-idempotency-key-10",
				contractId,
				debitLedgerAccountId,
				creditLedgerAccountId,
				"test-actor",
				UUID.randomUUID(),
				UUID.randomUUID(),
				UUID.randomUUID(),
				"test-trace"
		);

		depositApplicationService.accrueInterest(accrueRequest);

		jdbcTemplate.update(
				"UPDATE deposit_contracts SET start_date = CURRENT_DATE - INTERVAL '1 day', maturity_date = CURRENT_DATE WHERE contract_id = ?",
				contractId);

		// Now process maturity
		DepositApplicationService.MaturityRequest request = new DepositApplicationService.MaturityRequest(
				"test-idempotency-key-11",
				contractId,
				debitLedgerAccountId,
				creditLedgerAccountId,
				"test-actor",
				UUID.randomUUID(),
				UUID.randomUUID(),
				UUID.randomUUID(),
				"test-trace"
		);

		// Act
		DepositApplicationService.MaturityResponse response = depositApplicationService.processMaturity(request);

		// Assert
		assertNotNull(response);
		assertEquals(contractId, response.contractId());
		assertEquals(customerAccountId, response.customerAccountId());
		assertEquals(5000000L, response.principalAmountMinor());
		assertTrue(response.totalAccruedInterest() >= 0);
		assertEquals("VND", response.currency());
		assertEquals("MATURED", response.status());

		// Verify contract status was updated
		assertTrue(depositContractRepository.findById(contractId).isPresent());
		DepositContract contract = depositContractRepository.findById(contractId).orElseThrow();
		assertEquals("MATURED", contract.getStatus());

		// Verify account balance was updated (principal + interest returned)
		CustomerAccount updatedAccount = accountBalanceRepository.lockById(customerAccountId).orElseThrow();
		assertTrue(updatedAccount.getAvailableBalanceMinor() > 5000000L); // Should have principal + interest returned

		// Verify maturity event was created
		assertTrue(count(
				"SELECT COUNT(*) FROM deposit_events WHERE contract_id = ? AND event_type = 'MATURED'",
				contractId) > 0);
	}

	@Test
	void testProcessMaturity_AutoRenewContractRejectedWithoutSideEffects() {
		DepositApplicationService.OpenDepositResponse openResponse = depositApplicationService.openDeposit(
				new DepositApplicationService.OpenDepositRequest(
						"test-idempotency-key-12",
						customerAccountId,
						productId,
						productVersionId,
						5000000L,
						"VND",
						6.5,
						12,
						1.0,
						true,
						debitLedgerAccountId,
						creditLedgerAccountId,
						"test-actor",
						UUID.randomUUID(),
						UUID.randomUUID(),
						UUID.randomUUID(),
						"test-trace"));

		UUID contractId = openResponse.contractId();
		depositApplicationService.accrueInterest(new DepositApplicationService.AccrueInterestRequest(
				"test-idempotency-key-13",
				contractId,
				debitLedgerAccountId,
				creditLedgerAccountId,
				"test-actor",
				UUID.randomUUID(),
				UUID.randomUUID(),
				UUID.randomUUID(),
				"test-trace"));

		jdbcTemplate.update(
				"UPDATE deposit_contracts SET start_date = CURRENT_DATE - INTERVAL '1 day', maturity_date = CURRENT_DATE WHERE contract_id = ?",
				contractId);

		assertThrows(
				CoreBankException.class,
				() -> depositApplicationService.processMaturity(new DepositApplicationService.MaturityRequest(
						"test-idempotency-key-14",
						contractId,
						debitLedgerAccountId,
						creditLedgerAccountId,
						"test-actor",
						UUID.randomUUID(),
						UUID.randomUUID(),
						UUID.randomUUID(),
						"test-trace")));

		DepositContract contract = depositContractRepository.findById(contractId).orElseThrow();
		assertEquals("ACTIVE", contract.getStatus());
		assertEquals(0, count(
				"SELECT COUNT(*) FROM deposit_events WHERE contract_id = ? AND event_type = 'MATURED'",
				contractId));
		assertEquals(0, count(
				"SELECT COUNT(*) FROM audit_events WHERE action = 'DEPOSIT_MATURED' AND resource_id = ?",
				contractId.toString()));
		assertEquals(0, count(
				"SELECT COUNT(*) FROM outbox_events WHERE event_type = 'DEPOSIT_MATURED' AND aggregate_id = ?",
				contractId.toString()));
		assertEquals(1, count(
				"SELECT COUNT(*) FROM idempotency_keys WHERE idempotency_key = 'test-idempotency-key-14' AND status = 'FAILED'"));
	}

	@Test
	void testProcessMaturity_ReplayReturnsSameResultWithoutDoubleWrites() {
		DepositApplicationService.OpenDepositResponse openResponse = depositApplicationService.openDeposit(
				new DepositApplicationService.OpenDepositRequest(
						"test-idempotency-key-15",
						customerAccountId,
						productId,
						productVersionId,
						5000000L,
						"VND",
						6.5,
						12,
						1.0,
						false,
						debitLedgerAccountId,
						creditLedgerAccountId,
						"test-actor",
						UUID.randomUUID(),
						UUID.randomUUID(),
						UUID.randomUUID(),
						"test-trace"));

		UUID contractId = openResponse.contractId();
		depositApplicationService.accrueInterest(new DepositApplicationService.AccrueInterestRequest(
				"test-idempotency-key-16",
				contractId,
				debitLedgerAccountId,
				creditLedgerAccountId,
				"test-actor",
				UUID.randomUUID(),
				UUID.randomUUID(),
				UUID.randomUUID(),
				"test-trace"));

		jdbcTemplate.update(
				"UPDATE deposit_contracts SET start_date = CURRENT_DATE - INTERVAL '1 day', maturity_date = CURRENT_DATE WHERE contract_id = ?",
				contractId);

		DepositApplicationService.MaturityRequest request = new DepositApplicationService.MaturityRequest(
				"test-idempotency-key-17",
				contractId,
				debitLedgerAccountId,
				creditLedgerAccountId,
				"test-actor",
				UUID.randomUUID(),
				UUID.randomUUID(),
				UUID.randomUUID(),
				"test-trace");

		DepositApplicationService.MaturityResponse first = depositApplicationService.processMaturity(request);
		DepositApplicationService.MaturityResponse second = depositApplicationService.processMaturity(request);

		assertEquals(first.contractId(), second.contractId());
		assertEquals(first.totalAccruedInterest(), second.totalAccruedInterest());
		assertEquals(1, count(
				"SELECT COUNT(*) FROM deposit_events WHERE contract_id = ? AND event_type = 'MATURED'",
				contractId));
		assertEquals(1, count(
				"SELECT COUNT(*) FROM audit_events WHERE action = 'DEPOSIT_MATURED' AND resource_id = ?",
				contractId.toString()));
		assertEquals(1, count(
				"SELECT COUNT(*) FROM outbox_events WHERE event_type = 'DEPOSIT_MATURED' AND aggregate_id = ?",
				contractId.toString()));
	}

	private int count(String sql, Object... args) {
		Integer value = jdbcTemplate.queryForObject(sql, Integer.class, args);
		return value == null ? 0 : value;
	}
}
