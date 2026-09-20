package com.corebank.corebank_api.payment;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.corebank.corebank_api.TestcontainersConfiguration;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Covers the available-balance side of payment settlement.
 *
 * <p>{@code postJournal} moves {@code posted_balance_minor} only; {@code available_balance_minor} is
 * a separate write that each money path must perform for itself. Capture used to skip it for the
 * beneficiary, so captured funds appeared in the payee's posted balance while remaining unspendable,
 * and refund correspondingly never took them back. Both sides are asserted here because fixing only
 * one of them would let a capture-then-refund cycle leave the beneficiary permanently richer.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
class PaymentSettlementBalanceIntegrationTest {

	@Autowired
	private PaymentApplicationService paymentApplicationService;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@Test
	@DisplayName("capture credits the beneficiary's available balance, not only the posted balance")
	void captureCreditsBeneficiaryAvailableBalance() {
		SeededAccount payer = seedAccount(10_000L, 10_000L, "VND");
		SeededAccount payee = seedAccount(4_000L, 4_000L, "VND");
		SeededLedgerAccounts ledger = seedLedgerAccounts("VND");

		UUID holdId = authorizeHold(payer, payee, 3_000L, "settle-capture");

		// The hold only reserves the payer's funds; nothing has reached the payee yet.
		assertBalances(payee.customerAccountId(), 4_000L, 4_000L);

		captureHold(holdId, payee, ledger, 3_000L, "settle-capture");

		assertBalances(payee.customerAccountId(), 7_000L, 7_000L);
		// The payer's available balance was already reduced at authorize time and must not move again.
		assertBalances(payer.customerAccountId(), 7_000L, 7_000L);
	}

	@Test
	@DisplayName("refund takes the captured amount back out of the beneficiary's available balance")
	void refundReversesBothSidesOfSettlement() {
		SeededAccount payer = seedAccount(10_000L, 10_000L, "VND");
		SeededAccount payee = seedAccount(4_000L, 4_000L, "VND");
		SeededLedgerAccounts ledger = seedLedgerAccounts("VND");

		UUID holdId = authorizeHold(payer, payee, 3_000L, "settle-refund");
		UUID paymentOrderId = paymentOrderIdOf(holdId);
		captureHold(holdId, payee, ledger, 3_000L, "settle-refund");

		paymentApplicationService.refund(new PaymentApplicationService.RefundRequest(
				"idem-settle-refund-refund",
				paymentOrderId,
				3_000L,
				"tester",
				UUID.randomUUID(),
				UUID.randomUUID(),
				UUID.randomUUID(),
				"trace-settle-refund-refund",
				"full refund"));

		// A full authorize -> capture -> refund cycle must return both accounts to where they started.
		assertBalances(payer.customerAccountId(), 10_000L, 10_000L);
		assertBalances(payee.customerAccountId(), 4_000L, 4_000L);
	}

	// ------------------------------------------------------------------ helpers

	private UUID authorizeHold(SeededAccount payer, SeededAccount payee, long amountMinor, String keyPrefix) {
		return paymentApplicationService.authorizeHold(new PaymentApplicationService.AuthorizeHoldRequest(
						"idem-" + keyPrefix + "-authorize",
						payer.customerAccountId(),
						payee.customerAccountId(),
						amountMinor,
						"VND",
						"MERCHANT_PAYMENT",
						"settlement balance coverage",
						null,
						"tester",
						UUID.randomUUID(),
						UUID.randomUUID(),
						UUID.randomUUID(),
						"trace-" + keyPrefix + "-authorize"))
				.holdId();
	}

	private void captureHold(
			UUID holdId, SeededAccount payee, SeededLedgerAccounts ledger, long amountMinor, String keyPrefix) {
		paymentApplicationService.captureHold(new PaymentApplicationService.CaptureHoldRequest(
				"idem-" + keyPrefix + "-capture",
				holdId,
				amountMinor,
				ledger.debitLedgerAccountId(),
				ledger.creditLedgerAccountId(),
				payee.customerAccountId(),
				"tester",
				UUID.randomUUID(),
				UUID.randomUUID(),
				UUID.randomUUID(),
				"trace-" + keyPrefix + "-capture"));
	}

	private UUID paymentOrderIdOf(UUID holdId) {
		return jdbcTemplate.queryForObject(
				"SELECT payment_order_id FROM funds_holds WHERE hold_id = ?", UUID.class, holdId);
	}

	private void assertBalances(UUID customerAccountId, long expectedPostedMinor, long expectedAvailableMinor) {
		Map<String, Object> row = jdbcTemplate.queryForMap(
				"SELECT posted_balance_minor, available_balance_minor FROM customer_accounts WHERE customer_account_id = ?",
				customerAccountId);

		assertEquals(
				expectedPostedMinor,
				((Number) row.get("posted_balance_minor")).longValue(),
				"posted balance on account " + customerAccountId);
		assertEquals(
				expectedAvailableMinor,
				((Number) row.get("available_balance_minor")).longValue(),
				"available balance on account " + customerAccountId);
	}

	private SeededAccount seedAccount(long postedBalanceMinor, long availableBalanceMinor, String currency) {
		UUID customerId = UUID.randomUUID();
		UUID productId = UUID.randomUUID();
		UUID customerAccountId = UUID.randomUUID();

		jdbcTemplate.update(
				"""
				INSERT INTO customers (customer_id, customer_type, full_name, email, phone, status, risk_band)
				VALUES (?, 'INDIVIDUAL', ?, ?, ?, 'ACTIVE', ?)
				""",
				customerId, "Test Customer", "customer@example.com", "0123456789", "LOW");

		jdbcTemplate.update(
				"""
				INSERT INTO bank_products (product_id, product_code, product_name, product_type, currency, status)
				VALUES (?, ?, ?, ?, ?, 'ACTIVE')
				""",
				productId,
				"CHK-" + productId.toString().substring(0, 8),
				"Checking", "CHECKING", currency);

		jdbcTemplate.update(
				"""
				INSERT INTO customer_accounts (
				    customer_account_id, customer_id, product_id, account_number,
				    currency, status, posted_balance_minor, available_balance_minor, version
				) VALUES (?, ?, ?, ?, ?, 'ACTIVE', ?, ?, 0)
				""",
				customerAccountId, customerId, productId,
				"ACCT-" + customerAccountId.toString().substring(0, 8),
				currency, postedBalanceMinor, availableBalanceMinor);

		return new SeededAccount(customerId, productId, customerAccountId);
	}

	private SeededLedgerAccounts seedLedgerAccounts(String currency) {
		UUID debitId = UUID.randomUUID();
		UUID creditId = UUID.randomUUID();

		jdbcTemplate.update(
				"""
				INSERT INTO ledger_accounts (ledger_account_id, account_code, account_name, account_type, currency, is_active)
				VALUES (?, ?, ?, 'LIABILITY', ?, true)
				""",
				debitId, "DEBIT-" + debitId.toString().substring(0, 8), "Customer Deposit Liability", currency);

		jdbcTemplate.update(
				"""
				INSERT INTO ledger_accounts (ledger_account_id, account_code, account_name, account_type, currency, is_active)
				VALUES (?, ?, ?, 'ASSET', ?, true)
				""",
				creditId, "CREDIT-" + creditId.toString().substring(0, 8), "Merchant Settlement Asset", currency);

		return new SeededLedgerAccounts(debitId, creditId);
	}

	private record SeededAccount(UUID customerId, UUID productId, UUID customerAccountId) {}

	private record SeededLedgerAccounts(UUID debitLedgerAccountId, UUID creditLedgerAccountId) {}
}
