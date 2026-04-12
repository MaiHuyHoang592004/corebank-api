package com.corebank.corebank_api.demo.api;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Read-only queries for demo payment holds.
 * Always scoped to demo accounts.
 */
@Service
public class DemoPaymentFacadeService {

	private static final List<String> ACTIVE_HOLD_STATUSES = List.of(
			"AUTHORIZED", "PARTIALLY_CAPTURED");

	private final JdbcTemplate jdbcTemplate;

	public DemoPaymentFacadeService(JdbcTemplate jdbcTemplate) {
		this.jdbcTemplate = jdbcTemplate;
	}

	// -------------------------------------------------------------------------
	// DTOs
	// -------------------------------------------------------------------------

	public record DemoHoldDto(
			UUID holdId,
			UUID paymentOrderId,
			UUID payerAccountId,
			UUID payeeAccountId,
			long amountMinor,
			long remainingMinor,
			String holdStatus,
			String paymentStatus,
			String currency,
			String paymentType,
			Instant createdAt) {
	}

	public record DemoHoldPage(
			int page,
			int size,
			long totalItems,
			List<DemoHoldDto> items) {
	}

	// -------------------------------------------------------------------------
	// Queries
	// -------------------------------------------------------------------------

	public DemoHoldPage listActiveHolds(UUID accountId, int page, int size) {
		int safePage = Math.max(page, 0);
		int safeSize = Math.min(Math.max(size, 1), 100);

		String inClause = String.join(",",
				ACTIVE_HOLD_STATUSES.stream().map(s -> "'" + s + "'").toList());

		long totalItems = jdbcTemplate.queryForObject(
				"""
				SELECT COUNT(*)
				FROM funds_holds fh
				JOIN payment_orders po ON fh.payment_order_id = po.payment_order_id
				WHERE fh.customer_account_id = ?
				  AND fh.status IN (%s)
				""".formatted(inClause),
				Long.class,
				accountId);

		List<DemoHoldDto> items = jdbcTemplate.query(
				"""
				SELECT fh.hold_id,
				       fh.payment_order_id,
				       fh.customer_account_id     AS payer_account_id,
				       po.payee_account_id,
				       po.amount_minor            AS amount_minor,
				       fh.remaining_minor,
				       fh.status                  AS hold_status,
				       po.status                  AS payment_status,
				       po.currency,
				       po.payment_type,
				       fh.created_at
				FROM funds_holds fh
				JOIN payment_orders po ON fh.payment_order_id = po.payment_order_id
				WHERE fh.customer_account_id = ?
				  AND fh.status IN (%s)
				ORDER BY fh.created_at DESC
				LIMIT ? OFFSET ?
				""".formatted(inClause),
				(rs, rowNum) -> new DemoHoldDto(
						UUID.fromString(rs.getString("hold_id")),
						UUID.fromString(rs.getString("payment_order_id")),
						UUID.fromString(rs.getString("payer_account_id")),
						rs.getString("payee_account_id") != null
								? UUID.fromString(rs.getString("payee_account_id"))
								: null,
						rs.getLong("amount_minor"),
						rs.getLong("remaining_minor"),
						rs.getString("hold_status"),
						rs.getString("payment_status"),
						rs.getString("currency"),
						rs.getString("payment_type"),
						rs.getTimestamp("created_at") != null
								? rs.getTimestamp("created_at").toInstant()
								: null),
				accountId,
				safeSize,
				(long) safePage * safeSize);

		return new DemoHoldPage(safePage, safeSize, totalItems, items);
	}

	public DemoHoldDto getHoldById(UUID holdId) {
		List<DemoHoldDto> items = jdbcTemplate.query(
				"""
				SELECT fh.hold_id,
				       fh.payment_order_id,
				       fh.customer_account_id     AS payer_account_id,
				       po.payee_account_id,
				       po.amount_minor            AS amount_minor,
				       fh.remaining_minor,
				       fh.status                  AS hold_status,
				       po.status                  AS payment_status,
				       po.currency,
				       po.payment_type,
				       fh.created_at
				FROM funds_holds fh
				JOIN payment_orders po ON fh.payment_order_id = po.payment_order_id
				WHERE fh.hold_id = ?
				""",
				(rs, rowNum) -> new DemoHoldDto(
						UUID.fromString(rs.getString("hold_id")),
						UUID.fromString(rs.getString("payment_order_id")),
						UUID.fromString(rs.getString("payer_account_id")),
						rs.getString("payee_account_id") != null
								? UUID.fromString(rs.getString("payee_account_id"))
								: null,
						rs.getLong("amount_minor"),
						rs.getLong("remaining_minor"),
						rs.getString("hold_status"),
						rs.getString("payment_status"),
						rs.getString("currency"),
						rs.getString("payment_type"),
						rs.getTimestamp("created_at") != null
								? rs.getTimestamp("created_at").toInstant()
								: null),
				holdId);

		if (items.isEmpty()) {
			throw new IllegalArgumentException("Hold not found: " + holdId);
		}
		return items.get(0);
	}
}
