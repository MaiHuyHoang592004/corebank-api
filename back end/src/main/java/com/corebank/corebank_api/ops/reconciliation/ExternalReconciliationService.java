package com.corebank.corebank_api.ops.reconciliation;

import com.corebank.corebank_api.common.CoreBankException;
import com.corebank.corebank_api.ops.audit.AuditService;
import com.corebank.corebank_api.ops.batch.BatchRunService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.math.BigDecimal;
import java.sql.Date;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
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
public class ExternalReconciliationService {

	private static final int DEFAULT_PROCESSING_LIMIT = 2000;
	private static final int MAX_PROCESSING_LIMIT = 5000;
	private static final int MAX_INPUT_ENTRIES = 5000;
	private static final int DEFAULT_REPORTING_LIMIT = 50;
	private static final int MAX_REPORTING_LIMIT = 200;

	private static final String BATCH_TYPE = "EXTERNAL_RECONCILIATION";
	private static final String EXTERNAL_BREAK_SEVERITY_HIGH = "HIGH";
	private static final String EXTERNAL_BREAK_SEVERITY_CRITICAL = "CRITICAL";

	private static final Set<String> EXTERNAL_BREAK_TYPES = Set.of(
			"ORPHAN_EXTERNAL",
			"MISSING_EXTERNAL",
			"AMOUNT_MISMATCH",
			"STATUS_MISMATCH",
			"AMBIGUOUS_MATCH",
			"DUPLICATE_EXTERNAL",
			"DUPLICATE_INTERNAL");

	private final JdbcTemplate jdbcTemplate;
	private final BatchRunService batchRunService;
	private final AuditService auditService;
	private final ObjectMapper objectMapper;

	private final RowMapper<ExternalReconciliationBreakView> breakViewMapper = new RowMapper<>() {
		@Override
		public ExternalReconciliationBreakView mapRow(ResultSet rs, int rowNum) throws SQLException {
			String runIdText = rs.getString("run_id_text");
			Long runId = null;
			if (runIdText != null && !runIdText.isBlank()) {
				try {
					runId = Long.parseLong(runIdText);
				} catch (NumberFormatException ignored) {
					runId = null;
				}
			}

			return new ExternalReconciliationBreakView(
					rs.getObject("reconciliation_break_id", UUID.class),
					runId,
					rs.getString("statement_ref"),
					rs.getString("break_type"),
					rs.getString("reference_type"),
					rs.getString("reference_id"),
					rs.getString("severity"),
					rs.getString("status"),
					readPayloadMap(rs.getString("details_json")),
					readInstant(rs.getTimestamp("opened_at")),
					readInstant(rs.getTimestamp("resolved_at")));
		}
	};

	public ExternalReconciliationService(
			JdbcTemplate jdbcTemplate,
			BatchRunService batchRunService,
			AuditService auditService,
			ObjectMapper objectMapper) {
		this.jdbcTemplate = jdbcTemplate;
		this.batchRunService = batchRunService;
		this.auditService = auditService;
		this.objectMapper = objectMapper.copy().findAndRegisterModules();
	}

	@Transactional
	public ExternalReconciliationRunResult run(ExternalReconciliationRunCommand command) {
		ValidatedCommand validated = validateAndNormalize(command);
		long runId = batchRunService.startBatch(
				buildBatchName(validated.statementDate()),
				BATCH_TYPE,
				toJson(Map.of(
						"statementRef", validated.statementRef(),
						"provider", validated.provider(),
						"statementDate", validated.statementDate(),
						"processingLimit", validated.processingLimit(),
						"dryRun", validated.dryRun(),
						"requestedCount", validated.entries().size())));
		UUID runCorrelationId = runCorrelationId(runId);

		try {
			MatchingPreparation preparation = prepareEntriesForMatching(
					validated.entries(),
					validated.processingLimit());
			Map<MatchingKey, List<InternalEntry>> internalByKey = loadInternalEntriesByKey(
					validated.statementDate(),
					preparation.processedKeys());
			List<BreakDecision> breakDecisions = evaluateBreaks(
					runId,
					validated.statementRef(),
					validated.statementDate(),
					preparation,
					internalByKey);

			Integer statementVersion = null;
			UUID statementId = null;
			if (!validated.dryRun()) {
				statementId = persistStatementVersion(validated, runId);
				statementVersion = loadLatestVersionNo(validated.statementRef());
				persistEntries(statementId, validated.entries());
				persistBreaks(runCorrelationId, breakDecisions);
			}

			ExternalReconciliationRunResult result = summarizeResult(
					runId,
					statementId,
					statementVersion,
					validated,
					preparation,
					breakDecisions);

			Map<String, Object> summary = new LinkedHashMap<>();
			summary.put("runId", result.runId());
			summary.put("statementRef", result.statementRef());
			summary.put("statementVersion", result.statementVersion());
			summary.put("statementDate", result.statementDate());
			summary.put("dryRun", result.dryRun());
			summary.put("requestedCount", result.requestedCount());
			summary.put("processedCount", result.processedCount());
			summary.put("matchedCount", result.matchedCount());
			summary.put("breakCount", result.breakCount());
			summary.put("duplicateExternalCount", result.duplicateExternalCount());
			summary.put("duplicateInternalCount", result.duplicateInternalCount());
			summary.put("ambiguousCount", result.ambiguousCount());
			summary.put("orphanExternalCount", result.orphanExternalCount());
			summary.put("missingExternalCount", result.missingExternalCount());
			summary.put("amountMismatchCount", result.amountMismatchCount());
			summary.put("statusMismatchCount", result.statusMismatchCount());
			summary.put("truncated", result.truncated());

			batchRunService.completeBatch(runId, toJson(summary));
			appendAudit(runCorrelationId, result, validated.actor());
			return result;
		} catch (RuntimeException ex) {
			batchRunService.failBatch(runId, summarizeError(ex));
			throw ex;
		}
	}

	@Transactional(readOnly = true)
	public ExternalReconciliationBreakPage listBreaks(
			Long runId,
			String statementRef,
			String breakType,
			String status,
			Integer requestedLimit) {
		int limit = resolveReportingLimit(requestedLimit);
		String normalizedBreakType = normalizeOrNull(breakType);
		String normalizedStatus = normalizeOrNull(status);
		String normalizedStatementRef = trimOrNull(statementRef);

		if (normalizedBreakType != null && !EXTERNAL_BREAK_TYPES.contains(normalizedBreakType)) {
			throw new CoreBankException("breakType is not supported for external reconciliation");
		}

		StringBuilder sql = new StringBuilder(
				"""
				SELECT reconciliation_break_id,
				       break_type,
				       reference_type,
				       reference_id,
				       severity,
				       status,
				       details_json::text AS details_json,
				       opened_at,
				       resolved_at,
				       details_json->>'runId' AS run_id_text,
				       details_json->>'statementRef' AS statement_ref
				FROM reconciliation_breaks
				WHERE break_type IN ('ORPHAN_EXTERNAL','MISSING_EXTERNAL','AMOUNT_MISMATCH','STATUS_MISMATCH','AMBIGUOUS_MATCH','DUPLICATE_EXTERNAL','DUPLICATE_INTERNAL')
				""");

		List<Object> args = new ArrayList<>();
		if (runId != null) {
			sql.append(" AND details_json->>'runId' = ?");
			args.add(String.valueOf(runId));
		}
		if (normalizedStatementRef != null) {
			sql.append(" AND details_json->>'statementRef' = ?");
			args.add(normalizedStatementRef);
		}
		if (normalizedBreakType != null) {
			sql.append(" AND break_type = ?");
			args.add(normalizedBreakType);
		}
		if (normalizedStatus != null) {
			sql.append(" AND status = ?");
			args.add(normalizedStatus);
		}
		sql.append(" ORDER BY opened_at DESC, reconciliation_break_id DESC LIMIT ?");
		args.add(limit);

		List<ExternalReconciliationBreakView> items = jdbcTemplate.query(
				sql.toString(),
				breakViewMapper,
				args.toArray());

		return new ExternalReconciliationBreakPage(limit, items);
	}

	private ExternalReconciliationRunResult summarizeResult(
			long runId,
			UUID statementId,
			Integer statementVersion,
			ValidatedCommand command,
			MatchingPreparation preparation,
			List<BreakDecision> breakDecisions) {
		int duplicateExternalCount = countBreakType(breakDecisions, "DUPLICATE_EXTERNAL");
		int duplicateInternalCount = countBreakType(breakDecisions, "DUPLICATE_INTERNAL");
		int ambiguousCount = countBreakType(breakDecisions, "AMBIGUOUS_MATCH");
		int orphanExternalCount = countBreakType(breakDecisions, "ORPHAN_EXTERNAL");
		int missingExternalCount = countBreakType(breakDecisions, "MISSING_EXTERNAL");
		int amountMismatchCount = countBreakType(breakDecisions, "AMOUNT_MISMATCH");
		int statusMismatchCount = countBreakType(breakDecisions, "STATUS_MISMATCH");
		int breakCount = breakDecisions.size();
		int matchedCount = Math.max(preparation.processedGroupCount() - breakCount + missingExternalCount, 0);

		return new ExternalReconciliationRunResult(
				runId,
				statementId,
				command.statementRef(),
				statementVersion,
				command.statementDate(),
				command.provider(),
				command.dryRun(),
				command.entries().size(),
				preparation.processedEntryCount(),
				preparation.processedGroupCount(),
				matchedCount,
				breakCount,
				duplicateExternalCount,
				duplicateInternalCount,
				ambiguousCount,
				orphanExternalCount,
				missingExternalCount,
				amountMismatchCount,
				statusMismatchCount,
				preparation.truncated());
	}

	private int countBreakType(List<BreakDecision> breakDecisions, String breakType) {
		return (int) breakDecisions.stream().filter(item -> breakType.equals(item.breakType())).count();
	}

	private UUID persistStatementVersion(ValidatedCommand command, long runId) {
		acquireStatementLock(command.statementRef());
		int nextVersionNo = nextVersionNo(command.statementRef());
		UUID statementId = UUID.randomUUID();

		jdbcTemplate.update(
				"""
				UPDATE external_settlement_statements
				SET is_latest = false
				WHERE statement_ref = ?
				  AND is_latest = true
				""",
				command.statementRef());

		jdbcTemplate.update(
				"""
				INSERT INTO external_settlement_statements (
				    statement_id,
				    statement_ref,
				    version_no,
				    is_latest,
				    provider,
				    statement_date,
				    entry_count,
				    raw_payload_json,
				    imported_by,
				    imported_at,
				    created_at
				) VALUES (?, ?, ?, true, ?, ?, ?, CAST(? AS jsonb), ?, now(), now())
				""",
				statementId,
				command.statementRef(),
				nextVersionNo,
				command.provider(),
				Date.valueOf(command.statementDate()),
				command.entries().size(),
				toJson(Map.of(
						"runId", runId,
						"statementRef", command.statementRef(),
						"provider", command.provider(),
						"statementDate", command.statementDate(),
						"entryCount", command.entries().size(),
						"importedAt", Instant.now())),
				command.actor());

		return statementId;
	}

	private void persistEntries(UUID statementId, List<NormalizedExternalEntry> entries) {
		for (NormalizedExternalEntry entry : entries) {
			jdbcTemplate.update(
					"""
					INSERT INTO external_settlement_entries (
					    statement_id,
					    entry_order,
					    external_ref,
					    reference_type,
					    reference_id,
					    currency,
					    amount_minor,
					    status,
					    raw_payload_json
					) VALUES (?, ?, ?, ?, ?, ?, ?, ?, CAST(? AS jsonb))
					""",
					statementId,
					entry.entryOrder(),
					entry.externalRef(),
					entry.referenceType(),
					entry.referenceId(),
					entry.currency(),
					entry.amountMinor(),
					entry.status(),
					toJson(entry.rawPayload()));
		}
	}

	private void persistBreaks(UUID runCorrelationId, List<BreakDecision> decisions) {
		for (BreakDecision decision : decisions) {
			jdbcTemplate.update(
					"""
					INSERT INTO reconciliation_breaks (
					    reconciliation_run_id,
					    break_type,
					    reference_type,
					    reference_id,
					    severity,
					    status,
					    details_json,
					    opened_at
					) VALUES (?, ?, ?, ?, ?, 'OPEN', CAST(? AS jsonb), now())
					""",
					runCorrelationId,
					decision.breakType(),
					decision.referenceType(),
					decision.referenceId(),
					decision.severity(),
					toJson(decision.details()));
		}
	}

	private void appendAudit(UUID runCorrelationId, ExternalReconciliationRunResult result, String actor) {
		Map<String, Object> afterState = new LinkedHashMap<>();
		afterState.put("runId", result.runId());
		afterState.put("statementId", result.statementId());
		afterState.put("statementRef", result.statementRef());
		afterState.put("statementVersion", result.statementVersion());
		afterState.put("statementDate", result.statementDate());
		afterState.put("provider", result.provider());
		afterState.put("dryRun", result.dryRun());
		afterState.put("requestedCount", result.requestedCount());
		afterState.put("processedCount", result.processedCount());
		afterState.put("processedGroupCount", result.processedGroupCount());
		afterState.put("matchedCount", result.matchedCount());
		afterState.put("breakCount", result.breakCount());
		afterState.put("duplicateExternalCount", result.duplicateExternalCount());
		afterState.put("duplicateInternalCount", result.duplicateInternalCount());
		afterState.put("ambiguousCount", result.ambiguousCount());
		afterState.put("orphanExternalCount", result.orphanExternalCount());
		afterState.put("missingExternalCount", result.missingExternalCount());
		afterState.put("amountMismatchCount", result.amountMismatchCount());
		afterState.put("statusMismatchCount", result.statusMismatchCount());
		afterState.put("truncated", result.truncated());

		auditService.appendEvent(new AuditService.AuditCommand(
				actor,
				"EXTERNAL_RECONCILIATION_RUN_EXECUTED",
				"EXTERNAL_RECONCILIATION_RUN",
				String.valueOf(result.runId()),
				runCorrelationId,
				null,
				null,
				"external-reconciliation-service",
				null,
				toJson(afterState)));
	}

	private List<BreakDecision> evaluateBreaks(
			long runId,
			String statementRef,
			LocalDate statementDate,
			MatchingPreparation preparation,
			Map<MatchingKey, List<InternalEntry>> internalByKey) {
		List<BreakDecision> decisions = new ArrayList<>();
		Set<MatchingKey> externalKeys = new LinkedHashSet<>(preparation.processedByKey().keySet());

		for (MatchingKey truncatedKey : preparation.truncatedKeys()) {
			BreakDecision decision = buildBreak(
					"AMBIGUOUS_MATCH",
					EXTERNAL_BREAK_SEVERITY_HIGH,
					truncatedKey,
					Map.of(
							"runId", runId,
							"statementRef", statementRef,
							"statementDate", statementDate,
							"matchRuleCode", "RULE_TRUNCATED_KEY_GROUP",
							"reason", "TRUNCATED_KEY_GROUP"));
			decisions.add(decision);
		}

		for (Map.Entry<MatchingKey, List<NormalizedExternalEntry>> entry : preparation.processedByKey().entrySet()) {
			MatchingKey key = entry.getKey();
			if (preparation.truncatedKeys().contains(key)) {
				continue;
			}

			List<NormalizedExternalEntry> externalEntries = entry.getValue();
			List<InternalEntry> internalEntries = internalByKey.getOrDefault(key, List.of());

			if (externalEntries.size() > 1) {
				decisions.add(buildBreak(
						"DUPLICATE_EXTERNAL",
						EXTERNAL_BREAK_SEVERITY_HIGH,
						key,
						Map.of(
								"runId", runId,
								"statementRef", statementRef,
								"statementDate", statementDate,
								"matchRuleCode", "RULE_DUPLICATE_EXTERNAL",
								"externalCount", externalEntries.size(),
								"internalCount", internalEntries.size())));
				continue;
			}

			if (internalEntries.size() > 1) {
				decisions.add(buildBreak(
						"DUPLICATE_INTERNAL",
						EXTERNAL_BREAK_SEVERITY_HIGH,
						key,
						Map.of(
								"runId", runId,
								"statementRef", statementRef,
								"statementDate", statementDate,
								"matchRuleCode", "RULE_DUPLICATE_INTERNAL",
								"externalCount", externalEntries.size(),
								"internalCount", internalEntries.size())));
				continue;
			}

			if (externalEntries.size() != 1 || internalEntries.size() > 1) {
				decisions.add(buildBreak(
						"AMBIGUOUS_MATCH",
						EXTERNAL_BREAK_SEVERITY_HIGH,
						key,
						Map.of(
								"runId", runId,
								"statementRef", statementRef,
								"statementDate", statementDate,
								"matchRuleCode", "RULE_NON_1_TO_1",
								"externalCount", externalEntries.size(),
								"internalCount", internalEntries.size())));
				continue;
			}

			if (internalEntries.isEmpty()) {
				NormalizedExternalEntry external = externalEntries.get(0);
				decisions.add(buildBreak(
						"ORPHAN_EXTERNAL",
						EXTERNAL_BREAK_SEVERITY_HIGH,
						key,
						Map.of(
								"runId", runId,
								"statementRef", statementRef,
								"statementDate", statementDate,
								"matchRuleCode", "RULE_ORPHAN_EXTERNAL",
								"externalRef", external.externalRef(),
								"externalAmountMinor", external.amountMinor(),
								"externalStatus", external.status())));
				continue;
			}

			NormalizedExternalEntry external = externalEntries.get(0);
			InternalEntry internal = internalEntries.get(0);

			if (internal.amountMinor() != null && !internal.amountMinor().equals(external.amountMinor())) {
				decisions.add(buildBreak(
						"AMOUNT_MISMATCH",
						EXTERNAL_BREAK_SEVERITY_CRITICAL,
						key,
						Map.of(
								"runId", runId,
								"statementRef", statementRef,
								"statementDate", statementDate,
								"matchRuleCode", "RULE_AMOUNT_EXACT",
								"externalRef", external.externalRef(),
								"externalAmountMinor", external.amountMinor(),
								"internalAmountMinor", internal.amountMinor(),
								"externalStatus", external.status(),
								"internalStatus", internal.status())));
				continue;
			}

			if (!external.status().equals(internal.status())) {
				decisions.add(buildBreak(
						"STATUS_MISMATCH",
						EXTERNAL_BREAK_SEVERITY_HIGH,
						key,
						Map.of(
								"runId", runId,
								"statementRef", statementRef,
								"statementDate", statementDate,
								"matchRuleCode", "RULE_STATUS_PAYMENT_ORDER",
								"externalRef", external.externalRef(),
								"externalAmountMinor", external.amountMinor(),
								"internalAmountMinor", internal.amountMinor(),
								"externalStatus", external.status(),
								"internalStatus", internal.status())));
			}
		}

		for (Map.Entry<MatchingKey, List<InternalEntry>> internalEntry : internalByKey.entrySet()) {
			MatchingKey key = internalEntry.getKey();
			if (externalKeys.contains(key) || preparation.truncatedKeys().contains(key)) {
				continue;
			}

			List<InternalEntry> internals = internalEntry.getValue();
			if (internals.isEmpty()) {
				continue;
			}
			if (internals.size() > 1) {
				decisions.add(buildBreak(
						"DUPLICATE_INTERNAL",
						EXTERNAL_BREAK_SEVERITY_HIGH,
						key,
						Map.of(
								"runId", runId,
								"statementRef", statementRef,
								"statementDate", statementDate,
								"matchRuleCode", "RULE_DUPLICATE_INTERNAL",
								"externalCount", 0,
								"internalCount", internals.size())));
				continue;
			}

			InternalEntry internal = internals.get(0);
			Map<String, Object> details = new LinkedHashMap<>();
			details.put("runId", runId);
			details.put("statementRef", statementRef);
			details.put("statementDate", statementDate);
			details.put("matchRuleCode", "RULE_MISSING_EXTERNAL");
			details.put("internalJournalId", internal.journalId());
			details.put("internalAmountMinor", internal.amountMinor());
			details.put("internalStatus", internal.status());
			details.put("effectiveDateUtc", internal.effectiveDateUtc());

			decisions.add(buildBreak(
					"MISSING_EXTERNAL",
					EXTERNAL_BREAK_SEVERITY_HIGH,
					key,
					details));
		}

		return decisions;
	}

	private BreakDecision buildBreak(
			String breakType,
			String severity,
			MatchingKey key,
			Map<String, Object> details) {
		Map<String, Object> payload = new LinkedHashMap<>(details);
		payload.put("referenceType", key.referenceType());
		payload.put("referenceId", key.referenceId());
		payload.put("currency", key.currency());

		return new BreakDecision(
				breakType,
				key.referenceType(),
				key.referenceId(),
				severity,
				payload);
	}

	private Map<MatchingKey, List<InternalEntry>> loadInternalEntriesByKey(
			LocalDate statementDate,
			Set<MatchingKey> keys) {
		if (keys.isEmpty()) {
			return Map.of();
		}

		Set<String> referenceTypes = new LinkedHashSet<>();
		Set<String> currencies = new LinkedHashSet<>();
		for (MatchingKey key : keys) {
			referenceTypes.add(key.referenceType());
			currencies.add(key.currency());
		}

		String typePlaceholders = String.join(",", java.util.Collections.nCopies(referenceTypes.size(), "?"));
		String currencyPlaceholders = String.join(",", java.util.Collections.nCopies(currencies.size(), "?"));
		List<Object> args = new ArrayList<>();
		args.add(Date.valueOf(statementDate));
		args.addAll(referenceTypes);
		args.addAll(currencies);

		List<InternalEntry> rows = jdbcTemplate.query(
				"""
				SELECT lj.journal_id,
				       lj.reference_type,
				       lj.reference_id::text AS reference_id,
				       lj.currency,
				       DATE(lj.created_at AT TIME ZONE 'UTC') AS effective_date_utc,
				       amount_rows.debit_amount_minor,
				       'POSTED' AS internal_status
				FROM ledger_journals lj
				LEFT JOIN LATERAL (
				    SELECT SUM(CASE WHEN lp.entry_side = 'D' THEN lp.amount_minor ELSE 0 END) AS debit_amount_minor
				    FROM ledger_postings lp
				    WHERE lp.journal_id = lj.journal_id
				) amount_rows ON true
				WHERE DATE(lj.created_at AT TIME ZONE 'UTC') = ?
				  AND lj.reference_type IN (%s)
				  AND lj.currency IN (%s)
				ORDER BY lj.reference_type ASC, lj.reference_id ASC, lj.currency ASC, lj.created_at ASC, lj.journal_id ASC
				""".formatted(typePlaceholders, currencyPlaceholders),
				(rs, rowNum) -> new InternalEntry(
						rs.getObject("journal_id", UUID.class),
						rs.getString("reference_type"),
						rs.getString("reference_id"),
						rs.getString("currency"),
						readLongNullable(rs, "debit_amount_minor"),
						rs.getString("internal_status"),
						rs.getObject("effective_date_utc", Date.class).toLocalDate()),
				args.toArray());

		Map<MatchingKey, List<InternalEntry>> grouped = new LinkedHashMap<>();
		for (InternalEntry row : rows) {
			MatchingKey key = new MatchingKey(row.referenceType(), row.referenceId(), row.currency());
			if (!keys.contains(key)) {
				continue;
			}
			grouped.computeIfAbsent(key, ignored -> new ArrayList<>()).add(row);
		}
		return grouped;
	}

	private MatchingPreparation prepareEntriesForMatching(
			List<NormalizedExternalEntry> entries,
			int processingLimit) {
		List<NormalizedExternalEntry> sorted = new ArrayList<>(entries);
		sorted.sort(Comparator
				.comparing(NormalizedExternalEntry::referenceType)
				.thenComparing(NormalizedExternalEntry::referenceId)
				.thenComparing(NormalizedExternalEntry::currency)
				.thenComparing(NormalizedExternalEntry::externalRef)
				.thenComparingInt(NormalizedExternalEntry::entryOrder));

		Map<MatchingKey, List<NormalizedExternalEntry>> grouped = new LinkedHashMap<>();
		for (NormalizedExternalEntry entry : sorted) {
			MatchingKey key = new MatchingKey(entry.referenceType(), entry.referenceId(), entry.currency());
			grouped.computeIfAbsent(key, ignored -> new ArrayList<>()).add(entry);
		}

		Map<MatchingKey, List<NormalizedExternalEntry>> processedByKey = new LinkedHashMap<>();
		Set<MatchingKey> truncatedKeys = new LinkedHashSet<>();
		int processedCount = 0;
		boolean truncated = false;

		for (Map.Entry<MatchingKey, List<NormalizedExternalEntry>> groupedEntry : grouped.entrySet()) {
			MatchingKey key = groupedEntry.getKey();
			List<NormalizedExternalEntry> groupEntries = groupedEntry.getValue();
			int nextCount = processedCount + groupEntries.size();

			if (nextCount <= processingLimit) {
				processedByKey.put(key, groupEntries);
				processedCount = nextCount;
				continue;
			}

			truncated = true;
			if (processedCount < processingLimit) {
				truncatedKeys.add(key);
			}
			if (processedCount >= processingLimit) {
				break;
			}
		}

		return new MatchingPreparation(
				processedByKey,
				truncatedKeys,
				processedCount,
				processedByKey.size(),
				truncated);
	}

	private ValidatedCommand validateAndNormalize(ExternalReconciliationRunCommand command) {
		if (command == null) {
			throw new CoreBankException("request is required");
		}

		String statementRef = requireNonBlank(command.statementRef(), "statementRef is required");
		String provider = requireNonBlank(command.provider(), "provider is required");
		LocalDate statementDate = command.statementDate();
		if (statementDate == null) {
			throw new CoreBankException("statementDate is required");
		}

		int processingLimit = resolveProcessingLimit(command.processingLimit());
		boolean dryRun = command.dryRun() == null || command.dryRun();
		String actor = safeActor(command.actor());
		List<ExternalSettlementEntryInput> inputEntries = command.entries();
		if (inputEntries == null || inputEntries.isEmpty()) {
			throw new CoreBankException("entries must not be empty");
		}
		if (inputEntries.size() > MAX_INPUT_ENTRIES) {
			throw new CoreBankException("entries size must be <= 5000");
		}

		List<NormalizedExternalEntry> normalizedEntries = new ArrayList<>(inputEntries.size());
		int order = 1;
		for (ExternalSettlementEntryInput input : inputEntries) {
			if (input == null) {
				throw new CoreBankException("entry must not be null");
			}
			String referenceType = requireNonBlank(input.referenceType(), "entry.referenceType is required");
			String referenceId = requireNonBlank(input.referenceId(), "entry.referenceId is required");
			String currency = requireNonBlank(input.currency(), "entry.currency is required").toUpperCase(Locale.ROOT);
			if (currency.length() != 3) {
				throw new CoreBankException("entry.currency must be 3-letter ISO code");
			}
			if (input.amountMinor() == null || input.amountMinor() <= 0L) {
				throw new CoreBankException("entry.amountMinor must be positive");
			}
			String status = requireNonBlank(input.status(), "entry.status is required").toUpperCase(Locale.ROOT);
			String externalRef = input.externalRef() == null || input.externalRef().isBlank()
					? "ENTRY-" + order
					: input.externalRef().trim();

			Map<String, Object> rawPayload = new LinkedHashMap<>();
			rawPayload.put("externalRef", externalRef);
			rawPayload.put("referenceType", referenceType.toUpperCase(Locale.ROOT));
			rawPayload.put("referenceId", referenceId);
			rawPayload.put("currency", currency);
			rawPayload.put("amountMinor", input.amountMinor());
			rawPayload.put("status", status);
			rawPayload.put("metadata", input.metadata() == null ? Map.of() : input.metadata());

			normalizedEntries.add(new NormalizedExternalEntry(
					order,
					externalRef,
					referenceType.toUpperCase(Locale.ROOT),
					referenceId,
					currency,
					input.amountMinor(),
					status,
					rawPayload));
			order++;
		}

		return new ValidatedCommand(
				statementRef,
				provider,
				statementDate,
				processingLimit,
				dryRun,
				actor,
				normalizedEntries);
	}

	private int resolveProcessingLimit(Integer requestedLimit) {
		if (requestedLimit == null) {
			return DEFAULT_PROCESSING_LIMIT;
		}
		if (requestedLimit < 1 || requestedLimit > MAX_PROCESSING_LIMIT) {
			throw new CoreBankException("processingLimit must be between 1 and 5000");
		}
		return requestedLimit;
	}

	private int resolveReportingLimit(Integer requestedLimit) {
		if (requestedLimit == null) {
			return DEFAULT_REPORTING_LIMIT;
		}
		if (requestedLimit < 1 || requestedLimit > MAX_REPORTING_LIMIT) {
			throw new CoreBankException("limit must be between 1 and 200");
		}
		return requestedLimit;
	}

	private String safeActor(String actor) {
		if (actor == null || actor.trim().isEmpty()) {
			return "system";
		}
		return actor.trim();
	}

	private String requireNonBlank(String value, String message) {
		if (value == null || value.trim().isEmpty()) {
			throw new CoreBankException(message);
		}
		return value.trim();
	}

	private String normalizeOrNull(String value) {
		if (value == null || value.trim().isEmpty()) {
			return null;
		}
		return value.trim().toUpperCase(Locale.ROOT);
	}

	private String trimOrNull(String value) {
		if (value == null || value.trim().isEmpty()) {
			return null;
		}
		return value.trim();
	}

	private void acquireStatementLock(String statementRef) {
		jdbcTemplate.query(
				"SELECT pg_advisory_xact_lock(hashtext(?))",
				rs -> {
				},
				"external-reconciliation:" + statementRef);
	}

	private int nextVersionNo(String statementRef) {
		Integer currentMax = jdbcTemplate.queryForObject(
				"""
				SELECT COALESCE(MAX(version_no), 0)
				FROM external_settlement_statements
				WHERE statement_ref = ?
				""",
				Integer.class,
				statementRef);
		return (currentMax == null ? 0 : currentMax) + 1;
	}

	private int loadLatestVersionNo(String statementRef) {
		Integer version = jdbcTemplate.queryForObject(
				"""
				SELECT version_no
				FROM external_settlement_statements
				WHERE statement_ref = ?
				  AND is_latest = true
				""",
				Integer.class,
				statementRef);
		if (version == null) {
			throw new CoreBankException("Unable to resolve latest statement version");
		}
		return version;
	}

	private String buildBatchName(LocalDate statementDate) {
		return "EXTERNAL_RECONCILIATION_" + statementDate + "_" + Instant.now().toEpochMilli();
	}

	private UUID runCorrelationId(long runId) {
		return UUID.nameUUIDFromBytes(("external-reconciliation-run:" + runId).getBytes(StandardCharsets.UTF_8));
	}

	private String summarizeError(RuntimeException ex) {
		String message = ex.getMessage();
		if (message == null || message.isBlank()) {
			return ex.getClass().getSimpleName();
		}
		return message.length() > 500 ? message.substring(0, 500) : message;
	}

	private String toJson(Object value) {
		try {
			return objectMapper.writeValueAsString(value);
		} catch (JsonProcessingException ex) {
			throw new CoreBankException("Unable to serialize external reconciliation payload", ex);
		}
	}

	@SuppressWarnings("unchecked")
	private Map<String, Object> readPayloadMap(String json) {
		if (json == null || json.isBlank()) {
			return Map.of();
		}
		try {
			Object value = objectMapper.readValue(json, Map.class);
			if (value instanceof Map<?, ?> mapValue) {
				return (Map<String, Object>) mapValue;
			}
			return Map.of();
		} catch (JsonProcessingException ex) {
			throw new CoreBankException("Unable to parse external reconciliation details JSON", ex);
		}
	}

	private Instant readInstant(Timestamp timestamp) {
		return timestamp == null ? null : timestamp.toInstant();
	}

	private Long readLongNullable(ResultSet rs, String column) throws SQLException {
		BigDecimal value = rs.getBigDecimal(column);
		return value == null ? null : value.longValue();
	}

	private record ValidatedCommand(
			String statementRef,
			String provider,
			LocalDate statementDate,
			int processingLimit,
			boolean dryRun,
			String actor,
			List<NormalizedExternalEntry> entries) {
	}

	private record NormalizedExternalEntry(
			int entryOrder,
			String externalRef,
			String referenceType,
			String referenceId,
			String currency,
			long amountMinor,
			String status,
			Map<String, Object> rawPayload) {
	}

	private record MatchingKey(
			String referenceType,
			String referenceId,
			String currency) {
	}

	private record InternalEntry(
			UUID journalId,
			String referenceType,
			String referenceId,
			String currency,
			Long amountMinor,
			String status,
			LocalDate effectiveDateUtc) {
	}

	private record BreakDecision(
			String breakType,
			String referenceType,
			String referenceId,
			String severity,
			Map<String, Object> details) {
	}

	private record MatchingPreparation(
			Map<MatchingKey, List<NormalizedExternalEntry>> processedByKey,
			Set<MatchingKey> truncatedKeys,
			int processedEntryCount,
			int processedGroupCount,
			boolean truncated) {
		Set<MatchingKey> processedKeys() {
			Set<MatchingKey> keys = new LinkedHashSet<>(processedByKey.keySet());
			keys.addAll(truncatedKeys);
			return keys;
		}
	}

	public record ExternalReconciliationRunCommand(
			String statementRef,
			String provider,
			LocalDate statementDate,
			Integer processingLimit,
			Boolean dryRun,
			List<ExternalSettlementEntryInput> entries,
			String actor) {
	}

	public record ExternalSettlementEntryInput(
			String externalRef,
			String referenceType,
			String referenceId,
			String currency,
			Long amountMinor,
			String status,
			Map<String, Object> metadata) {
	}

	public record ExternalReconciliationRunResult(
			long runId,
			UUID statementId,
			String statementRef,
			Integer statementVersion,
			LocalDate statementDate,
			String provider,
			boolean dryRun,
			int requestedCount,
			int processedCount,
			int processedGroupCount,
			int matchedCount,
			int breakCount,
			int duplicateExternalCount,
			int duplicateInternalCount,
			int ambiguousCount,
			int orphanExternalCount,
			int missingExternalCount,
			int amountMismatchCount,
			int statusMismatchCount,
			boolean truncated) {
	}

	public record ExternalReconciliationBreakPage(
			int limit,
			List<ExternalReconciliationBreakView> items) {
	}

	public record ExternalReconciliationBreakView(
			UUID reconciliationBreakId,
			Long runId,
			String statementRef,
			String breakType,
			String referenceType,
			String referenceId,
			String severity,
			String status,
			Map<String, Object> details,
			Instant openedAt,
			Instant resolvedAt) {
	}
}
