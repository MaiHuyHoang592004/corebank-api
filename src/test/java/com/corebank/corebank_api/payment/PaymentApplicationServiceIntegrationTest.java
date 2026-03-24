package com.corebank.corebank_api.payment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.corebank.corebank_api.TestcontainersConfiguration;
import com.corebank.corebank_api.common.IdempotencyConflictException;
import com.corebank.corebank_api.common.InsufficientFundsException;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
class PaymentApplicationServiceIntegrationTest {

	@Autowired
	private PaymentApplicationService paymentApplicationService;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@Test
	void authorizeHoldReducesAvailableOnlyAndWritesAuditAndOutbox() {
		SeededAccount seededAccount = seedAccount(10_000L, 10_000L, "VND");

		PaymentApplicationService.AuthorizeHoldResponse response = paymentApplicationService.authorizeHold(
				new PaymentApplicationService.AuthorizeHoldRequest(
						"idem-hold-1",
						seededAccount.customerAccountId(),
						null,
						3_000L,
						"VND",
						"MERCHANT_PAYMENT",
						"test hold",
						"tester",
						UUID.randomUUID(),
						UUID.randomUUID(),
						UUID.randomUUID(),
						"trace-1"));

		assertNotNull(response.paymentOrderId());
		assertNotNull(response.holdId());
		assertEquals(10_000L, response.postedBalanceMinor());
		assertEquals(10_000L, response.availableBalanceBeforeMinor());
		assertEquals(7_000L, response.availableBalanceAfterMinor());

		Map<String, Object> accountRow = jdbcTemplate.queryForMap(
				"SELECT posted_balance_minor, available_balance_minor FROM customer_accounts WHERE customer_account_id = ?",
				seededAccount.customerAccountId());

		assertEquals(10_000L, ((Number) accountRow.get("posted_balance_minor")).longValue());
		assertEquals(7_000L, ((Number) accountRow.get("available_balance_minor")).longValue());

		Map<String, Object> holdRow = jdbcTemplate.queryForMap(
				"SELECT amount_minor, remaining_minor, status FROM funds_holds WHERE hold_id = ?",
				response.holdId());

		assertEquals(3_000L, ((Number) holdRow.get("amount_minor")).longValue());
		assertEquals(3_000L, ((Number) holdRow.get("remaining_minor")).longValue());
		assertEquals("AUTHORIZED", holdRow.get("status"));

		assertEquals(1, count("SELECT COUNT(*) FROM hold_events WHERE hold_id = ?", response.holdId()));
		assertEquals(1, count("SELECT COUNT(*) FROM payment_events WHERE payment_order_id = ?", response.paymentOrderId()));
		assertEquals(1, count(
				"SELECT COUNT(*) FROM audit_events WHERE action = 'PAYMENT_HOLD_AUTHORIZED' AND resource_id = ?",
				response.paymentOrderId().toString()));
		assertEquals(1, count(
				"SELECT COUNT(*) FROM outbox_events WHERE event_type = 'PAYMENT_AUTHORIZED' AND aggregate_id = ?",
				response.paymentOrderId().toString()));
		assertEquals(1, count(
				"SELECT COUNT(*) FROM idempotency_keys WHERE idempotency_key = ? AND status = 'SUCCEEDED'",
				"idem-hold-1"));
	}

	@Test
	void authorizeHoldCannotOverspend() {
		SeededAccount seededAccount = seedAccount(10_000L, 1_000L, "VND");

		assertThrows(
				InsufficientFundsException.class,
				() -> paymentApplicationService.authorizeHold(
						new PaymentApplicationService.AuthorizeHoldRequest(
								"idem-hold-2",
								seededAccount.customerAccountId(),
								null,
								3_000L,
								"VND",
								"MERCHANT_PAYMENT",
								"insufficient hold",
								"tester",
								UUID.randomUUID(),
								UUID.randomUUID(),
								UUID.randomUUID(),
								"trace-2")));

		Map<String, Object> accountRow = jdbcTemplate.queryForMap(
				"SELECT posted_balance_minor, available_balance_minor FROM customer_accounts WHERE customer_account_id = ?",
				seededAccount.customerAccountId());

		assertEquals(10_000L, ((Number) accountRow.get("posted_balance_minor")).longValue());
		assertEquals(1_000L, ((Number) accountRow.get("available_balance_minor")).longValue());

		assertEquals(0, count("SELECT COUNT(*) FROM payment_orders WHERE payer_account_id = ?", seededAccount.customerAccountId()));
		assertEquals(0, count("SELECT COUNT(*) FROM funds_holds WHERE customer_account_id = ?", seededAccount.customerAccountId()));
		assertEquals(0, count(
				"SELECT COUNT(*) FROM audit_events WHERE action = 'PAYMENT_HOLD_AUTHORIZED' AND resource_type = 'PAYMENT_ORDER'"));
		assertEquals(0, count(
				"SELECT COUNT(*) FROM outbox_events WHERE event_type = 'PAYMENT_AUTHORIZED' AND aggregate_type = 'PAYMENT_ORDER' AND aggregate_id IN (SELECT payment_order_id::text FROM payment_orders WHERE payer_account_id = ?)",
				seededAccount.customerAccountId()));
		assertEquals(1, count(
				"SELECT COUNT(*) FROM idempotency_keys WHERE idempotency_key = ? AND status = 'FAILED'",
				"idem-hold-2"));
	}

	@Test
	void authorizeHoldReplayReturnsSameResultWithoutDoubleWrites() {
		SeededAccount seededAccount = seedAccount(10_000L, 10_000L, "VND");
		PaymentApplicationService.AuthorizeHoldRequest request = new PaymentApplicationService.AuthorizeHoldRequest(
				"idem-hold-3",
				seededAccount.customerAccountId(),
				null,
				2_500L,
				"VND",
				"MERCHANT_PAYMENT",
				"replay hold",
				"tester",
				UUID.randomUUID(),
				UUID.randomUUID(),
				UUID.randomUUID(),
				"trace-3");

		PaymentApplicationService.AuthorizeHoldResponse first = paymentApplicationService.authorizeHold(request);
		PaymentApplicationService.AuthorizeHoldResponse second = paymentApplicationService.authorizeHold(request);

		assertEquals(first.paymentOrderId(), second.paymentOrderId());
		assertEquals(first.holdId(), second.holdId());
		assertEquals(first.availableBalanceAfterMinor(), second.availableBalanceAfterMinor());

		assertEquals(1, count("SELECT COUNT(*) FROM payment_orders WHERE payment_order_id = ?", first.paymentOrderId()));
		assertEquals(1, count("SELECT COUNT(*) FROM funds_holds WHERE hold_id = ?", first.holdId()));
		assertEquals(1, count("SELECT COUNT(*) FROM hold_events WHERE hold_id = ?", first.holdId()));
		assertEquals(1, count("SELECT COUNT(*) FROM payment_events WHERE payment_order_id = ?", first.paymentOrderId()));
		assertEquals(1, count(
				"SELECT COUNT(*) FROM audit_events WHERE action = 'PAYMENT_HOLD_AUTHORIZED' AND resource_id = ?",
				first.paymentOrderId().toString()));
		assertEquals(1, count(
				"SELECT COUNT(*) FROM outbox_events WHERE event_type = 'PAYMENT_AUTHORIZED' AND aggregate_id = ?",
				first.paymentOrderId().toString()));
	}

	@Test
	void authorizeHoldRejectsSameKeyWithDifferentPayload() {
		SeededAccount seededAccount = seedAccount(10_000L, 10_000L, "VND");

		paymentApplicationService.authorizeHold(
				new PaymentApplicationService.AuthorizeHoldRequest(
						"idem-hold-4",
						seededAccount.customerAccountId(),
						null,
						1_000L,
						"VND",
						"MERCHANT_PAYMENT",
						"first hold",
						"tester",
						UUID.randomUUID(),
						UUID.randomUUID(),
						UUID.randomUUID(),
						"trace-4a"));

		assertThrows(
				IdempotencyConflictException.class,
				() -> paymentApplicationService.authorizeHold(
						new PaymentApplicationService.AuthorizeHoldRequest(
								"idem-hold-4",
								seededAccount.customerAccountId(),
								null,
								2_000L,
								"VND",
								"MERCHANT_PAYMENT",
								"second hold",
								"tester",
								UUID.randomUUID(),
								UUID.randomUUID(),
								UUID.randomUUID(),
								"trace-4b")));

		assertEquals(1, count("SELECT COUNT(*) FROM payment_orders WHERE payer_account_id = ?", seededAccount.customerAccountId()));
		assertEquals(1, count("SELECT COUNT(*) FROM funds_holds WHERE customer_account_id = ?", seededAccount.customerAccountId()));
		assertEquals(1, count(
				"SELECT COUNT(*) FROM idempotency_keys WHERE idempotency_key = ? AND status = 'SUCCEEDED'",
				"idem-hold-4"));
	}

	@Test
	void captureHoldPostsBalancedJournalUpdatesPostedAndWritesAuditAndOutbox() {
		SeededAccount seededAccount = seedAccount(10_000L, 10_000L, "VND");
		SeededLedgerAccounts ledgerAccounts = seedLedgerAccounts("VND");

		PaymentApplicationService.AuthorizeHoldResponse authorization = paymentApplicationService.authorizeHold(
				new PaymentApplicationService.AuthorizeHoldRequest(
						"idem-capture-auth-1",
						seededAccount.customerAccountId(),
						null,
						3_000L,
						"VND",
						"MERCHANT_PAYMENT",
						"capture setup",
						"tester",
						UUID.randomUUID(),
						UUID.randomUUID(),
						UUID.randomUUID(),
						"trace-capture-auth"));

		PaymentApplicationService.CaptureHoldResponse capture = paymentApplicationService.captureHold(
				new PaymentApplicationService.CaptureHoldRequest(
						"idem-capture-1",
						authorization.holdId(),
						3_000L,
						ledgerAccounts.debitLedgerAccountId(),
						ledgerAccounts.creditLedgerAccountId(),
						null,
						"tester",
						UUID.randomUUID(),
						UUID.randomUUID(),
						UUID.randomUUID(),
						"trace-capture-1"));

		assertNotNull(capture.journalId());
		assertEquals(authorization.paymentOrderId(), capture.paymentOrderId());
		assertEquals(authorization.holdId(), capture.holdId());
		assertEquals(3_000L, capture.capturedAmountMinor());
		assertEquals(0L, capture.remainingAmountMinor());
		assertEquals("FULLY_CAPTURED", capture.holdStatus());
		assertEquals("CAPTURED", capture.paymentStatus());

		Map<String, Object> accountRow = jdbcTemplate.queryForMap(
				"SELECT posted_balance_minor, available_balance_minor FROM customer_accounts WHERE customer_account_id = ?",
				seededAccount.customerAccountId());

		assertEquals(7_000L, ((Number) accountRow.get("posted_balance_minor")).longValue());
		assertEquals(7_000L, ((Number) accountRow.get("available_balance_minor")).longValue());

		Map<String, Object> holdRow = jdbcTemplate.queryForMap(
				"SELECT remaining_minor, status FROM funds_holds WHERE hold_id = ?",
				capture.holdId());

		assertEquals(0L, ((Number) holdRow.get("remaining_minor")).longValue());
		assertEquals("FULLY_CAPTURED", holdRow.get("status"));

		Map<String, Object> journalSums = jdbcTemplate.queryForMap(
				"""
				SELECT COALESCE(SUM(CASE WHEN entry_side = 'D' THEN amount_minor ELSE 0 END), 0) AS total_debit,
				       COALESCE(SUM(CASE WHEN entry_side = 'C' THEN amount_minor ELSE 0 END), 0) AS total_credit,
				       COUNT(*) AS posting_count
				FROM ledger_postings
				WHERE journal_id = ?
				""",
				capture.journalId());

		assertEquals(3_000L, ((Number) journalSums.get("total_debit")).longValue());
		assertEquals(3_000L, ((Number) journalSums.get("total_credit")).longValue());
		assertEquals(2L, ((Number) journalSums.get("posting_count")).longValue());

		assertEquals(1, count(
				"SELECT COUNT(*) FROM audit_events WHERE action = 'PAYMENT_HOLD_CAPTURED' AND resource_id = ?",
				capture.holdId().toString()));
		assertEquals(1, count(
				"SELECT COUNT(*) FROM outbox_events WHERE event_type = 'PAYMENT_CAPTURED' AND aggregate_id = ?",
				capture.holdId().toString()));
	}

	@Test
	void voidHoldRestoresAvailableLeavesPostedUnchangedAndWritesAuditAndOutbox() {
		SeededAccount seededAccount = seedAccount(10_000L, 10_000L, "VND");

		PaymentApplicationService.AuthorizeHoldResponse authorization = paymentApplicationService.authorizeHold(
				new PaymentApplicationService.AuthorizeHoldRequest(
						"idem-void-auth-1",
						seededAccount.customerAccountId(),
						null,
						4_000L,
						"VND",
						"MERCHANT_PAYMENT",
						"void setup",
						"tester",
						UUID.randomUUID(),
						UUID.randomUUID(),
						UUID.randomUUID(),
						"trace-void-auth"));

		PaymentApplicationService.VoidHoldResponse voidResponse = paymentApplicationService.voidHold(
				new PaymentApplicationService.VoidHoldRequest(
						"idem-void-1",
						authorization.holdId(),
						"tester",
						UUID.randomUUID(),
						UUID.randomUUID(),
						UUID.randomUUID(),
						"trace-void-1"));

		assertEquals(authorization.paymentOrderId(), voidResponse.paymentOrderId());
		assertEquals(authorization.holdId(), voidResponse.holdId());
		assertEquals(4_000L, voidResponse.restoredAmountMinor());
		assertEquals(6_000L, voidResponse.availableBalanceBeforeMinor());
		assertEquals(10_000L, voidResponse.availableBalanceAfterMinor());
		assertEquals("VOIDED", voidResponse.status());

		Map<String, Object> accountRow = jdbcTemplate.queryForMap(
				"SELECT posted_balance_minor, available_balance_minor FROM customer_accounts WHERE customer_account_id = ?",
				seededAccount.customerAccountId());

		assertEquals(10_000L, ((Number) accountRow.get("posted_balance_minor")).longValue());
		assertEquals(10_000L, ((Number) accountRow.get("available_balance_minor")).longValue());

		Map<String, Object> holdRow = jdbcTemplate.queryForMap(
				"SELECT remaining_minor, status FROM funds_holds WHERE hold_id = ?",
				voidResponse.holdId());

		assertEquals(0L, ((Number) holdRow.get("remaining_minor")).longValue());
		assertEquals("VOIDED", holdRow.get("status"));

		assertEquals(0, count(
				"SELECT COUNT(*) FROM ledger_journals WHERE reference_type = 'PAYMENT_ORDER' AND reference_id = ?",
				authorization.paymentOrderId()));
		assertEquals(1, count(
				"SELECT COUNT(*) FROM audit_events WHERE action = 'PAYMENT_HOLD_VOIDED' AND resource_id = ?",
				voidResponse.holdId().toString()));
		assertEquals(1, count(
				"SELECT COUNT(*) FROM outbox_events WHERE event_type = 'PAYMENT_VOIDED' AND aggregate_id = ?",
				voidResponse.holdId().toString()));
	}

	private SeededAccount seedAccount(long postedBalanceMinor, long availableBalanceMinor, String currency) {
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

		return new SeededAccount(customerId, productId, customerAccountId);
	}

	private SeededLedgerAccounts seedLedgerAccounts(String currency) {
		UUID debitLedgerAccountId = UUID.randomUUID();
		UUID creditLedgerAccountId = UUID.randomUUID();

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
				"DEBIT-" + debitLedgerAccountId.toString().substring(0, 8),
				"Customer Deposit Liability",
				currency);

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
				"CREDIT-" + creditLedgerAccountId.toString().substring(0, 8),
				"Merchant Settlement Asset",
				currency);

		return new SeededLedgerAccounts(debitLedgerAccountId, creditLedgerAccountId);
	}

	private int count(String sql, Object... args) {
		Integer count = jdbcTemplate.queryForObject(sql, Integer.class, args);
		return count == null ? 0 : count;
	}

	private record SeededAccount(UUID customerId, UUID productId, UUID customerAccountId) {
	}

	private record SeededLedgerAccounts(UUID debitLedgerAccountId, UUID creditLedgerAccountId) {
	}
}
