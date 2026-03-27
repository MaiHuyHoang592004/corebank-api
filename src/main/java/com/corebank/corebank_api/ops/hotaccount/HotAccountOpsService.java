package com.corebank.corebank_api.ops.hotaccount;

import com.corebank.corebank_api.common.CoreBankException;
import com.corebank.corebank_api.ops.audit.AuditService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class HotAccountOpsService {

	private static final Set<String> ALLOWED_STRATEGIES = Set.of("HASH", "ROUND_ROBIN", "RANDOM");

	private final JdbcTemplate jdbcTemplate;
	private final AuditService auditService;
	private final ObjectMapper objectMapper;

	private final RowMapper<HotAccountSlotView> slotViewMapper = new RowMapper<>() {
		@Override
		public HotAccountSlotView mapRow(ResultSet rs, int rowNum) throws SQLException {
			return new HotAccountSlotView(
					rs.getInt("slot_no"),
					rs.getLong("posted_balance_minor"),
					rs.getLong("available_balance_minor"),
					rs.getTimestamp("updated_at").toInstant());
		}
	};

	public HotAccountOpsService(
			JdbcTemplate jdbcTemplate,
			AuditService auditService,
			ObjectMapper objectMapper) {
		this.jdbcTemplate = jdbcTemplate;
		this.auditService = auditService;
		this.objectMapper = objectMapper.copy().findAndRegisterModules();
	}

	@Transactional
	public HotAccountProfileView upsertProfile(UpsertHotAccountProfileCommand command, String actor) {
		UUID ledgerAccountId = requireLedgerAccountId(command.ledgerAccountId());
		int slotCount = requireSlotCount(command.slotCount());
		String strategy = resolveStrategy(command.selectionStrategy());
		boolean active = command.isActive() == null || command.isActive();
		String safeActor = safeActor(actor);

		ensureLedgerAccountExists(ledgerAccountId);

		Integer existingMaxSlotNo = jdbcTemplate.queryForObject(
				"""
				SELECT MAX(slot_no)
				FROM ledger_account_balance_slots
				WHERE ledger_account_id = ?
				""",
				Integer.class,
				ledgerAccountId);
		if (existingMaxSlotNo != null && slotCount < existingMaxSlotNo + 1) {
			throw new CoreBankException(
					"Cannot reduce slotCount below existing slot count: " + (existingMaxSlotNo + 1));
		}

		jdbcTemplate.update(
				"""
				INSERT INTO hot_account_profiles (
				    ledger_account_id,
				    slot_count,
				    selection_strategy,
				    is_active
				) VALUES (?, ?, ?, ?)
				ON CONFLICT (ledger_account_id)
				DO UPDATE SET
				    slot_count = EXCLUDED.slot_count,
				    selection_strategy = EXCLUDED.selection_strategy,
				    is_active = EXCLUDED.is_active
				""",
				ledgerAccountId,
				slotCount,
				strategy,
				active);

		for (int slotNo = 0; slotNo < slotCount; slotNo++) {
			jdbcTemplate.update(
					"""
					INSERT INTO ledger_account_balance_slots (
					    ledger_account_id,
					    slot_no,
					    posted_balance_minor,
					    available_balance_minor,
					    updated_at
					) VALUES (?, ?, 0, 0, now())
					ON CONFLICT (ledger_account_id, slot_no) DO NOTHING
					""",
					ledgerAccountId,
					slotNo);
		}

		HotAccountProfileView view = getHotAccount(ledgerAccountId);
		appendAudit(view, safeActor);
		return view;
	}

	@Transactional(readOnly = true)
	public HotAccountProfileView getHotAccount(UUID ledgerAccountId) {
		UUID safeLedgerAccountId = requireLedgerAccountId(ledgerAccountId);
		ensureLedgerAccountExists(safeLedgerAccountId);

		List<HotAccountProfileRow> rows = jdbcTemplate.query(
				"""
				SELECT ledger_account_id,
				       slot_count,
				       selection_strategy,
				       is_active
				FROM hot_account_profiles
				WHERE ledger_account_id = ?
				""",
				(rs, rowNum) -> new HotAccountProfileRow(
						rs.getObject("ledger_account_id", UUID.class),
						rs.getInt("slot_count"),
						rs.getString("selection_strategy"),
						rs.getBoolean("is_active")),
				safeLedgerAccountId);
		if (rows.isEmpty()) {
			throw new CoreBankException("Hot account profile not found for ledger account: " + safeLedgerAccountId);
		}

		List<HotAccountSlotView> slots = jdbcTemplate.query(
				"""
				SELECT slot_no,
				       posted_balance_minor,
				       available_balance_minor,
				       updated_at
				FROM ledger_account_balance_slots
				WHERE ledger_account_id = ?
				ORDER BY slot_no ASC
				""",
				slotViewMapper,
				safeLedgerAccountId);

		long totalPosted = 0L;
		long totalAvailable = 0L;
		for (HotAccountSlotView slot : slots) {
			totalPosted += slot.postedBalanceMinor();
			totalAvailable += slot.availableBalanceMinor();
		}

		HotAccountProfileRow profile = rows.get(0);
		return new HotAccountProfileView(
				profile.ledgerAccountId(),
				profile.slotCount(),
				profile.selectionStrategy(),
				profile.isActive(),
				totalPosted,
				totalAvailable,
				slots,
				"Hot-account slot totals are runtime-updated when profile is active. customer_accounts semantics remain authoritative.");
	}

	private void ensureLedgerAccountExists(UUID ledgerAccountId) {
		Integer count = jdbcTemplate.queryForObject(
				"SELECT COUNT(*) FROM ledger_accounts WHERE ledger_account_id = ?",
				Integer.class,
				ledgerAccountId);
		if (count == null || count == 0) {
			throw new CoreBankException("Ledger account not found: " + ledgerAccountId);
		}
	}

	private UUID requireLedgerAccountId(UUID ledgerAccountId) {
		if (ledgerAccountId == null) {
			throw new CoreBankException("ledgerAccountId is required");
		}
		return ledgerAccountId;
	}

	private int requireSlotCount(Integer slotCount) {
		if (slotCount == null) {
			throw new CoreBankException("slotCount is required");
		}
		if (slotCount <= 1) {
			throw new CoreBankException("slotCount must be greater than 1");
		}
		return slotCount;
	}

	private String resolveStrategy(String strategy) {
		if (strategy == null || strategy.trim().isEmpty()) {
			return "HASH";
		}

		String normalized = strategy.trim().toUpperCase(Locale.ROOT);
		if (!ALLOWED_STRATEGIES.contains(normalized)) {
			throw new CoreBankException("selectionStrategy must be one of HASH, ROUND_ROBIN, RANDOM");
		}
		return normalized;
	}

	private void appendAudit(HotAccountProfileView profile, String actor) {
		Map<String, Object> after = new LinkedHashMap<>();
		after.put("ledgerAccountId", profile.ledgerAccountId());
		after.put("slotCount", profile.slotCount());
		after.put("selectionStrategy", profile.selectionStrategy());
		after.put("isActive", profile.isActive());
		after.put("totalPostedBalanceMinor", profile.totalPostedBalanceMinor());
		after.put("totalAvailableBalanceMinor", profile.totalAvailableBalanceMinor());
		after.put("slotRowCount", profile.slots().size());
		after.put("note", profile.note());

		auditService.appendEvent(new AuditService.AuditCommand(
				actor,
				"HOT_ACCOUNT_PROFILE_UPSERTED",
				"HOT_ACCOUNT_PROFILE",
				profile.ledgerAccountId().toString(),
				UUID.nameUUIDFromBytes(
						("hot-account-profile:" + profile.ledgerAccountId()).getBytes(StandardCharsets.UTF_8)),
				null,
				null,
				"hot-account-ops-service",
				null,
				toJson(after)));
	}

	private String safeActor(String actor) {
		if (actor == null || actor.trim().isEmpty()) {
			return "system";
		}
		return actor.trim();
	}

	private String toJson(Object payload) {
		try {
			return objectMapper.writeValueAsString(payload);
		} catch (JsonProcessingException ex) {
			throw new CoreBankException("Unable to serialize hot-account profile payload", ex);
		}
	}

	public record UpsertHotAccountProfileCommand(
			UUID ledgerAccountId,
			Integer slotCount,
			String selectionStrategy,
			Boolean isActive) {
	}

	public record HotAccountProfileView(
			UUID ledgerAccountId,
			int slotCount,
			String selectionStrategy,
			boolean isActive,
			long totalPostedBalanceMinor,
			long totalAvailableBalanceMinor,
			List<HotAccountSlotView> slots,
			String note) {
	}

	public record HotAccountSlotView(
			int slotNo,
			long postedBalanceMinor,
			long availableBalanceMinor,
			Instant updatedAt) {
	}

	private record HotAccountProfileRow(
			UUID ledgerAccountId,
			int slotCount,
			String selectionStrategy,
			boolean isActive) {
	}
}
