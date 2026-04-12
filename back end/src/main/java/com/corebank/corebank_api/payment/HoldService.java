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
							rs.getString("payment_type"));
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
				    description
				) VALUES (?, ?, ?, ?, ?, ?, 'AUTHORIZED', ?)
				""",
				paymentOrderId,
				command.payerAccountId(),
				command.payeeAccountId(),
				command.amountMinor(),
				command.currency(),
				command.paymentType(),
				command.description());

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
				"AUTHORIZED");
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
										true), // Update posted balance for captures
								new LedgerCommandService.PostingInstruction(
										command.creditLedgerAccountId(),
										beneficiaryCustomerAccountId,
										"C",
										command.amountMinor(),
										hold.currency(),
										true)))); // Update posted balance for captures

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
				hold.currency());
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
				"VOIDED");
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
				       po.payment_type
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

	public record AuthorizeHoldCommand(
			UUID payerAccountId,
			UUID payeeAccountId,
			long amountMinor,
			String currency,
			String paymentType,
			String description) {
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
			String status) {
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
			String currency) {
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
			String status) {
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
			String paymentType) {
	}
}