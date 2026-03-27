package com.corebank.corebank_api.demo.application;

import com.corebank.corebank_api.common.CoreBankException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class DemoSetupService {

	private static final UUID CHECKING_PRODUCT_ID = UUID.fromString("a1b2c3d4-e5f6-7890-abcd-ef1234567801");
	private static final UUID SAVINGS_PRODUCT_ID = UUID.fromString("a1b2c3d4-e5f6-7890-abcd-ef1234567802");
	private static final UUID TERM_DEPOSIT_PRODUCT_ID = UUID.fromString("a1b2c3d4-e5f6-7890-abcd-ef1234567803");
	private static final UUID LOAN_PRODUCT_ID = UUID.fromString("a1b2c3d4-e5f6-7890-abcd-ef1234567804");

	private static final UUID DEMO_CUSTOMER_SOURCE_ID = UUID.fromString("10000000-0000-0000-0000-000000000001");
	private static final UUID DEMO_CUSTOMER_DESTINATION_ID = UUID.fromString("10000000-0000-0000-0000-000000000002");
	private static final UUID DEMO_CUSTOMER_DEPOSIT_ID = UUID.fromString("10000000-0000-0000-0000-000000000003");
	private static final UUID DEMO_CUSTOMER_BORROWER_ID = UUID.fromString("10000000-0000-0000-0000-000000000004");

	private static final UUID DEMO_ACCOUNT_SOURCE_ID = UUID.fromString("20000000-0000-0000-0000-000000000001");
	private static final UUID DEMO_ACCOUNT_DESTINATION_ID = UUID.fromString("20000000-0000-0000-0000-000000000002");
	private static final UUID DEMO_ACCOUNT_DEPOSIT_ID = UUID.fromString("20000000-0000-0000-0000-000000000003");
	private static final UUID DEMO_ACCOUNT_BORROWER_ID = UUID.fromString("20000000-0000-0000-0000-000000000004");

	private static final UUID MATURITY_READY_CONTRACT_ID = UUID.fromString("30000000-0000-0000-0000-000000000001");

	private static final UUID LEDGER_SETTLEMENT_ASSET = UUID.fromString("b1c2d3e4-f5a6-7890-bcde-f12345678013");
	private static final UUID LEDGER_CUSTOMER_DEPOSITS = UUID.fromString("b1c2d3e4-f5a6-7890-bcde-f12345678021");
	private static final UUID LEDGER_TERM_DEPOSITS = UUID.fromString("b1c2d3e4-f5a6-7890-bcde-f12345678022");
	private static final UUID LEDGER_CHECKING_DEPOSITS = UUID.fromString("b1c2d3e4-f5a6-7890-bcde-f12345678023");
	private static final UUID LEDGER_INTEREST_EXPENSE = UUID.fromString("b1c2d3e4-f5a6-7890-bcde-f12345678051");

	private final JdbcTemplate jdbcTemplate;

	public DemoSetupService(JdbcTemplate jdbcTemplate) {
		this.jdbcTemplate = jdbcTemplate;
	}

	@Transactional
	public DemoSetupResponse initialize() {
		ensureProductRows();
		ensureLedgerRows();

		UUID checkingVersionId = ensureUsableProductVersion(CHECKING_PRODUCT_ID);
		UUID savingsVersionId = ensureUsableProductVersion(SAVINGS_PRODUCT_ID);
		UUID termDepositVersionId = ensureUsableProductVersion(TERM_DEPOSIT_PRODUCT_ID);
		UUID loanVersionId = ensureUsableProductVersion(LOAN_PRODUCT_ID);

		ensureCustomer(DEMO_CUSTOMER_SOURCE_ID, "Demo Source", "demo.source@corebank.local", "090-000-0001");
		ensureCustomer(DEMO_CUSTOMER_DESTINATION_ID, "Demo Destination", "demo.destination@corebank.local", "090-000-0002");
		ensureCustomer(DEMO_CUSTOMER_DEPOSIT_ID, "Demo Depositor", "demo.depositor@corebank.local", "090-000-0003");
		ensureCustomer(DEMO_CUSTOMER_BORROWER_ID, "Demo Borrower", "demo.borrower@corebank.local", "090-000-0004");

		ensureAccount(DEMO_ACCOUNT_SOURCE_ID, DEMO_CUSTOMER_SOURCE_ID, "DEMO-SRC-0001", 80_000_000L);
		ensureAccount(DEMO_ACCOUNT_DESTINATION_ID, DEMO_CUSTOMER_DESTINATION_ID, "DEMO-DST-0001", 25_000_000L);
		ensureAccount(DEMO_ACCOUNT_DEPOSIT_ID, DEMO_CUSTOMER_DEPOSIT_ID, "DEMO-DEP-0001", 120_000_000L);
		ensureAccount(DEMO_ACCOUNT_BORROWER_ID, DEMO_CUSTOMER_BORROWER_ID, "DEMO-LOAN-0001", 35_000_000L);

		ensureMaturityReadyDepositContract(termDepositVersionId);

		return new DemoSetupResponse(
				Instant.now(),
				Map.of(
						"sourceCustomerId", DEMO_CUSTOMER_SOURCE_ID,
						"destinationCustomerId", DEMO_CUSTOMER_DESTINATION_ID,
						"depositCustomerId", DEMO_CUSTOMER_DEPOSIT_ID,
						"borrowerCustomerId", DEMO_CUSTOMER_BORROWER_ID),
				Map.of(
						"sourceAccountId", DEMO_ACCOUNT_SOURCE_ID,
						"destinationAccountId", DEMO_ACCOUNT_DESTINATION_ID,
						"depositAccountId", DEMO_ACCOUNT_DEPOSIT_ID,
						"borrowerAccountId", DEMO_ACCOUNT_BORROWER_ID),
				Map.of(
						"checkingProductId", CHECKING_PRODUCT_ID,
						"savingsProductId", SAVINGS_PRODUCT_ID,
						"termDepositProductId", TERM_DEPOSIT_PRODUCT_ID,
						"loanProductId", LOAN_PRODUCT_ID),
				Map.of(
						"checkingVersionId", checkingVersionId,
						"savingsVersionId", savingsVersionId,
						"termDepositVersionId", termDepositVersionId,
						"loanVersionId", loanVersionId),
				Map.ofEntries(
						Map.entry("paymentCaptureDebitLedgerAccountId", LEDGER_SETTLEMENT_ASSET),
						Map.entry("paymentCaptureCreditLedgerAccountId", LEDGER_CUSTOMER_DEPOSITS),
						Map.entry("transferDebitLedgerAccountId", LEDGER_CUSTOMER_DEPOSITS),
						Map.entry("transferCreditLedgerAccountId", LEDGER_CUSTOMER_DEPOSITS),
						Map.entry("depositOpenDebitLedgerAccountId", LEDGER_CHECKING_DEPOSITS),
						Map.entry("depositOpenCreditLedgerAccountId", LEDGER_TERM_DEPOSITS),
						Map.entry("depositAccrueDebitLedgerAccountId", LEDGER_INTEREST_EXPENSE),
						Map.entry("depositAccrueCreditLedgerAccountId", LEDGER_TERM_DEPOSITS),
						Map.entry("depositMaturityDebitLedgerAccountId", LEDGER_TERM_DEPOSITS),
						Map.entry("depositMaturityCreditLedgerAccountId", LEDGER_CHECKING_DEPOSITS),
						Map.entry("lendingDisburseDebitLedgerAccountId", LEDGER_SETTLEMENT_ASSET),
						Map.entry("lendingDisburseCreditLedgerAccountId", LEDGER_CHECKING_DEPOSITS),
						Map.entry("lendingRepayDebitLedgerAccountId", LEDGER_CHECKING_DEPOSITS),
						Map.entry("lendingRepayCreditLedgerAccountId", LEDGER_SETTLEMENT_ASSET)),
				Map.of("maturityReadyContractId", MATURITY_READY_CONTRACT_ID),
				Map.of(
						"paymentAmountMinor", 500_000L,
						"transferAmountMinor", 700_000L,
						"depositPrincipalMinor", 2_000_000L,
						"loanDisbursementMinor", 4_000_000L,
						"loanRepaymentMinor", 1_100_000L),
				"Demo setup completed. PostgreSQL is authoritative truth; Redis/Kafka are non-authoritative helpers.");
	}

	private void ensureProductRows() {
		jdbcTemplate.update(
				"""
				INSERT INTO bank_products (product_id, product_code, product_name, product_type, currency, status, created_at, updated_at)
				VALUES (?, 'CHK-VND', 'Checking Account', 'CHECKING', 'VND', 'ACTIVE', now(), now())
				ON CONFLICT (product_id) DO UPDATE
				SET status = 'ACTIVE',
				    updated_at = now()
				""",
				CHECKING_PRODUCT_ID);
		jdbcTemplate.update(
				"""
				INSERT INTO bank_products (product_id, product_code, product_name, product_type, currency, status, created_at, updated_at)
				VALUES (?, 'SAV-VND', 'Savings Account', 'SAVINGS', 'VND', 'ACTIVE', now(), now())
				ON CONFLICT (product_id) DO UPDATE
				SET status = 'ACTIVE',
				    updated_at = now()
				""",
				SAVINGS_PRODUCT_ID);
		jdbcTemplate.update(
				"""
				INSERT INTO bank_products (product_id, product_code, product_name, product_type, currency, status, created_at, updated_at)
				VALUES (?, 'TD-VND', 'Term Deposit', 'TERM_DEPOSIT', 'VND', 'ACTIVE', now(), now())
				ON CONFLICT (product_id) DO UPDATE
				SET status = 'ACTIVE',
				    updated_at = now()
				""",
				TERM_DEPOSIT_PRODUCT_ID);
		jdbcTemplate.update(
				"""
				INSERT INTO bank_products (product_id, product_code, product_name, product_type, currency, status, created_at, updated_at)
				VALUES (?, 'LN-VND', 'Loan Product', 'LOAN', 'VND', 'ACTIVE', now(), now())
				ON CONFLICT (product_id) DO UPDATE
				SET status = 'ACTIVE',
				    updated_at = now()
				""",
				LOAN_PRODUCT_ID);
	}

	private void ensureLedgerRows() {
		assertLedgerExists(LEDGER_SETTLEMENT_ASSET);
		assertLedgerExists(LEDGER_CUSTOMER_DEPOSITS);
		assertLedgerExists(LEDGER_TERM_DEPOSITS);
		assertLedgerExists(LEDGER_CHECKING_DEPOSITS);
		assertLedgerExists(LEDGER_INTEREST_EXPENSE);
	}

	private void assertLedgerExists(UUID ledgerAccountId) {
		Integer count = jdbcTemplate.queryForObject(
				"SELECT COUNT(*) FROM ledger_accounts WHERE ledger_account_id = ?",
				Integer.class,
				ledgerAccountId);
		if (count == null || count == 0) {
			throw new CoreBankException("Demo ledger account is missing: " + ledgerAccountId);
		}
	}

	private UUID ensureUsableProductVersion(UUID productId) {
		Optional<UUID> activeVersion = findActiveVersion(productId);
		if (activeVersion.isPresent()) {
			return activeVersion.get();
		}

		Optional<UUID> latestVersion = findLatestVersion(productId);
		if (latestVersion.isPresent()) {
			jdbcTemplate.update(
					"""
					UPDATE bank_product_versions
					SET status = 'ACTIVE',
					    effective_to = NULL
					WHERE product_id = ?
					  AND product_version_id = ?
					""",
					productId,
					latestVersion.get());
			return latestVersion.get();
		}

		UUID generatedVersionId = UUID.randomUUID();
		Integer maxVersion = jdbcTemplate.queryForObject(
				"SELECT COALESCE(MAX(version_no), 0) FROM bank_product_versions WHERE product_id = ?",
				Integer.class,
				productId);
		int nextVersionNo = (maxVersion == null ? 0 : maxVersion) + 1;

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
				) VALUES (?, ?, ?, ?, NULL, 'ACTIVE', CAST(? AS jsonb), now())
				""",
				generatedVersionId,
				productId,
				nextVersionNo,
				Timestamp.from(Instant.now().minusSeconds(60)),
				"{\"source\":\"demo-setup\"}");

		return generatedVersionId;
	}

	private Optional<UUID> findActiveVersion(UUID productId) {
		List<UUID> versions = jdbcTemplate.query(
				"""
				SELECT product_version_id
				FROM bank_product_versions
				WHERE product_id = ?
				  AND status = 'ACTIVE'
				ORDER BY effective_from DESC, version_no DESC, product_version_id DESC
				LIMIT 1
				""",
				(rs, rowNum) -> rs.getObject("product_version_id", UUID.class),
				productId);
		return versions.stream().findFirst();
	}

	private Optional<UUID> findLatestVersion(UUID productId) {
		List<UUID> versions = jdbcTemplate.query(
				"""
				SELECT product_version_id
				FROM bank_product_versions
				WHERE product_id = ?
				ORDER BY version_no DESC, effective_from DESC, product_version_id DESC
				LIMIT 1
				""",
				(rs, rowNum) -> rs.getObject("product_version_id", UUID.class),
				productId);
		return versions.stream().findFirst();
	}

	private void ensureCustomer(UUID customerId, String fullName, String email, String phone) {
		jdbcTemplate.update(
				"""
				INSERT INTO customers (
				    customer_id,
				    customer_type,
				    full_name,
				    email,
				    phone,
				    status,
				    risk_band,
				    created_at,
				    updated_at
				) VALUES (?, 'INDIVIDUAL', ?, ?, ?, 'ACTIVE', 'LOW', now(), now())
				ON CONFLICT (customer_id) DO UPDATE
				SET full_name = EXCLUDED.full_name,
				    email = EXCLUDED.email,
				    phone = EXCLUDED.phone,
				    status = 'ACTIVE',
				    risk_band = 'LOW',
				    updated_at = now()
				""",
				customerId,
				fullName,
				email,
				phone);
	}

	private void ensureAccount(UUID accountId, UUID customerId, String accountNumber, long targetBalanceMinor) {
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
				    version,
				    created_at,
				    updated_at
				) VALUES (?, ?, ?, ?, 'VND', 'ACTIVE', ?, ?, 0, now(), now())
				ON CONFLICT (customer_account_id) DO UPDATE
				SET customer_id = EXCLUDED.customer_id,
				    product_id = EXCLUDED.product_id,
				    account_number = EXCLUDED.account_number,
				    currency = 'VND',
				    status = 'ACTIVE',
				    posted_balance_minor = EXCLUDED.posted_balance_minor,
				    available_balance_minor = EXCLUDED.available_balance_minor,
				    version = 0,
				    updated_at = now()
				""",
				accountId,
				customerId,
				CHECKING_PRODUCT_ID,
				accountNumber,
				targetBalanceMinor,
				targetBalanceMinor);
	}

	private void ensureMaturityReadyDepositContract(UUID termDepositVersionId) {
		LocalDate now = LocalDate.now();
		LocalDate startDate = now.minusMonths(13);
		LocalDate maturityDate = now.minusDays(1);

		jdbcTemplate.update(
				"""
				INSERT INTO deposit_contracts (
				    contract_id,
				    customer_account_id,
				    product_id,
				    product_version_id,
				    principal_amount,
				    currency,
				    interest_rate,
				    term_months,
				    start_date,
				    maturity_date,
				    status,
				    early_closure_penalty_rate,
				    auto_renew,
				    created_at,
				    updated_at
				) VALUES (?, ?, ?, ?, 2000000, 'VND', 6.5, 12, ?, ?, 'ACTIVE', 1.0, false, now(), now())
				ON CONFLICT (contract_id) DO UPDATE
				SET customer_account_id = EXCLUDED.customer_account_id,
				    product_id = EXCLUDED.product_id,
				    product_version_id = EXCLUDED.product_version_id,
				    principal_amount = EXCLUDED.principal_amount,
				    currency = EXCLUDED.currency,
				    interest_rate = EXCLUDED.interest_rate,
				    term_months = EXCLUDED.term_months,
				    start_date = EXCLUDED.start_date,
				    maturity_date = EXCLUDED.maturity_date,
				    status = 'ACTIVE',
				    early_closure_penalty_rate = EXCLUDED.early_closure_penalty_rate,
				    auto_renew = false,
				    updated_at = now()
				""",
				MATURITY_READY_CONTRACT_ID,
				DEMO_ACCOUNT_DEPOSIT_ID,
				TERM_DEPOSIT_PRODUCT_ID,
				termDepositVersionId,
				startDate,
				maturityDate);
	}

	public record DemoSetupResponse(
			Instant initializedAt,
			Map<String, UUID> customerIds,
			Map<String, UUID> accountIds,
			Map<String, UUID> productIds,
			Map<String, UUID> productVersionIds,
			Map<String, UUID> ledgerAccountIds,
			Map<String, UUID> sampleContractIds,
			Map<String, Long> sampleAmountsMinor,
			String note) {
	}
}
