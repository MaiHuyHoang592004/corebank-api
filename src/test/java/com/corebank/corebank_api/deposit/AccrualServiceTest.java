package com.corebank.corebank_api.deposit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.corebank.corebank_api.TestcontainersConfiguration;
import com.corebank.corebank_api.ops.system.SystemModeService;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Regression coverage for the AccrualService transaction-isolation bug: a single
 * outer @Transactional around the whole batch loop meant one contract's
 * CoreBankException marked the WHOLE ambient transaction rollback-only, so
 * UnexpectedRollbackException on return silently discarded every accrual
 * processed that day — not just the failing contract's — while the per-item
 * catch block gave the false impression that failures were isolated.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
class AccrualServiceTest {

	@Autowired
	private AccrualService accrualService;

	@Autowired
	private DepositApplicationService depositApplicationService;

	@Autowired
	private DepositAccrualRepository depositAccrualRepository;

	@Autowired
	private SystemModeService systemModeService;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	private UUID customerAccountId;
	private UUID productId;
	private UUID productVersionId;
	private UUID debitLedgerAccountId;
	private UUID creditLedgerAccountId;

	@BeforeEach
	void setUp() {
		UUID customerId = UUID.randomUUID();
		customerAccountId = UUID.randomUUID();
		productId = UUID.randomUUID();
		productVersionId = UUID.randomUUID();
		debitLedgerAccountId = UUID.randomUUID();
		creditLedgerAccountId = UUID.randomUUID();
		String accountNumber = "ACR-" + customerAccountId.toString().substring(0, 8);
		systemModeService.setMode(SystemModeService.SystemMode.RUNNING, "test");

		jdbcTemplate.update(
				"""
				INSERT INTO customers (customer_id, customer_type, full_name, email, phone, status, risk_band)
				VALUES (?, 'INDIVIDUAL', ?, ?, ?, 'ACTIVE', ?)
				""",
				customerId, "Accrual Test Customer", "accrual@example.com", "0900000001", "LOW");

		jdbcTemplate.update(
				"""
				INSERT INTO bank_products (product_id, product_code, product_name, product_type, currency, status)
				VALUES (?, ?, 'Term Deposit', 'TERM_DEPOSIT', ?, 'ACTIVE')
				""",
				productId, "TD-" + productId.toString().substring(0, 8), "VND");

		jdbcTemplate.update(
				"""
				INSERT INTO bank_product_versions (
				    product_version_id, product_id, version_no, effective_from, effective_to, status, configuration_json, created_at
				) VALUES (?, ?, 1, now() - interval '1 day', NULL, 'ACTIVE', '{}'::jsonb, now())
				""",
				productVersionId, productId);

		jdbcTemplate.update(
				"""
				INSERT INTO ledger_accounts (ledger_account_id, account_code, account_name, account_type, currency, is_active)
				VALUES (?, ?, 'Deposit Funding Liability', 'LIABILITY', ?, true)
				""",
				debitLedgerAccountId, "ACR-DEBIT-" + debitLedgerAccountId.toString().substring(0, 8), "VND");

		jdbcTemplate.update(
				"""
				INSERT INTO ledger_accounts (ledger_account_id, account_code, account_name, account_type, currency, is_active)
				VALUES (?, ?, 'Deposit Funding Asset', 'ASSET', ?, true)
				""",
				creditLedgerAccountId, "ACR-CREDIT-" + creditLedgerAccountId.toString().substring(0, 8), "VND");

		jdbcTemplate.update(
				"""
				INSERT INTO customer_accounts (
				    customer_account_id, customer_id, product_id, account_number, currency, status,
				    posted_balance_minor, available_balance_minor, version, created_at, updated_at
				) VALUES (?, ?, ?, ?, 'VND', 'ACTIVE', ?, ?, 0, NOW(), NOW())
				""",
				customerAccountId, customerId, productId, accountNumber, 20_000_000L, 20_000_000L);
	}

	private UUID openContract(String idempotencyKey, long principalMinor, double interestRate) {
		DepositApplicationService.OpenDepositRequest request = new DepositApplicationService.OpenDepositRequest(
				idempotencyKey,
				customerAccountId,
				productId,
				productVersionId,
				principalMinor,
				"VND",
				interestRate,
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
		return depositApplicationService.openDeposit(request).contractId();
	}

	@Test
	void oneContractFailingToAccrueDoesNotRollBackAnotherContractsAlreadyCommittedAccrual() {
		// A healthy contract that will accrue a positive amount.
		UUID healthyContractId = openContract("acr-open-healthy", 5_000_000L, 6.5);
		// A 0%-rate contract: daily interest rounds to exactly zero minor units, which
		// LedgerCommandService.validateBalancedJournal rejects ("amount must be
		// positive") — a realistic way to force exactly this one contract to fail
		// inside processDailyAccruals without touching the other contract's request.
		UUID zeroRateContractId = openContract("acr-open-zero-rate", 3_000_000L, 0.0);

		AccrualService.AccrualBatchRequest request = new AccrualService.AccrualBatchRequest(
				debitLedgerAccountId, creditLedgerAccountId, "test-actor", UUID.randomUUID());

		AccrualService.AccrualBatchResult result = accrualService.processDailyAccruals(request);

		assertEquals(1, result.processed());
		assertEquals(1, result.failed());
		assertEquals(0, result.skipped());

		// The bug: this would have been empty too, because the zero-rate contract's
		// failure marked the single shared outer transaction rollback-only and the
		// whole batch — healthy contract included — was discarded on return.
		assertTrue(
				depositAccrualRepository.findByContractIdAndAccrualDate(healthyContractId, LocalDate.now()).isPresent(),
				"the healthy contract's accrual must survive a sibling contract's failure in the same batch");

		assertTrue(
				depositAccrualRepository.findByContractIdAndAccrualDate(zeroRateContractId, LocalDate.now()).isEmpty(),
				"the failing contract must not have a partial accrual row");

		assertEquals(
				1,
				count(
						"SELECT COUNT(*) FROM deposit_events WHERE contract_id = ? AND event_type = 'ACCURED'",
						healthyContractId),
				"the healthy contract's accrual event must be committed");
	}

	private int count(String sql, Object... args) {
		Integer value = jdbcTemplate.queryForObject(sql, Integer.class, args);
		return value == null ? 0 : value;
	}
}
