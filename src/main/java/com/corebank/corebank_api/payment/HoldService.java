package com.corebank.corebank_api.payment;

import com.corebank.corebank_api.account.AccountBalanceRepository;
import com.corebank.corebank_api.account.CustomerAccount;
import com.corebank.corebank_api.common.CoreBankException;
import com.corebank.corebank_api.common.InsufficientFundsException;
import com.corebank.corebank_api.ledger.LedgerCommandService;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class HoldService {

	private static final RowMapper<HoldSnapshot> HOLD_SNAPSHOT_ROW_MAPPER =
			new RowMapper<>() {
				@Override
				public HoldSnapshot mapRow(ResultSet rs, int rowNum) throws SQLException {
					return new HoldSnapshot(
							rs.getObject("hold_id", UUID.class),
							rs.getObject("payment_order_id", UUID.class),
							rs.getObject("customer_account_id", UUID.class),
							rs.getObject("payee_account_id", UUID.class),
							rs.getLong("amount_minor"),
							rs.getLong("remaining_minor"),
							rs.getString("hold_status"),
							rs.getString("payment_status"),
							rs.getString("currency"),
							rs.getString("payment_type"),
							rs.getString("external_order_ref"));
				}
			};

	private final JdbcTemplate jdbcTemplate;
	private final AccountBalanceRepository accountBalanceRepository;
	private final LedgerCommandService ledgerCommandService;

	public HoldService(
			JdbcTemplate jdbcTemplate,
			AccountBalanceRepository accountBalanceRepository,
			LedgerCommandService ledgerCommandService) {
		this.jdbcTemplate = jdbcTemplate;
		this.accountBalanceRepository = accountBalanceRepository;
		this.ledgerCommandService = ledgerCommandService;
	}

	public AuthorizationResult authorizeHold(AuthorizeHoldCommand command) {
		CustomerAccount lockedAccount = accountBalanceRepository.lockById(command.payerAccountId())
				.orElseThrow(() -> new CoreBankException("Customer account not found: " + command.payerAccountId()));

		if (command.amountMinor() <= 0) {
			throw new CoreBankException("Hold amount must be positive");
		}

		if (!lockedAccount.getCurrency().equals(command.currency())) {
			throw new CoreBankException("Account currency does not match hold currency");
		}

		if (command.externalOrderRef() != null) {
			if (command.externalOrderRef().length() > 128) {
				throw new CoreBankException("externalOrderRef exceeds maximum length of 128");
			}
			if (!command.externalOrderRef().matches("^[A-Za-z0-9:_\\-]{1,128}$")) {
				throw new CoreBankException("externalOrderRef contains invalid characters; allowed: A-Z a-z 0-9 : _ -");
			}
		}

		long availableBefore = lockedAccount.getAvailableBalanceMinor();
		if (availableBefore < command.amountMinor()) {
			throw new InsufficientFundsException("Insufficient available balance for hold authorization");
		}

		long availableAfter = availableBefore - command.amountMinor();
		boolean updated = accountBalanceRepository.updateAvailableBalance(
				lockedAccount.getCustomerAccountId(),
				availableAfter,
				lockedAccount.getVersion());

		if (!updated) {
			throw new CoreBankException("Failed to update available balance for hold authorization");
		}

		UUID paymentOrderId = UUID.randomUUID();
		UUID holdId = UUID.randomUUID();

		jdbcTemplate.update(
				"""
				INSERT INTO payment_orders (
				    payment_order_id,
				    payer_account_id,
				    payee_account_id,
				    amount_minor,
				    currency,
				    payment_type,
				    status,
				    description,
				    external_order_ref
				) VALUES (?, ?, ?, ?, ?, ?, 'AUTHORIZED', ?, ?)
				""",
				paymentOrderId,
				command.payerAccountId(),
				command.payeeAccountId(),
				command.amountMinor(),
				command.currency(),
				command.paymentType(),
				command.description(),
				command.externalOrderRef());

		jdbcTemplate.update(
				"""
				INSERT INTO funds_holds (
				    hold_id,
				    payment_order_id,
				    customer_account_id,
				    amount_minor,
				    remaining_minor,
				    status
				) VALUES (?, ?, ?, ?, ?, 'AUTHORIZED')
				""",
				holdId,
				paymentOrderId,
				command.payerAccountId(),
				command.amountMinor(),
				command.amountMinor());

		jdbcTemplate.update(
				"""
				INSERT INTO hold_events (
				    hold_id,
				    event_type,
				    amount_minor,
				    metadata_json
				) VALUES (?, 'AUTHORIZED', ?, ?::jsonb)
				""",
				holdId,
				command.amountMinor(),
				"{}");

		jdbcTemplate.update(
				"""
				INSERT INTO payment_events (
				    payment_order_id,
				    event_type,
				    amount_minor,
				    metadata_json
				) VALUES (?, 'AUTHORIZED', ?, ?::jsonb)
				""",
				paymentOrderId,
				command.amountMinor(),
				"{}");

		return new AuthorizationResult(
				paymentOrderId,
				holdId,
				lockedAccount.getCustomerAccountId(),
				lockedAccount.getPostedBalanceMinor(),
				availableBefore,
				availableAfter,
				command.amountMinor(),
				command.currency(),
				"AUTHORIZED",
				command.externalOrderRef());
	}

	public CaptureResult captureHold(CaptureHoldCommand command) {
		if (command.amountMinor() <= 0) {
			throw new CoreBankException("Capture amount must be positive");
		}

		HoldSnapshot hold = lockHold(command.holdId())
				.orElseThrow(() -> new CoreBankException("Funds hold not found: " + command.holdId()));

		if (!List.of("AUTHORIZED", "PARTIALLY_CAPTURED").contains(hold.holdStatus())) {
			throw new CoreBankException("Hold is not capturable in status: " + hold.holdStatus());
		}

		if (hold.remainingMinor() < command.amountMinor()) {
			throw new CoreBankException("Capture amount exceeds hold remaining amount");
		}

		UUID beneficiaryCustomerAccountId = command.beneficiaryCustomerAccountId() != null
				? command.beneficiaryCustomerAccountId()
				: hold.payeeAccountId();

		UUID journalId = ledgerCommandService.postJournal(
				new LedgerCommandService.PostJournalCommand(
						"PAYMENT_CAPTURE",
						"PAYMENT_ORDER",
						hold.paymentOrderId(),
						hold.currency(),
						null,
						command.actor(),
						command.correlationId(),
						List.of(
								new LedgerCommandService.PostingInstruction(
										command.debitLedgerAccountId(),
										hold.customerAccountId(),
										"D",
										command.amountMinor(),
										hold.currency(),
										true),
								new LedgerCommandService.PostingInstruction(
										command.creditLedgerAccountId(),
										beneficiaryCustomerAccountId,
										"C",
										command.amountMinor(),
										hold.currency(),
										true))));

		long remainingAfter = hold.remainingMinor() - command.amountMinor();
		boolean fullyCaptured = remainingAfter == 0;
		String nextHoldStatus = fullyCaptured ? "FULLY_CAPTURED" : "PARTIALLY_CAPTURED";
		String nextPaymentStatus = fullyCaptured ? "CAPTURED" : "PARTIALLY_CAPTURED";
		String holdEventType = fullyCaptured ? "FULLY_CAPTURED" : "PARTIALLY_CAPTURED";
		String paymentEventType = fullyCaptured ? "CAPTURED" : "PARTIALLY_CAPTURED";
		Timestamp capturedAt = Timestamp.from(Instant.now());

		jdbcTemplate.update(
				"""
				UPDATE funds_holds
				SET remaining_minor = ?,
				    status = ?,
				    captured_at = ?
				WHERE hold_id = ?
				""",
				remainingAfter,
				nextHoldStatus,
				capturedAt,
				hold.holdId());

		jdbcTemplate.update(
				"""
				UPDATE payment_orders
				SET status = ?,
				    updated_at = now()
				WHERE payment_order_id = ?
				""",
				nextPaymentStatus,
				hold.paymentOrderId());

		jdbcTemplate.update(
				"""
				INSERT INTO hold_events (
				    hold_id,
				    event_type,
				    amount_minor,
				    metadata_json
				) VALUES (?, ?, ?, ?::jsonb)
				""",
				hold.holdId(),
				holdEventType,
				command.amountMinor(),
				"{}");

		jdbcTemplate.update(
				"""
				INSERT INTO payment_events (
				    payment_order_id,
				    event_type,
				    amount_minor,
				    metadata_json
				) VALUES (?, ?, ?, ?::jsonb)
				""",
				hold.paymentOrderId(),
				paymentEventType,
				command.amountMinor(),
				"{}");

		return new CaptureResult(
				hold.paymentOrderId(),
				hold.holdId(),
				journalId,
				command.amountMinor(),
				remainingAfter,
				nextHoldStatus,
				nextPaymentStatus,
				hold.currency(),
				hold.externalOrderRef());
	}

	public VoidResult voidHold(VoidHoldCommand command) {
		HoldSnapshot hold = lockHold(command.holdId())
				.orElseThrow(() -> new CoreBankException("Funds hold not found: " + command.holdId()));

		if (!List.of("AUTHORIZED", "PARTIALLY_CAPTURED").contains(hold.holdStatus())) {
			throw new CoreBankException("Hold is not voidable in status: " + hold.holdStatus());
		}

		CustomerAccount lockedAccount = accountBalanceRepository.lockById(hold.customerAccountId())
				.orElseThrow(() -> new CoreBankException("Customer account not found: " + hold.customerAccountId()));

		long restoredAmount = hold.remainingMinor();
		long availableAfter = lockedAccount.getAvailableBalanceMinor() + restoredAmount;

		boolean updated = accountBalanceRepository.updateAvailableBalance(
				lockedAccount.getCustomerAccountId(),
				availableAfter,
				lockedAccount.getVersion());

		if (!updated) {
			throw new CoreBankException("Failed to restore available balance during void");
		}

		Timestamp voidedAt = Timestamp.from(Instant.now());

		jdbcTemplate.update(
				"""
				UPDATE funds_holds
				SET remaining_minor = 0,
				    status = 'VOIDED',
				    voided_at = ?
				WHERE hold_id = ?
				""",
				voidedAt,
				hold.holdId());

		jdbcTemplate.update(
				"""
				UPDATE payment_orders
				SET status = 'VOIDED',
				    updated_at = now()
				WHERE payment_order_id = ?
				""",
				hold.paymentOrderId());

		jdbcTemplate.update(
				"""
				INSERT INTO hold_events (
				    hold_id,
				    event_type,
				    amount_minor,
				    metadata_json
				) VALUES (?, 'VOIDED', ?, ?::jsonb)
				""",
				hold.holdId(),
				restoredAmount,
				"{}");

		jdbcTemplate.update(
				"""
				INSERT INTO payment_events (
				    payment_order_id,
				    event_type,
				    amount_minor,
				    metadata_json
				) VALUES (?, 'VOIDED', ?, ?::jsonb)
				""",
				hold.paymentOrderId(),
				restoredAmount,
				"{}");

		return new VoidResult(
				hold.paymentOrderId(),
				hold.holdId(),
				restoredAmount,
				lockedAccount.getAvailableBalanceMinor(),
				availableAfter,
				hold.currency(),
				"VOIDED",
				hold.externalOrderRef());
	}

	public RefundResult refund(RefundCommand command) {
		if (command.amountMinor() <= 0) {
			throw new CoreBankException("refund amount must be positive");
		}

		// Lock payment_order — serializes concurrent refunds on same order
		PaymentOrderSnapshot order = lockPaymentOrder(command.paymentOrderId())
				.orElseThrow(() -> new CoreBankException("payment_order not found: " + command.paymentOrderId()));

		if (!List.of("CAPTURED", "PARTIALLY_CAPTURED", "PARTIALLY_REFUNDED").contains(order.status())) {
			throw new CoreBankException("payment_order not in refundable state: " + order.status());
		}

		// Sum all captured events — robust across partial + multi-capture sequences
		Long capturedAmount = jdbcTemplate.queryForObject(
				"""
				SELECT COALESCE(SUM(amount_minor), 0)
				FROM payment_events
				WHERE payment_order_id = ?
				  AND event_type IN ('CAPTURED', 'PARTIALLY_CAPTURED')
				""",
				Long.class,
				command.paymentOrderId());
		if (capturedAmount == null) capturedAmount = 0L;

		long refundable = capturedAmount - order.refundedAmountMinor();
		if (command.amountMinor() > refundable) {
			throw new CoreBankException("refund amount exceeds refundable balance");
		}

		// Find the first capture journal — used as reversal_of_journal_id (metadata only)
		UUID captureJournalId = findFirstCaptureJournalId(command.paymentOrderId());

		// Load capture postings to derive ledger account IDs and customer account IDs
		List<CapturePosting> capturePostings = loadCapturePostings(captureJournalId);
		if (capturePostings.isEmpty()) {
			throw new CoreBankException("No capture postings found for payment order: " + command.paymentOrderId());
		}

		// Build reversal postings (flip D↔C, use refund amount)
		List<LedgerCommandService.PostingInstruction> refundPostings = new ArrayList<>();
		for (CapturePosting cp : capturePostings) {
			String reversedSide = "D".equals(cp.entrySide()) ? "C" : "D";
			refundPostings.add(new LedgerCommandService.PostingInstruction(
					cp.ledgerAccountId(),
					cp.customerAccountId(),
					reversedSide,
					command.amountMinor(),
					order.currency(),
					true));
		}

		// Post reversing journal — this also updates posted_balance_minor for all parties
		UUID refundJournalId = ledgerCommandService.postJournal(
				new LedgerCommandService.PostJournalCommand(
						"PAYMENT_REFUND",
						"PAYMENT_ORDER",
						command.paymentOrderId(),
						order.currency(),
						captureJournalId,
						command.actor(),
						command.correlationId(),
						refundPostings));

		// Restore payer's available_balance (reduced at authorize time, not restored at capture)
		jdbcTemplate.update(
				"""
				UPDATE customer_accounts
				SET available_balance_minor = available_balance_minor + ?,
				    version = version + 1,
				    updated_at = now()
				WHERE customer_account_id = ?
				""",
				command.amountMinor(),
				order.payerAccountId());

		long newRefundedAmount = order.refundedAmountMinor() + command.amountMinor();
		boolean fullyRefunded = newRefundedAmount >= capturedAmount;
		String nextStatus = fullyRefunded ? "REFUNDED" : "PARTIALLY_REFUNDED";

		jdbcTemplate.update(
				"""
				UPDATE payment_orders
				SET refunded_amount_minor = ?,
				    status = ?,
				    updated_at = now()
				WHERE payment_order_id = ?
				""",
				newRefundedAmount,
				nextStatus,
				command.paymentOrderId());

		String metadataJson = buildRefundMetadataJson(command.actor(), refundJournalId);
		jdbcTemplate.update(
				"""
				INSERT INTO payment_events (
				    payment_order_id,
				    event_type,
				    amount_minor,
				    metadata_json
				) VALUES (?, ?, ?, ?::jsonb)
				""",
				command.paymentOrderId(),
				nextStatus,
				command.amountMinor(),
				metadataJson);

		return new RefundResult(
				command.paymentOrderId(),
				refundJournalId,
				command.amountMinor(),
				newRefundedAmount,
				capturedAmount,
				nextStatus,
				order.externalOrderRef(),
				order.currency());
	}

	private Optional<HoldSnapshot> lockHold(UUID holdId) {
		return jdbcTemplate.query(
				"""
				SELECT fh.hold_id,
				       fh.payment_order_id,
				       fh.customer_account_id,
				       po.payee_account_id,
				       fh.amount_minor,
				       fh.remaining_minor,
				       fh.status AS hold_status,
				       po.status AS payment_status,
				       po.currency,
				       po.payment_type,
				       po.external_order_ref
				FROM funds_holds fh
				JOIN payment_orders po ON po.payment_order_id = fh.payment_order_id
				WHERE fh.hold_id = ?
				FOR UPDATE
				""",
				HOLD_SNAPSHOT_ROW_MAPPER,
				holdId)
			.stream()
			.findFirst();
	}

	private Optional<PaymentOrderSnapshot> lockPaymentOrder(UUID paymentOrderId) {
		return jdbcTemplate.query(
				"""
				SELECT payment_order_id, payer_account_id, payee_account_id,
				       amount_minor, currency, payment_type, status,
				       refunded_amount_minor, external_order_ref
				FROM payment_orders
				WHERE payment_order_id = ?
				FOR UPDATE
				""",
				(rs, rowNum) -> new PaymentOrderSnapshot(
						rs.getObject("payment_order_id", UUID.class),
						rs.getObject("payer_account_id", UUID.class),
						rs.getObject("payee_account_id", UUID.class),
						rs.getLong("amount_minor"),
						rs.getString("currency"),
						rs.getString("payment_type"),
						rs.getString("status"),
						rs.getLong("refunded_amount_minor"),
						rs.getString("external_order_ref")),
				paymentOrderId)
			.stream()
			.findFirst();
	}

	private UUID findFirstCaptureJournalId(UUID paymentOrderId) {
		List<UUID> ids = jdbcTemplate.query(
				"""
				SELECT journal_id
				FROM ledger_journals
				WHERE reference_type = 'PAYMENT_ORDER'
				  AND reference_id = ?
				  AND journal_type = 'PAYMENT_CAPTURE'
				ORDER BY created_at ASC
				LIMIT 1
				""",
				(rs, rowNum) -> rs.getObject("journal_id", UUID.class),
				paymentOrderId);
		if (ids.isEmpty()) {
			throw new CoreBankException("No capture journal found for payment order: " + paymentOrderId);
		}
		return ids.get(0);
	}

	private List<CapturePosting> loadCapturePostings(UUID captureJournalId) {
		return jdbcTemplate.query(
				"SELECT ledger_account_id, customer_account_id, entry_side FROM ledger_postings WHERE journal_id = ?",
				(rs, rowNum) -> new CapturePosting(
						rs.getObject("ledger_account_id", UUID.class),
						rs.getObject("customer_account_id", UUID.class),
						rs.getString("entry_side")),
				captureJournalId);
	}

	private String buildRefundMetadataJson(String actor, UUID refundJournalId) {
		String safeActor = actor == null ? "" : actor.replace("\\", "\\\\").replace("\"", "\\\"");
		return "{\"actor\":\"" + safeActor + "\",\"refundJournalId\":\"" + refundJournalId + "\"}";
	}

	// ------------------------------------------------------------------ records

	public record AuthorizeHoldCommand(
			UUID payerAccountId,
			UUID payeeAccountId,
			long amountMinor,
			String currency,
			String paymentType,
			String description,
			String externalOrderRef) {
	}

	public record AuthorizationResult(
			UUID paymentOrderId,
			UUID holdId,
			UUID payerAccountId,
			long postedBalanceMinor,
			long availableBalanceBeforeMinor,
			long availableBalanceAfterMinor,
			long holdAmountMinor,
			String currency,
			String status,
			String externalOrderRef) {
	}

	public record CaptureHoldCommand(
			UUID holdId,
			long amountMinor,
			UUID debitLedgerAccountId,
			UUID creditLedgerAccountId,
			UUID beneficiaryCustomerAccountId,
			String actor,
			UUID correlationId) {
	}

	public record CaptureResult(
			UUID paymentOrderId,
			UUID holdId,
			UUID journalId,
			long capturedAmountMinor,
			long remainingAmountMinor,
			String holdStatus,
			String paymentStatus,
			String currency,
			String externalOrderRef) {
	}

	public record VoidHoldCommand(UUID holdId) {
	}

	public record VoidResult(
			UUID paymentOrderId,
			UUID holdId,
			long restoredAmountMinor,
			long availableBalanceBeforeMinor,
			long availableBalanceAfterMinor,
			String currency,
			String status,
			String externalOrderRef) {
	}

	public record RefundCommand(
			UUID paymentOrderId,
			long amountMinor,
			String actor,
			UUID correlationId,
			String description) {
	}

	public record RefundResult(
			UUID paymentOrderId,
			UUID refundJournalId,
			long refundedAmountMinor,
			long cumulativeRefundedMinor,
			long capturedAmountMinor,
			String paymentStatus,
			String externalOrderRef,
			String currency) {
	}

	private record HoldSnapshot(
			UUID holdId,
			UUID paymentOrderId,
			UUID customerAccountId,
			UUID payeeAccountId,
			long amountMinor,
			long remainingMinor,
			String holdStatus,
			String paymentStatus,
			String currency,
			String paymentType,
			String externalOrderRef) {
	}

	private record PaymentOrderSnapshot(
			UUID paymentOrderId,
			UUID payerAccountId,
			UUID payeeAccountId,
			long amountMinor,
			String currency,
			String paymentType,
			String status,
			long refundedAmountMinor,
			String externalOrderRef) {
	}

	private record CapturePosting(
			UUID ledgerAccountId,
			UUID customerAccountId,
			String entrySide) {
	}
}
