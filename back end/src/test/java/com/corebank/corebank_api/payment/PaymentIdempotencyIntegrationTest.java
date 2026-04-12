package com.corebank.corebank_api.payment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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
class PaymentIdempotencyIntegrationTest {

	@Autowired
	private PaymentApplicationService paymentApplicationService;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@Test
	void partialCapturePreservesRemainingAmountCorrectly() {
		SeededAccount seededAccount = seedAccount(10_000L, 10_000L, "VND");
		SeededLedgerAccounts ledgerAccounts = seedLedgerAccounts("VND");
		PaymentApplicationService.AuthorizeHoldResponse authorization = authorizeHold(seededAccount.customerAccountId(), 3_000L, "partial");

		PaymentApplicationService.CaptureHoldResponse response = paymentApplicationService.captureHold(
				new PaymentApplicationService.CaptureHoldRequest(
						"idem-partial-capture-1",
						authorization.holdId(),
						1_000L,
						ledgerAccounts.debitLedgerAccountId(),
						ledgerAccounts.creditLedgerAccountId(),
						null,
						"tester",
						UUID.randomUUID(),
						UUID.randomUUID(),
						UUID.randomUUID(),
						"trace-partial-capture"));

		assertEquals(2_000L, response.remainingAmountMinor());
		assertEquals("PARTIALLY_CAPTURED", response.holdStatus());
		assertEquals("PARTIALLY_CAPTURED", response.paymentStatus());

		Map<String, Object> holdRow = jdbcTemplate.queryForMap(
				"SELECT remaining_minor, status FROM funds_holds WHERE hold_id = ?",
				response.holdId());
		assertEquals(2_000L, ((Number) holdRow.get("remaining_minor")).longValue());
		assertEquals("PARTIALLY_CAPTURED", holdRow.get("status"));

		Map<String, Object> accountRow = jdbcTemplate.queryForMap(
				"SELECT posted_balance_minor, available_balance_minor FROM customer_accounts WHERE customer_account_id = ?",
				seededAccount.customerAccountId());
		assertEquals(9_000L, ((Number) accountRow.get("posted_balance_minor")).longValue());
		assertEquals(7_000L, ((Number) accountRow.get("available_balance_minor")).longValue());
	}

	@Test
	void captureReplayWithSameIdempotencyKeyIsReplaySafe() {
		SeededAccount seededAccount = seedAccount(10_000L, 10_000L, "VND");
		SeededLedgerAccounts ledgerAccounts = seedLedgerAccounts("VND");
		PaymentApplicationService.AuthorizeHoldResponse authorization = authorizeHold(seededAccount.customerAccountId(), 3_000L, "capture-replay");

		PaymentApplicationService.CaptureHoldRequest request = new PaymentApplicationService.CaptureHoldRequest(
				"idem-capture-replay-1",
				authorization.holdId(),
				3_000L,
				ledgerAccounts.debitLedgerAccountId(),
				ledgerAccounts.creditLedgerAccountId(),
				null,
				"tester",
				UUID.randomUUID(),
				UUID.randomUUID(),
				UUID.randomUUID(),
				"trace-capture-replay");

		PaymentApplicationService.CaptureHoldResponse first = paymentApplicationService.captureHold(request);
		PaymentApplicationService.CaptureHoldResponse second = paymentApplicationService.captureHold(request);

		assertEquals(first.journalId(), second.journalId());
		assertEquals(first.remainingAmountMinor(), second.remainingAmountMinor());
		assertEquals(1, count(
				"SELECT COUNT(*) FROM ledger_journals WHERE reference_type = 'PAYMENT_ORDER' AND reference_id = ?",
				first.paymentOrderId()));
		assertEquals(1, count(
				"SELECT COUNT(*) FROM audit_events WHERE action = 'PAYMENT_HOLD_CAPTURED' AND resource_id = ?",
				first.holdId().toString()));
		assertEquals(1, count(
				"SELECT COUNT(*) FROM outbox_events WHERE event_type = 'PAYMENT_CAPTURED' AND aggregate_id = ?",
				first.holdId().toString()));
	}

	@Test
	void voidReplayWithSameIdempotencyKeyIsReplaySafe() {
		SeededAccount seededAccount = seedAccount(10_000L, 10_000L, "VND");
		PaymentApplicationService.AuthorizeHoldResponse authorization = authorizeHold(seededAccount.customerAccountId(), 4_000L, "void-replay");

		PaymentApplicationService.VoidHoldRequest request = new PaymentApplicationService.VoidHoldRequest(
				"idem-void-replay-1",
				authorization.holdId(),
				"tester",
				UUID.randomUUID(),
				UUID.randomUUID(),
				UUID.randomUUID(),
				"trace-void-replay");

		PaymentApplicationService.VoidHoldResponse first = paymentApplicationService.voidHold(request);
		PaymentApplicationService.VoidHoldResponse second = paymentApplicationService.voidHold(request);

		assertEquals(first.holdId(), second.holdId());
		assertEquals(first.availableBalanceAfterMinor(), second.availableBalanceAfterMinor());
		assertEquals(1, count(
				"SELECT COUNT(*) FROM audit_events WHERE action = 'PAYMENT_HOLD_VOIDED' AND resource_id = ?",
				first.holdId().toString()));
		assertEquals(1, count(
				"SELECT COUNT(*) FROM outbox_events WHERE event_type = 'PAYMENT_VOIDED' AND aggregate_id = ?",
				first.holdId().toString()));
	}

	@Test
	void captureSameKeyWithDifferentPayloadIsRejected() {
		SeededAccount seededAccount = seedAccount(10_000L, 10_000L, "VND");
		SeededLedgerAccounts ledgerAccounts = seedLedgerAccounts("VND");
		PaymentApplicationService.AuthorizeHoldResponse authorization = authorizeHold(seededAccount.customerAccountId(), 3_000L, "capture-conflict");

		paymentApplicationService.captureHold(new PaymentApplicationService.CaptureHoldRequest(
				"idem-capture-conflict-1",
				authorization.holdId(),
				1_000L,
				ledgerAccounts.debitLedgerAccountId(),
				ledgerAccounts.creditLedgerAccountId(),
				null,
				"tester",
				UUID.randomUUID(),
				UUID.randomUUID(),
				UUID.randomUUID(),
				"trace-capture-conflict-1"));

		assertThrows(
				IdempotencyConflictException.class,
				() -> paymentApplicationService.captureHold(new PaymentApplicationService.CaptureHoldRequest(
						"idem-capture-conflict-1",
						authorization.holdId(),
						2_000L,
						ledgerAccounts.debitLedgerAccountId(),
						ledgerAccounts.creditLedgerAccountId(),
						null,
						"tester",
						UUID.randomUUID(),
						UUID.randomUUID(),
						UUID.randomUUID(),
						"trace-capture-conflict-2")));

		Map<String, Object> holdRow = jdbcTemplate.queryForMap(
				"SELECT remaining_minor, status FROM funds_holds WHERE hold_id = ?",
				authorization.holdId());
		assertEquals(2_000L, ((Number) holdRow.get("remaining_minor")).longValue());
		assertEquals("PARTIALLY_CAPTURED", holdRow.get("status"));
	}

	@Test
	void invalidCaptureFailureDoesNotLeavePermanentlyBlockingState() {
		SeededAccount seededAccount = seedAccount(10_000L, 10_000L, "VND");
		SeededLedgerAccounts ledgerAccounts = seedLedgerAccounts("VND");
		PaymentApplicationService.AuthorizeHoldResponse authorization = authorizeHold(seededAccount.customerAccountId(), 3_000L, "capture-failure");

		PaymentApplicationService.CaptureHoldRequest invalidRequest = new PaymentApplicationService.CaptureHoldRequest(
				"idem-capture-failure-1",
				authorization.holdId(),
				4_000L,
				ledgerAccounts.debitLedgerAccountId(),
				ledgerAccounts.creditLedgerAccountId(),
				null,
				"tester",
				UUID.randomUUID(),
				UUID.randomUUID(),
				UUID.randomUUID(),
				"trace-capture-failure");

		assertThrows(CoreBankException.class, () -> paymentApplicationService.captureHold(invalidRequest));
		assertThrows(CoreBankException.class, () -> paymentApplicationService.captureHold(invalidRequest));

		Map<String, Object> idempotencyRow = jdbcTemplate.queryForMap(
				"SELECT status FROM idempotency_keys WHERE idempotency_key = ?",
				"idem-capture-failure-1");
		assertEquals("FAILED", idempotencyRow.get("status"));

		assertEquals(0, count(
				"SELECT COUNT(*) FROM ledger_journals WHERE reference_type = 'PAYMENT_ORDER' AND reference_id = ?",
				authorization.paymentOrderId()));
		Map<String, Object> holdRow = jdbcTemplate.queryForMap(
				"SELECT remaining_minor, status FROM funds_holds WHERE hold_id = ?",
				authorization.holdId());
		assertEquals(3_000L, ((Number) holdRow.get("remaining_minor")).longValue());
		assertEquals("AUTHORIZED", holdRow.get("status"));
	}

	@Test
	void concurrentDuplicateCaptureRequestsDoNotDoublePost() throws Exception {
		SeededAccount seededAccount = seedAccount(10_000L, 10_000L, "VND");
		SeededLedgerAccounts ledgerAccounts = seedLedgerAccounts("VND");
		PaymentApplicationService.AuthorizeHoldResponse authorization = authorizeHold(seededAccount.customerAccountId(), 3_000L, "capture-concurrent");

		PaymentApplicationService.CaptureHoldRequest request = new PaymentApplicationService.CaptureHoldRequest(
				"idem-capture-concurrent-1",
				authorization.holdId(),
				3_000L,
				ledgerAccounts.debitLedgerAccountId(),
				ledgerAccounts.creditLedgerAccountId(),
				null,
				"tester",
				UUID.randomUUID(),
				UUID.randomUUID(),
				UUID.randomUUID(),
				"trace-capture-concurrent");

		List<InvocationOutcome<PaymentApplicationService.CaptureHoldResponse>> outcomes = invokeConcurrently(
				() -> paymentApplicationService.captureHold(request),
				() -> paymentApplicationService.captureHold(request));

		assertTrue(outcomes.stream().anyMatch(outcome -> outcome.response() != null));
		assertEquals(1, count(
				"SELECT COUNT(*) FROM ledger_journals WHERE reference_type = 'PAYMENT_ORDER' AND reference_id = ?",
				authorization.paymentOrderId()));
		assertEquals(1, count(
				"SELECT COUNT(*) FROM idempotency_keys WHERE idempotency_key = ? AND status = 'SUCCEEDED'",
				request.idempotencyKey()));
		Map<String, Object> holdRow = jdbcTemplate.queryForMap(
				"SELECT remaining_minor, status FROM funds_holds WHERE hold_id = ?",
				authorization.holdId());
		assertEquals(0L, ((Number) holdRow.get("remaining_minor")).longValue());
		assertEquals("FULLY_CAPTURED", holdRow.get("status"));
	}

	@Test
	void concurrentDuplicateVoidRequestsDoNotCorruptBalanceOrState() throws Exception {
		SeededAccount seededAccount = seedAccount(10_000L, 10_000L, "VND");
		PaymentApplicationService.AuthorizeHoldResponse authorization = authorizeHold(seededAccount.customerAccountId(), 4_000L, "void-concurrent");

		PaymentApplicationService.VoidHoldRequest request = new PaymentApplicationService.VoidHoldRequest(
				"idem-void-concurrent-1",
				authorization.holdId(),
				"tester",
				UUID.randomUUID(),
				UUID.randomUUID(),
				UUID.randomUUID(),
				"trace-void-concurrent");

		List<InvocationOutcome<PaymentApplicationService.VoidHoldResponse>> outcomes = invokeConcurrently(
				() -> paymentApplicationService.voidHold(request),
				() -> paymentApplicationService.voidHold(request));

		assertTrue(outcomes.stream().anyMatch(outcome -> outcome.response() != null));
		assertEquals(1, count(
				"SELECT COUNT(*) FROM idempotency_keys WHERE idempotency_key = ? AND status = 'SUCCEEDED'",
				request.idempotencyKey()));
		assertEquals(0, count(
				"SELECT COUNT(*) FROM ledger_journals WHERE reference_type = 'PAYMENT_ORDER' AND reference_id = ?",
				authorization.paymentOrderId()));
		Map<String, Object> holdRow = jdbcTemplate.queryForMap(
				"SELECT remaining_minor, status FROM funds_holds WHERE hold_id = ?",
				authorization.holdId());
		assertEquals(0L, ((Number) holdRow.get("remaining_minor")).longValue());
		assertEquals("VOIDED", holdRow.get("status"));
		Map<String, Object> accountRow = jdbcTemplate.queryForMap(
				"SELECT posted_balance_minor, available_balance_minor FROM customer_accounts WHERE customer_account_id = ?",
				seededAccount.customerAccountId());
		assertEquals(10_000L, ((Number) accountRow.get("posted_balance_minor")).longValue());
		assertEquals(10_000L, ((Number) accountRow.get("available_balance_minor")).longValue());
	}

	private PaymentApplicationService.AuthorizeHoldResponse authorizeHold(UUID customerAccountId, long amountMinor, String suffix) {
		return paymentApplicationService.authorizeHold(
				new PaymentApplicationService.AuthorizeHoldRequest(
						"idem-auth-" + suffix + "-" + UUID.randomUUID(),
						customerAccountId,
						null,
						amountMinor,
						"VND",
						"MERCHANT_PAYMENT",
						suffix,
						"tester",
						UUID.randomUUID(),
						UUID.randomUUID(),
						UUID.randomUUID(),
						"trace-" + suffix));
	}

	private <T> List<InvocationOutcome<T>> invokeConcurrently(Callable<T> first, Callable<T> second) throws Exception {
		ExecutorService executorService = Executors.newFixedThreadPool(2);
		CountDownLatch ready = new CountDownLatch(2);
		CountDownLatch start = new CountDownLatch(1);

		try {
			Future<InvocationOutcome<T>> firstFuture = executorService.submit(() -> executeConcurrentCallable(first, ready, start));
			Future<InvocationOutcome<T>> secondFuture = executorService.submit(() -> executeConcurrentCallable(second, ready, start));

			assertTrue(ready.await(5, TimeUnit.SECONDS));
			start.countDown();

			List<InvocationOutcome<T>> outcomes = new ArrayList<>();
			outcomes.add(firstFuture.get(20, TimeUnit.SECONDS));
			outcomes.add(secondFuture.get(20, TimeUnit.SECONDS));
			return outcomes;
		} finally {
			executorService.shutdownNow();
		}
	}

	private <T> InvocationOutcome<T> executeConcurrentCallable(
			Callable<T> callable,
			CountDownLatch ready,
			CountDownLatch start) {
		ready.countDown();
		try {
			assertTrue(start.await(5, TimeUnit.SECONDS));
			return new InvocationOutcome<>(callable.call(), null);
		} catch (Throwable throwable) {
			return new InvocationOutcome<>(null, throwable);
		}
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

	private record InvocationOutcome<T>(T response, Throwable error) {
	}
}
