package com.corebank.corebank_api.ledger;

import com.corebank.corebank_api.common.CoreBankException;
import com.corebank.corebank_api.ops.audit.AuditService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class HotAccountSlotRuntimeService {

	private static final Logger log = LoggerFactory.getLogger(HotAccountSlotRuntimeService.class);

	private final JdbcTemplate jdbcTemplate;
	private final AuditService auditService;
	private final ObjectMapper objectMapper;

	public HotAccountSlotRuntimeService(
			JdbcTemplate jdbcTemplate,
			AuditService auditService,
			ObjectMapper objectMapper) {
		this.jdbcTemplate = jdbcTemplate;
		this.auditService = auditService;
		this.objectMapper = objectMapper;
	}

	public Map<UUID, HotAccountContext> lockActiveContextsInDeterministicOrder(Collection<UUID> ledgerAccountIds) {
		if (ledgerAccountIds == null || ledgerAccountIds.isEmpty()) {
			return Map.of();
		}

		List<UUID> sortedAccountIds = ledgerAccountIds.stream()
				.filter(Objects::nonNull)
				.distinct()
				.sorted()
				.toList();

		Map<UUID, HotAccountContext> contexts = new LinkedHashMap<>();
		for (UUID ledgerAccountId : sortedAccountIds) {
			HotAccountProfile profile = findProfile(ledgerAccountId);
			if (profile == null || !profile.active()) {
				continue;
			}

			List<Integer> lockedSlots = lockSlots(ledgerAccountId);
			if (lockedSlots.size() < profile.slotCount()) {
				throw new CoreBankException("Hot account slots are missing for ledger account: " + ledgerAccountId);
			}

			contexts.put(ledgerAccountId, new HotAccountContext(
					ledgerAccountId,
					profile.slotCount(),
					profile.selectionStrategy()));
		}

		return contexts;
	}

	public SlottingDecision applySlotDelta(
			HotAccountContext context,
			UUID referenceId,
			String entrySide,
			long amountMinor) {
		if (context == null) {
			throw new CoreBankException("Hot account context is required");
		}
		if (referenceId == null) {
			throw new CoreBankException("referenceId is required for hot-account slot selection");
		}
		if (amountMinor <= 0) {
			throw new CoreBankException("amountMinor must be positive for hot-account slot selection");
		}

		int slotNo = Math.floorMod(Objects.hash(
				referenceId,
				context.ledgerAccountId(),
				entrySide,
				amountMinor), context.slotCount());
		long deltaMinor = "C".equals(entrySide) ? amountMinor : -amountMinor;

		String configuredStrategy = normalizeStrategy(context.selectionStrategy());
		String selectionStrategyApplied = "HASH";
		if (!"HASH".equals(configuredStrategy)) {
			selectionStrategyApplied = "HASH_FALLBACK";
			log.warn(
					"Hot-account strategy fallback configured={} applied={} ledgerAccountId={} slotNo={}",
					configuredStrategy,
					selectionStrategyApplied,
					context.ledgerAccountId(),
					slotNo);
		}

		int updatedRows = jdbcTemplate.update(
				"""
				UPDATE ledger_account_balance_slots
				SET posted_balance_minor = posted_balance_minor + ?,
				    available_balance_minor = available_balance_minor + ?,
				    updated_at = now()
				WHERE ledger_account_id = ?
				  AND slot_no = ?
				""",
				deltaMinor,
				deltaMinor,
				context.ledgerAccountId(),
				slotNo);
		if (updatedRows != 1) {
			throw new CoreBankException(
					"Unable to update hot-account slot for ledger account: " + context.ledgerAccountId());
		}

		return new SlottingDecision(
				context.ledgerAccountId(),
				slotNo,
				configuredStrategy,
				selectionStrategyApplied,
				deltaMinor);
	}

	public void appendSlottingAudit(
			UUID journalId,
			LedgerCommandService.PostJournalCommand command,
			List<SlottingDecision> decisions) {
		if (journalId == null || decisions == null || decisions.isEmpty()) {
			return;
		}

		Map<String, Object> after = new LinkedHashMap<>();
		after.put("journalId", journalId);
		after.put("referenceType", command.referenceType());
		after.put("referenceId", command.referenceId());
		after.put("currency", command.currency());
		after.put("selectionCount", decisions.size());
		after.put("selectionDetails", decisions);
		after.put("appliedAt", Instant.now());

		auditService.appendEvent(new AuditService.AuditCommand(
				command.createdByActor() == null || command.createdByActor().isBlank()
						? "system"
						: command.createdByActor().trim(),
				"HOT_ACCOUNT_SLOTTING_APPLIED",
				"LEDGER_JOURNAL",
				journalId.toString(),
				command.correlationId() == null
						? UUID.nameUUIDFromBytes(("hot-slotting:" + journalId).getBytes(StandardCharsets.UTF_8))
						: command.correlationId(),
				null,
				null,
				"hot-account-runtime",
				null,
				toJson(after)));
	}

	private HotAccountProfile findProfile(UUID ledgerAccountId) {
		List<HotAccountProfile> profiles = jdbcTemplate.query(
				"""
				SELECT slot_count,
				       selection_strategy,
				       is_active
				FROM hot_account_profiles
				WHERE ledger_account_id = ?
				""",
				(rs, rowNum) -> new HotAccountProfile(
						rs.getInt("slot_count"),
						rs.getString("selection_strategy"),
						rs.getBoolean("is_active")),
				ledgerAccountId);
		return profiles.isEmpty() ? null : profiles.get(0);
	}

	private List<Integer> lockSlots(UUID ledgerAccountId) {
		return jdbcTemplate.query(
				"""
				SELECT slot_no
				FROM ledger_account_balance_slots
				WHERE ledger_account_id = ?
				ORDER BY slot_no ASC
				FOR UPDATE
				""",
				(rs, rowNum) -> rs.getInt("slot_no"),
				ledgerAccountId);
	}

	private String normalizeStrategy(String strategy) {
		if (strategy == null || strategy.isBlank()) {
			return "HASH";
		}
		return strategy.trim().toUpperCase(Locale.ROOT);
	}

	private String toJson(Object payload) {
		try {
			return objectMapper.writeValueAsString(payload);
		} catch (JsonProcessingException ex) {
			throw new CoreBankException("Unable to serialize hot-account slotting audit payload", ex);
		}
	}

	public record HotAccountContext(
			UUID ledgerAccountId,
			int slotCount,
			String selectionStrategy) {
	}

	public record SlottingDecision(
			UUID ledgerAccountId,
			int slotNo,
			String configuredStrategy,
			String selectionStrategyApplied,
			long deltaMinor) {
	}

	private record HotAccountProfile(
			int slotCount,
			String selectionStrategy,
			boolean active) {
	}
}
