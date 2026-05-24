package com.corebank.corebank_api.payment;

import com.corebank.corebank_api.common.CoreBankException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class PaymentQueryService {

	private final JdbcTemplate jdbcTemplate;

	public PaymentQueryService(JdbcTemplate jdbcTemplate) {
		this.jdbcTemplate = jdbcTemplate;
	}

	public PaymentOrderView getPaymentOrder(UUID paymentOrderId) {
		List<PaymentOrderView> rows = jdbcTemplate.query(
				"""
				SELECT po.payment_order_id,
				       po.external_order_ref,
				       po.payer_account_id,
				       po.payee_account_id,
				       po.amount_minor,
				       po.currency,
				       po.payment_type,
				       po.status,
				       po.description,
				       po.refunded_amount_minor,
				       po.created_at,
				       po.updated_at,
				       fh.hold_id,
				       fh.status         AS hold_status,
				       fh.amount_minor   AS hold_amount_minor,
				       fh.remaining_minor,
				       fh.authorized_at,
				       fh.captured_at,
				       fh.voided_at
				FROM payment_orders po
				LEFT JOIN funds_holds fh ON fh.payment_order_id = po.payment_order_id
				WHERE po.payment_order_id = ?
				""",
				this::mapPaymentOrderRow,
				paymentOrderId);

		if (rows.isEmpty()) {
			throw new CoreBankException("payment_order not found: " + paymentOrderId);
		}
		PaymentOrderView view = rows.get(0);

		List<PaymentEventView> events = jdbcTemplate.query(
				"""
				SELECT event_type, amount_minor, created_at
				FROM payment_events
				WHERE payment_order_id = ?
				ORDER BY created_at DESC
				LIMIT 20
				""",
				this::mapEventRow,
				paymentOrderId);

		return new PaymentOrderView(
				view.paymentOrderId(), view.externalOrderRef(),
				view.payerAccountId(), view.payeeAccountId(),
				view.amountMinor(), view.currency(), view.paymentType(),
				view.status(), view.description(),
				view.refundedAmountMinor(),
				view.hold(), events,
				view.createdAt(), view.updatedAt());
	}

	public PaymentOrderListView listPaymentOrders(ListPaymentOrdersRequest request) {
		if (request.externalOrderRef() == null
				&& request.payerAccountId() == null
				&& request.payeeAccountId() == null) {
			throw new CoreBankException(
					"At least one of externalOrderRef, payerAccountId, or payeeAccountId is required");
		}

		int page = request.page() < 0 ? 0 : request.page();
		int size = request.size() <= 0 ? 20 : Math.min(request.size(), 100);
		int offset = page * size;

		StringBuilder where = new StringBuilder("WHERE 1=1");
		List<Object> params = new ArrayList<>();

		if (request.externalOrderRef() != null) {
			where.append(" AND po.external_order_ref = ?");
			params.add(request.externalOrderRef());
		}
		if (request.payerAccountId() != null) {
			where.append(" AND po.payer_account_id = ?");
			params.add(request.payerAccountId());
		}
		if (request.payeeAccountId() != null) {
			where.append(" AND po.payee_account_id = ?");
			params.add(request.payeeAccountId());
		}
		if (request.status() != null) {
			where.append(" AND po.status = ?");
			params.add(request.status());
		}
		if (request.createdFrom() != null) {
			where.append(" AND po.created_at >= ?");
			params.add(java.sql.Timestamp.from(request.createdFrom()));
		}
		if (request.createdTo() != null) {
			where.append(" AND po.created_at <= ?");
			params.add(java.sql.Timestamp.from(request.createdTo()));
		}

		String countSql = "SELECT COUNT(*) FROM payment_orders po " + where;
		Long total = jdbcTemplate.queryForObject(countSql, Long.class, params.toArray());
		if (total == null) total = 0L;

		String querySql =
				"""
				SELECT po.payment_order_id,
				       po.external_order_ref,
				       po.payer_account_id,
				       po.payee_account_id,
				       po.amount_minor,
				       po.currency,
				       po.payment_type,
				       po.status,
				       po.description,
				       po.refunded_amount_minor,
				       po.created_at,
				       po.updated_at,
				       fh.hold_id,
				       fh.status         AS hold_status,
				       fh.amount_minor   AS hold_amount_minor,
				       fh.remaining_minor,
				       fh.authorized_at,
				       fh.captured_at,
				       fh.voided_at
				FROM payment_orders po
				LEFT JOIN funds_holds fh ON fh.payment_order_id = po.payment_order_id
				"""
				+ where
				+ " ORDER BY po.created_at DESC LIMIT ? OFFSET ?";

		List<Object> pageParams = new ArrayList<>(params);
		pageParams.add(size);
		pageParams.add(offset);

		List<PaymentOrderView> items = jdbcTemplate.query(
				querySql,
				this::mapPaymentOrderRow,
				pageParams.toArray());

		int totalPages = (int) Math.ceil((double) total / size);
		return new PaymentOrderListView(items, page, size, total, totalPages);
	}

	private PaymentOrderView mapPaymentOrderRow(ResultSet rs, int rowNum) throws SQLException {
		HoldView holdView = null;
		UUID holdId = rs.getObject("hold_id", UUID.class);
		if (holdId != null) {
			holdView = new HoldView(
					holdId,
					rs.getString("hold_status"),
					rs.getLong("hold_amount_minor"),
					rs.getLong("remaining_minor"),
					toInstant(rs, "authorized_at"),
					toInstant(rs, "captured_at"),
					toInstant(rs, "voided_at"));
		}
		return new PaymentOrderView(
				rs.getObject("payment_order_id", UUID.class),
				rs.getString("external_order_ref"),
				rs.getObject("payer_account_id", UUID.class),
				rs.getObject("payee_account_id", UUID.class),
				rs.getLong("amount_minor"),
				rs.getString("currency"),
				rs.getString("payment_type"),
				rs.getString("status"),
				rs.getString("description"),
				rs.getLong("refunded_amount_minor"),
				holdView,
				List.of(),
				toInstant(rs, "created_at"),
				toInstant(rs, "updated_at"));
	}

	private PaymentEventView mapEventRow(ResultSet rs, int rowNum) throws SQLException {
		return new PaymentEventView(
				rs.getString("event_type"),
				rs.getLong("amount_minor"),
				toInstant(rs, "created_at"));
	}

	private Instant toInstant(ResultSet rs, String column) throws SQLException {
		java.sql.Timestamp ts = rs.getTimestamp(column);
		return ts == null ? null : ts.toInstant();
	}

	// ------------------------------------------------------------------ view records

	public record PaymentOrderView(
			UUID paymentOrderId,
			String externalOrderRef,
			UUID payerAccountId,
			UUID payeeAccountId,
			long amountMinor,
			String currency,
			String paymentType,
			String status,
			String description,
			long refundedAmountMinor,
			HoldView hold,
			List<PaymentEventView> events,
			Instant createdAt,
			Instant updatedAt) {
	}

	public record HoldView(
			UUID holdId,
			String status,
			long amountMinor,
			long remainingMinor,
			Instant authorizedAt,
			Instant capturedAt,
			Instant voidedAt) {
	}

	public record PaymentEventView(
			String eventType,
			long amountMinor,
			Instant createdAt) {
	}

	public record PaymentOrderListView(
			List<PaymentOrderView> items,
			int page,
			int size,
			long totalElements,
			int totalPages) {
	}

	public record ListPaymentOrdersRequest(
			String externalOrderRef,
			UUID payerAccountId,
			UUID payeeAccountId,
			String status,
			Instant createdFrom,
			Instant createdTo,
			int page,
			int size) {
	}
}
