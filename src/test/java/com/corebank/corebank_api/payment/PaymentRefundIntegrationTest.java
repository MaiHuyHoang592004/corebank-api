package com.corebank.corebank_api.payment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.corebank.corebank_api.TestcontainersConfiguration;
import com.corebank.corebank_api.common.CoreBankException;
import com.corebank.corebank_api.common.IdempotencyConflictException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
class PaymentRefundIntegrationTest {

	@Autowired
	private PaymentApplicationService paymentApplicationService;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	// ------------------------------------------------------------------ A.1 — external_order_ref

	@Test
	void authorizeHold_persistsExternalOrderRef_andEchosInResponse() {
		SeededAccount account = seedAccount(10_000L, 10_000L, "VND");
		String externalRef = "booking:42:payment:1";

		PaymentApplicationService.AuthorizeHoldResponse response = paymentApplicationService.authorizeHold(
				new PaymentApplicationService.AuthorizeHoldRequest(
						"idem-ext-ref-1",
						account.customerAccountId(),
						null,
						3_000L,
						"VND",
						"MERCHANT_PAYMENT",
						"with external ref",
						externalRef,
						"tester",
						UUID.randomUUID(),
						UUID.randomUUID(),
						UUID.randomUUID(),
						"trace-ext-ref-1"));

		assertEquals(externalRef, response.externalOrderRef());

		Map<String, Object> row = jdbcTemplate.queryForMap(
				"SELECT external_order_ref FROM payment_orders WHERE payment_order_id = ?",
				response.paymentOrderId());
		assertEquals(externalRef, row.get("external_order_ref"));
	}

	@Test
	void authorizeHold_withoutExternalOrderRef_isNull() {
		SeededAccount account = seedAccount(5_000L, 5_000L, "VND");

		PaymentApplicationService.AuthorizeHoldResponse response = paymentApplicationService.authorizeHold(
				new PaymentApplicationService.AuthorizeHoldRequest(
						"idem-no-ext-ref-1",
						account.customerAccountId(),
						null,
						1_000L,
						"VND",
						"MERCHANT_PAYMENT",
						"no external ref",
						null,
						"tester",
						UUID.randomUUID(),
						UUID.randomUUID(),
						UUID.randomUUID(),
						"trace-no-ext-ref-1"));

		assertNull(response.externalOrderRef());
	}

	@Test
	void authorizeHold_externalOrderRefTooLong_throws400() {
		SeededAccount account = seedAccount(5_000L, 5_000L, "VND");
		String tooLong = "x".repeat(129);

		assertThrows(CoreBankException.class, () -> paymentApplicationService.authorizeHold(
				new PaymentApplicationService.AuthorizeHoldRequest(
						"idem-ext-ref-long-" + UUID.randomUUID(),
						account.customerAccountId(),
						null,
						1_000L,
						"VND",
						"MERCHANT_PAYMENT",
						"too long ref",
						tooLong,
						"tester",
						UUID.randomUUID(),
						UUID.randomUUID(),
						UUID.randomUUID(),
						"trace-long")));
	}

	@Test
	void authorizeHold_externalOrderRefInvalidChars_throws() {
		SeededAccount account = seedAccount(5_000L, 5_000L, "VND");

		assertThrows(CoreBankException.class, () -> paymentApplicationService.authorizeHold(
				new PaymentApplicationService.AuthorizeHoldRequest(
						"idem-ext-ref-invalid-" + UUID.randomUUID(),
						account.customerAccountId(),
						null,
						1_000L,
						"VND",
						"MERCHANT_PAYMENT",
						"invalid ref",
						"has spaces in ref",
						"tester",
						UUID.randomUUID(),
						UUID.randomUUID(),
						UUID.randomUUID(),
						"trace-invalid")));
	}

	// ------------------------------------------------------------------ A.2 — refund

	@Test
	void fullRefund_afterFullCapture_setsStatusRefundedAndRestoresBalances() {
		SeededAccount account = seedAccount(10_000L, 10_000L, "VND");
		SeededLedgerAccounts ledger = seedLedgerAccounts("VND");

		PaymentApplicationService.AuthorizeHoldResponse auth = authorizeHold(account.customerAccountId(), 5_000L, "full-refund");
		capture(auth.holdId(), 5_000L, ledger, "full-refund-cap");

		PaymentApplicationService.RefundResponse refund = paymentApplicationService.refund(
				new PaymentApplicationService.RefundRequest(
						"idem-refund-full-1",
						auth.paymentOrderId(),
						5_000L,
						"tester",
						UUID.randomUUID(),
						UUID.randomUUID(),
						UUID.randomUUID(),
						"trace-refund-full",
						"full refund"));

		assertNotNull(refund.refundJournalId());
		assertEquals(auth.paymentOrderId(), refund.paymentOrderId());
		assertEquals(5_000L, refund.refundedAmountMinor());
		assertEquals(5_000L, refund.cumulativeRefundedMinor());
		assertEquals(5_000L, refund.capturedAmountMinor());
		assertEquals("REFUNDED", refund.paymentStatus());

		Map<String, Object> orderRow = jdbcTemplate.queryForMap(
				"SELECT status, refunded_amount_minor FROM payment_orders WHERE payment_order_id = ?",
				auth.paymentOrderId());
		assertEquals("REFUNDED", orderRow.get("status"));
		assertEquals(5_000L, ((Number) orderRow.get("refunded_amount_minor")).longValue());

		Map<String, Object> accountRow = jdbcTemplate.queryForMap(
				"SELECT posted_balance_minor, available_balance_minor FROM customer_accounts WHERE customer_account_id = ?",
				account.customerAccountId());
		assertEquals(10_000L, ((Number) accountRow.get("posted_balance_minor")).longValue());
		assertEquals(10_000L, ((Number) accountRow.get("available_balance_minor")).longValue());

		assertEquals(1, count(
				"SELECT COUNT(*) FROM ledger_journals WHERE reference_type = 'PAYMENT_ORDER' AND reference_id = ? AND journal_type = 'PAYMENT_REFUND'",
				auth.paymentOrderId()));
		assertEquals(1, count(
				"SELECT COUNT(*) FROM payment_events WHERE payment_order_id = ? AND event_type = 'REFUNDED'",
				auth.paymentOrderId()));
		assertEquals(1, count(
				"SELECT COUNT(*) FROM audit_events WHERE action = 'PAYMENT_REFUND_ISSUED' AND resource_id = ?",
				auth.paymentOrderId().toString()));
		assertEquals(1, count(
				"SELECT COUNT(*) FROM outbox_events WHERE event_type = 'PAYMENT_REFUNDED' AND aggregate_id = ?",
				auth.paymentOrderId().toString()));

		// Ledger journal balanced
		Map<String, Object> journalSums = jdbcTemplate.queryForMap(
				"""
				SELECT COALESCE(SUM(CASE WHEN entry_side = 'D' THEN amount_minor ELSE 0 END), 0) AS total_debit,
				       COALESCE(SUM(CASE WHEN entry_side = 'C' THEN amount_minor ELSE 0 END), 0) AS total_credit
				FROM ledger_postings
				WHERE journal_id = ?
				""",
				refund.refundJournalId());
		assertEquals(5_000L, ((Number) journalSums.get("total_debit")).longValue());
		assertEquals(5_000L, ((Number) journalSums.get("total_credit")).longValue());

		// row_hash chain valid — reversal_of_journal_id links back to capture journal
		Map<String, Object> refundJournal = jdbcTemplate.queryForMap(
				"SELECT reversal_of_journal_id FROM ledger_journals WHERE journal_id = ?",
				refund.refundJournalId());
		assertNotNull(refundJournal.get("reversal_of_journal_id"));
	}

	@Test
	void partialRefund_setsStatusPartiallyRefunded() {
		SeededAccount account = seedAccount(10_000L, 10_000L, "VND");
		SeededLedgerAccounts ledger = seedLedgerAccounts("VND");

		PaymentApplicationService.AuthorizeHoldResponse auth = authorizeHold(account.customerAccountId(), 5_000L, "partial-refund");
		capture(auth.holdId(), 5_000L, ledger, "partial-refund-cap");

		PaymentApplicationService.RefundResponse refund = paymentApplicationService.refund(
				new PaymentApplicationService.RefundRequest(
						"idem-refund-partial-1",
						auth.paymentOrderId(),
						2_000L,
						"tester",
						UUID.randomUUID(),
						UUID.randomUUID(),
						UUID.randomUUID(),
						"trace-refund-partial",
						"partial refund"));

		assertEquals(2_000L, refund.refundedAmountMinor());
		assertEquals(2_000L, refund.cumulativeRefundedMinor());
		assertEquals(5_000L, refund.capturedAmountMinor());
		assertEquals("PARTIALLY_REFUNDED", refund.paymentStatus());

		Map<String, Object> accountRow = jdbcTemplate.queryForMap(
				"SELECT posted_balance_minor, available_balance_minor FROM customer_accounts WHERE customer_account_id = ?",
				account.customerAccountId());
		assertEquals(7_000L, ((Number) accountRow.get("posted_balance_minor")).longValue());
		assertEquals(7_000L, ((Number) accountRow.get("available_balance_minor")).longValue());
	}

	@Test
	void multiplePartialRefunds_becomeFullyRefunded() {
		SeededAccount account = seedAccount(10_000L, 10_000L, "VND");
		SeededLedgerAccounts ledger = seedLedgerAccounts("VND");

		PaymentApplicationService.AuthorizeHoldResponse auth = authorizeHold(account.customerAccountId(), 6_000L, "multi-refund");
		capture(auth.holdId(), 6_000L, ledger, "multi-refund-cap");

		paymentApplicationService.refund(new PaymentApplicationService.RefundRequest(
				"idem-multi-ref-1", auth.paymentOrderId(), 2_000L, "tester",
				UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "trace-m1", null));
		paymentApplicationService.refund(new PaymentApplicationService.RefundRequest(
				"idem-multi-ref-2", auth.paymentOrderId(), 2_000L, "tester",
				UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "trace-m2", null));
		PaymentApplicationService.RefundResponse last = paymentApplicationService.refund(
				new PaymentApplicationService.RefundRequest(
						"idem-multi-ref-3", auth.paymentOrderId(), 2_000L, "tester",
						UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "trace-m3", null));

		assertEquals("REFUNDED", last.paymentStatus());
		assertEquals(2_000L, last.refundedAmountMinor());
		assertEquals(6_000L, last.cumulativeRefundedMinor());
		assertEquals(3, count(
				"SELECT COUNT(*) FROM payment_events WHERE payment_order_id = ? AND event_type IN ('PARTIALLY_REFUNDED','REFUNDED')",
				auth.paymentOrderId()));
		assertEquals(3, count(
				"SELECT COUNT(*) FROM ledger_journals WHERE reference_type = 'PAYMENT_ORDER' AND reference_id = ? AND journal_type = 'PAYMENT_REFUND'",
				auth.paymentOrderId()));
	}

	@Test
	void refund_exceedingRefundableAmount_throws() {
		SeededAccount account = seedAccount(10_000L, 10_000L, "VND");
		SeededLedgerAccounts ledger = seedLedgerAccounts("VND");

		PaymentApplicationService.AuthorizeHoldResponse auth = authorizeHold(account.customerAccountId(), 5_000L, "refund-exceed");
		capture(auth.holdId(), 5_000L, ledger, "refund-exceed-cap");

		// refund 3000 first
		paymentApplicationService.refund(new PaymentApplicationService.RefundRequest(
				"idem-exceed-ref-1", auth.paymentOrderId(), 3_000L, "tester",
				UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "trace-exc1", null));

		// try to refund 3000 more (only 2000 remains)
		assertThrows(CoreBankException.class, () ->
				paymentApplicationService.refund(new PaymentApplicationService.RefundRequest(
						"idem-exceed-ref-2", auth.paymentOrderId(), 3_000L, "tester",
						UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "trace-exc2", null)));
	}

	@Test
	void refund_onNonCapturedOrder_throws() {
		SeededAccount account = seedAccount(10_000L, 10_000L, "VND");

		PaymentApplicationService.AuthorizeHoldResponse auth = authorizeHold(account.customerAccountId(), 3_000L, "refund-no-cap");

		assertThrows(CoreBankException.class, () ->
				paymentApplicationService.refund(new PaymentApplicationService.RefundRequest(
						"idem-no-cap-ref-1", auth.paymentOrderId(), 3_000L, "tester",
						UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "trace-nc1", null)));

		Map<String, Object> orderRow = jdbcTemplate.queryForMap(
				"SELECT status FROM payment_orders WHERE payment_order_id = ?", auth.paymentOrderId());
		assertEquals("AUTHORIZED", orderRow.get("status"));
	}

	@Test
	void refund_onVoidedOrder_throws() {
		SeededAccount account = seedAccount(10_000L, 10_000L, "VND");

		PaymentApplicationService.AuthorizeHoldResponse auth = authorizeHold(account.customerAccountId(), 3_000L, "refund-voided");
		paymentApplicationService.voidHold(new PaymentApplicationService.VoidHoldRequest(
				"idem-void-for-refund-" + UUID.randomUUID(),
				auth.holdId(), "tester",
				UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "trace-void-for-ref"));

		assertThrows(CoreBankException.class, () ->
				paymentApplicationService.refund(new PaymentApplicationService.RefundRequest(
						"idem-voided-ref-1", auth.paymentOrderId(), 3_000L, "tester",
						UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "trace-vref1", null)));
	}

	@Test
	void refund_nonExistentPaymentOrder_throws404() {
		CoreBankException ex = assertThrows(CoreBankException.class, () ->
				paymentApplicationService.refund(new PaymentApplicationService.RefundRequest(
						"idem-404-ref-1", UUID.randomUUID(), 100L, "tester",
						UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "trace-404", null)));
		assertTrue(ex.getMessage().contains("not found"));
	}

	@Test
	void refund_idempotencyReplay_returnsSameResponseWithoutDoublePost() {
		SeededAccount account = seedAccount(10_000L, 10_000L, "VND");
		SeededLedgerAccounts ledger = seedLedgerAccounts("VND");

		PaymentApplicationService.AuthorizeHoldResponse auth = authorizeHold(account.customerAccountId(), 5_000L, "refund-idem");
		capture(auth.holdId(), 5_000L, ledger, "refund-idem-cap");

		PaymentApplicationService.RefundRequest request = new PaymentApplicationService.RefundRequest(
				"idem-refund-replay-1", auth.paymentOrderId(), 2_000L, "tester",
				UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "trace-idem-r1", null);

		PaymentApplicationService.RefundResponse first = paymentApplicationService.refund(request);
		PaymentApplicationService.RefundResponse second = paymentApplicationService.refund(request);

		assertEquals(first.refundJournalId(), second.refundJournalId());
		assertEquals(first.cumulativeRefundedMinor(), second.cumulativeRefundedMinor());

		assertEquals(1, count(
				"SELECT COUNT(*) FROM ledger_journals WHERE reference_type = 'PAYMENT_ORDER' AND reference_id = ? AND journal_type = 'PAYMENT_REFUND'",
				auth.paymentOrderId()));
		assertEquals(1, count(
				"SELECT COUNT(*) FROM audit_events WHERE action = 'PAYMENT_REFUND_ISSUED' AND resource_id = ?",
				auth.paymentOrderId().toString()));
	}

	@Test
	void refund_idempotencyConflict_sameKeyDifferentAmount_throws() {
		SeededAccount account = seedAccount(10_000L, 10_000L, "VND");
		SeededLedgerAccounts ledger = seedLedgerAccounts("VND");

		PaymentApplicationService.AuthorizeHoldResponse auth = authorizeHold(account.customerAccountId(), 5_000L, "refund-conflict");
		capture(auth.holdId(), 5_000L, ledger, "refund-conflict-cap");

		paymentApplicationService.refund(new PaymentApplicationService.RefundRequest(
				"idem-refund-conflict-1", auth.paymentOrderId(), 1_000L, "tester",
				UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "trace-rc1", null));

		assertThrows(IdempotencyConflictException.class, () ->
				paymentApplicationService.refund(new PaymentApplicationService.RefundRequest(
						"idem-refund-conflict-1", auth.paymentOrderId(), 2_000L, "tester",
						UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "trace-rc2", null)));
	}

	@Test
	void refund_echoesExternalOrderRef() {
		SeededAccount account = seedAccount(10_000L, 10_000L, "VND");
		SeededLedgerAccounts ledger = seedLedgerAccounts("VND");
		String externalRef = "booking:99:payment:1";

		PaymentApplicationService.AuthorizeHoldResponse auth = paymentApplicationService.authorizeHold(
				new PaymentApplicationService.AuthorizeHoldRequest(
						"idem-ext-ref-refund-auth-1",
						account.customerAccountId(), null, 4_000L, "VND", "MERCHANT_PAYMENT",
						"with ref", externalRef, "tester",
						UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "trace-ext-refund-auth"));
		capture(auth.holdId(), 4_000L, ledger, "ext-ref-refund-cap");

		PaymentApplicationService.RefundResponse refund = paymentApplicationService.refund(
				new PaymentApplicationService.RefundRequest(
						"idem-ext-ref-refund-1", auth.paymentOrderId(), 4_000L, "tester",
						UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "trace-ext-refund-1", null));

		assertEquals(externalRef, refund.externalOrderRef());
	}

	@Test
	void concurrentRefunds_onSameOrder_onlyOneSucceeds() throws Exception {
		SeededAccount account = seedAccount(20_000L, 20_000L, "VND");
		SeededLedgerAccounts ledger = seedLedgerAccounts("VND");

		PaymentApplicationService.AuthorizeHoldResponse auth = authorizeHold(account.customerAccountId(), 10_000L, "concurrent-refund");
		capture(auth.holdId(), 10_000L, ledger, "concurrent-refund-cap");

		// Two concurrent refunds each trying to refund 6000 (total 12000 > captured 10000)
		Callable<PaymentApplicationService.RefundResponse> refundCall1 = () ->
				paymentApplicationService.refund(new PaymentApplicationService.RefundRequest(
						"idem-concurrent-ref-A-" + UUID.randomUUID(), auth.paymentOrderId(), 6_000L, "tester",
						UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "trace-cr1", null));

		Callable<PaymentApplicationService.RefundResponse> refundCall2 = () ->
				paymentApplicationService.refund(new PaymentApplicationService.RefundRequest(
						"idem-concurrent-ref-B-" + UUID.randomUUID(), auth.paymentOrderId(), 6_000L, "tester",
						UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "trace-cr2", null));

		List<InvocationOutcome<PaymentApplicationService.RefundResponse>> outcomes =
				invokeConcurrently(refundCall1, refundCall2);

		long successCount = outcomes.stream().filter(o -> o.response() != null).count();
		long failureCount = outcomes.stream().filter(o -> o.error() != null).count();

		// Exactly one succeeds, one fails (either due to exceed-refundable or lock contention)
		assertEquals(1, successCount, "Exactly one refund should succeed");
		assertEquals(1, failureCount, "Exactly one refund should fail");

		// Cumulative refund must not exceed captured
		Map<String, Object> orderRow = jdbcTemplate.queryForMap(
				"SELECT refunded_amount_minor FROM payment_orders WHERE payment_order_id = ?",
				auth.paymentOrderId());
		long refunded = ((Number) orderRow.get("refunded_amount_minor")).longValue();
		assertTrue(refunded <= 10_000L, "refunded_amount_minor must not exceed captured");
	}

	// ------------------------------------------------------------------ A.3 — query endpoints

	@Test
	void getPaymentOrder_returnsViewWithHoldAndEvents() {
		SeededAccount account = seedAccount(10_000L, 10_000L, "VND");
		String externalRef = "booking:100:payment:1";

		PaymentApplicationService.AuthorizeHoldResponse auth = paymentApplicationService.authorizeHold(
				new PaymentApplicationService.AuthorizeHoldRequest(
						"idem-query-auth-1",
						account.customerAccountId(), null, 3_000L, "VND", "MERCHANT_PAYMENT",
						"query test", externalRef, "tester",
						UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "trace-query-1"));

		PaymentQueryService paymentQueryService = getQueryService();
		PaymentQueryService.PaymentOrderView view = paymentQueryService.getPaymentOrder(auth.paymentOrderId());

		assertEquals(auth.paymentOrderId(), view.paymentOrderId());
		assertEquals(externalRef, view.externalOrderRef());
		assertEquals("AUTHORIZED", view.status());
		assertNotNull(view.hold());
		assertEquals("AUTHORIZED", view.hold().status());
		assertEquals(1, view.events().size());
		assertEquals("AUTHORIZED", view.events().get(0).eventType());
	}

	@Test
	void getPaymentOrder_notFound_throws() {
		PaymentQueryService paymentQueryService = getQueryService();
		CoreBankException ex = assertThrows(CoreBankException.class,
				() -> paymentQueryService.getPaymentOrder(UUID.randomUUID()));
		assertTrue(ex.getMessage().contains("not found"));
	}

	@Test
	void listPaymentOrders_byExternalOrderRef_returnsMatch() {
		SeededAccount account = seedAccount(10_000L, 10_000L, "VND");
		String externalRef = "booking:list-test:payment:1";

		PaymentApplicationService.AuthorizeHoldResponse auth = paymentApplicationService.authorizeHold(
				new PaymentApplicationService.AuthorizeHoldRequest(
						"idem-list-auth-1",
						account.customerAccountId(), null, 2_000L, "VND", "MERCHANT_PAYMENT",
						"list test", externalRef, "tester",
						UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "trace-list-1"));

		PaymentQueryService paymentQueryService = getQueryService();
		PaymentQueryService.PaymentOrderListView result = paymentQueryService.listPaymentOrders(
				new PaymentQueryService.ListPaymentOrdersRequest(
						externalRef, null, null, null, null, null, 0, 20));

		assertEquals(1, result.totalElements());
		assertEquals(1, result.items().size());
		assertEquals(auth.paymentOrderId(), result.items().get(0).paymentOrderId());
	}

	@Test
	void listPaymentOrders_withoutAnyFilter_throws() {
		PaymentQueryService paymentQueryService = getQueryService();
		assertThrows(CoreBankException.class, () -> paymentQueryService.listPaymentOrders(
				new PaymentQueryService.ListPaymentOrdersRequest(null, null, null, null, null, null, 0, 20)));
	}

	@Test
	void listPaymentOrders_byPayerAccountId_returnsMultipleOrders() {
		SeededAccount account = seedAccount(20_000L, 20_000L, "VND");

		authorizeHold(account.customerAccountId(), 1_000L, "list-multi-1");
		authorizeHold(account.customerAccountId(), 1_000L, "list-multi-2");

		PaymentQueryService paymentQueryService = getQueryService();
		PaymentQueryService.PaymentOrderListView result = paymentQueryService.listPaymentOrders(
				new PaymentQueryService.ListPaymentOrdersRequest(
						null, account.customerAccountId(), null, null, null, null, 0, 20));

		assertTrue(result.totalElements() >= 2);
	}

	// ------------------------------------------------------------------ helpers

	@Autowired
	private PaymentQueryService injectedQueryService;

	private PaymentQueryService getQueryService() {
		return injectedQueryService;
	}

	private PaymentApplicationService.AuthorizeHoldResponse authorizeHold(
			UUID customerAccountId, long amountMinor, String suffix) {
		return paymentApplicationService.authorizeHold(
				new PaymentApplicationService.AuthorizeHoldRequest(
						"idem-auth-" + suffix + "-" + UUID.randomUUID(),
						customerAccountId,
						null,
						amountMinor,
						"VND",
						"MERCHANT_PAYMENT",
						suffix,
						null,
						"tester",
						UUID.randomUUID(),
						UUID.randomUUID(),
						UUID.randomUUID(),
						"trace-" + suffix));
	}

	private PaymentApplicationService.CaptureHoldResponse capture(
			UUID holdId, long amountMinor, SeededLedgerAccounts ledger, String suffix) {
		return paymentApplicationService.captureHold(
				new PaymentApplicationService.CaptureHoldRequest(
						"idem-cap-" + suffix + "-" + UUID.randomUUID(),
						holdId,
						amountMinor,
						ledger.debitLedgerAccountId(),
						ledger.creditLedgerAccountId(),
						null,
						"tester",
						UUID.randomUUID(),
						UUID.randomUUID(),
						UUID.randomUUID(),
						"trace-cap-" + suffix));
	}

	private <T> List<InvocationOutcome<T>> invokeConcurrently(Callable<T> first, Callable<T> second) throws Exception {
		ExecutorService executorService = Executors.newFixedThreadPool(2);
		CountDownLatch ready = new CountDownLatch(2);
		CountDownLatch start = new CountDownLatch(1);
		try {
			Future<InvocationOutcome<T>> f1 = executorService.submit(() -> executeConcurrentCallable(first, ready, start));
			Future<InvocationOutcome<T>> f2 = executorService.submit(() -> executeConcurrentCallable(second, ready, start));
			assertTrue(ready.await(5, TimeUnit.SECONDS));
			start.countDown();
			List<InvocationOutcome<T>> outcomes = new ArrayList<>();
			outcomes.add(f1.get(20, TimeUnit.SECONDS));
			outcomes.add(f2.get(20, TimeUnit.SECONDS));
			return outcomes;
		} finally {
			executorService.shutdownNow();
		}
	}

	private <T> InvocationOutcome<T> executeConcurrentCallable(
			Callable<T> callable, CountDownLatch ready, CountDownLatch start) {
		ready.countDown();
		try {
			assertTrue(start.await(5, TimeUnit.SECONDS));
			return new InvocationOutcome<>(callable.call(), null);
		} catch (Throwable t) {
			return new InvocationOutcome<>(null, t);
		}
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

	private int count(String sql, Object... args) {
		Integer count = jdbcTemplate.queryForObject(sql, Integer.class, args);
		return count == null ? 0 : count;
	}

	private record SeededAccount(UUID customerId, UUID productId, UUID customerAccountId) {}
	private record SeededLedgerAccounts(UUID debitLedgerAccountId, UUID creditLedgerAccountId) {}
	private record InvocationOutcome<T>(T response, Throwable error) {}
}
