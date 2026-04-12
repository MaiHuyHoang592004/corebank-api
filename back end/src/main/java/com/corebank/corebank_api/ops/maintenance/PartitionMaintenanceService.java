package com.corebank.corebank_api.ops.maintenance;

import com.corebank.corebank_api.common.CoreBankException;
import com.corebank.corebank_api.ops.audit.AuditService;
import com.corebank.corebank_api.ops.batch.BatchRunService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PartitionMaintenanceService {

	private static final int DEFAULT_MONTHS_AHEAD = 3;
	private static final int MAX_MONTHS_AHEAD = 12;

	private static final DateTimeFormatter MONTH_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM");
	private static final DateTimeFormatter PARTITION_SUFFIX_FORMATTER = DateTimeFormatter.ofPattern("yyyy_MM");
	private static final DateTimeFormatter PARTITION_DATE_FORMATTER = DateTimeFormatter.ISO_LOCAL_DATE;

	private static final List<String> MANAGED_PARENT_TABLES = List.of(
			"ledger_journals_p",
			"ledger_postings_p",
			"audit_events_p");

	private final JdbcTemplate jdbcTemplate;
	private final BatchRunService batchRunService;
	private final AuditService auditService;
	private final ObjectMapper objectMapper;

	public PartitionMaintenanceService(
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
	public PartitionMaintenanceResult ensureFuturePartitions(
			String fromMonthValue,
			Integer requestedMonthsAhead,
			String actor) {
		YearMonth fromMonth = resolveFromMonth(fromMonthValue);
		int monthsAhead = resolveMonthsAhead(requestedMonthsAhead);
		String safeActor = safeActor(actor);

		long runId = batchRunService.startBatch(
				buildBatchName(fromMonth),
				"PARTITION_MAINTENANCE",
				toJson(Map.of(
						"fromMonth", fromMonth.format(MONTH_FORMATTER),
						"monthsAhead", monthsAhead)));
		UUID correlationId = correlationId(runId);

		try {
			List<PartitionItem> items = new ArrayList<>();
			int createdCount = 0;
			int existingCount = 0;
			int errorCount = 0;

			for (String parentTable : MANAGED_PARENT_TABLES) {
				for (int offset = 0; offset < monthsAhead; offset++) {
					YearMonth targetMonth = fromMonth.plusMonths(offset);
					String partitionName = partitionName(parentTable, targetMonth);
					boolean existedBefore = partitionExists(partitionName);

					try {
						jdbcTemplate.execute(buildCreatePartitionSql(parentTable, partitionName, targetMonth));
						if (existedBefore) {
							existingCount++;
							items.add(new PartitionItem(
									parentTable,
									partitionName,
									rangeStart(targetMonth),
									rangeEnd(targetMonth),
									"EXISTING",
									"partition already existed"));
						} else {
							createdCount++;
							items.add(new PartitionItem(
									parentTable,
									partitionName,
									rangeStart(targetMonth),
									rangeEnd(targetMonth),
									"CREATED",
									"partition created"));
						}
					} catch (RuntimeException ex) {
						errorCount++;
						items.add(new PartitionItem(
								parentTable,
								partitionName,
								rangeStart(targetMonth),
								rangeEnd(targetMonth),
								"ERROR",
								summarizeError(ex)));
					}
				}
			}

			PartitionMaintenanceResult result = new PartitionMaintenanceResult(
					runId,
					fromMonth.format(MONTH_FORMATTER),
					monthsAhead,
					createdCount,
					existingCount,
					errorCount,
					items,
					"Partition maintenance ensures readiness only; it does not switch active write paths.");

			batchRunService.completeBatch(runId, toJson(Map.of(
					"runId", result.runId(),
					"fromMonth", result.fromMonth(),
					"monthsAhead", result.monthsAhead(),
					"createdCount", result.createdCount(),
					"existingCount", result.existingCount(),
					"errorCount", result.errorCount(),
					"itemCount", result.items().size())));
			appendAudit(result, safeActor, correlationId);
			return result;
		} catch (RuntimeException ex) {
			batchRunService.failBatch(runId, summarizeError(ex));
			throw ex;
		}
	}

	private boolean partitionExists(String partitionName) {
		Boolean exists = jdbcTemplate.queryForObject(
				"SELECT to_regclass(?) IS NOT NULL",
				Boolean.class,
				partitionName);
		return Boolean.TRUE.equals(exists);
	}

	private String buildCreatePartitionSql(String parentTable, String partitionName, YearMonth targetMonth) {
		String fromDate = rangeStart(targetMonth);
		String toDate = rangeEnd(targetMonth);
		return "CREATE TABLE IF NOT EXISTS "
				+ partitionName
				+ " PARTITION OF "
				+ parentTable
				+ " FOR VALUES FROM ('"
				+ fromDate
				+ "') TO ('"
				+ toDate
				+ "')";
	}

	private YearMonth resolveFromMonth(String value) {
		if (value == null || value.trim().isEmpty()) {
			return YearMonth.now();
		}
		try {
			return YearMonth.parse(value.trim(), MONTH_FORMATTER);
		} catch (DateTimeParseException ex) {
			throw new CoreBankException("fromMonth must be yyyy-MM");
		}
	}

	private int resolveMonthsAhead(Integer value) {
		if (value == null) {
			return DEFAULT_MONTHS_AHEAD;
		}
		if (value < 1 || value > MAX_MONTHS_AHEAD) {
			throw new CoreBankException("monthsAhead must be between 1 and 12");
		}
		return value;
	}

	private String partitionName(String parentTable, YearMonth month) {
		return parentTable + "_" + month.format(PARTITION_SUFFIX_FORMATTER);
	}

	private String rangeStart(YearMonth month) {
		return month.atDay(1).format(PARTITION_DATE_FORMATTER);
	}

	private String rangeEnd(YearMonth month) {
		return month.plusMonths(1).atDay(1).format(PARTITION_DATE_FORMATTER);
	}

	private String buildBatchName(YearMonth month) {
		return "PARTITION_MAINTENANCE_" + month.format(MONTH_FORMATTER) + "_" + Instant.now().toEpochMilli();
	}

	private UUID correlationId(long runId) {
		return UUID.nameUUIDFromBytes(("partition-maintenance-run:" + runId).getBytes(StandardCharsets.UTF_8));
	}

	private void appendAudit(PartitionMaintenanceResult result, String actor, UUID correlationId) {
		Map<String, Object> after = new LinkedHashMap<>();
		after.put("runId", result.runId());
		after.put("fromMonth", result.fromMonth());
		after.put("monthsAhead", result.monthsAhead());
		after.put("createdCount", result.createdCount());
		after.put("existingCount", result.existingCount());
		after.put("errorCount", result.errorCount());
		after.put("itemCount", result.items().size());
		after.put("note", result.note());

		auditService.appendEvent(new AuditService.AuditCommand(
				actor,
				"PARTITION_MAINTENANCE_EXECUTED",
				"PARTITION_MAINTENANCE",
				String.valueOf(result.runId()),
				correlationId,
				null,
				null,
				"partition-maintenance-service",
				null,
				toJson(after)));
	}

	private String safeActor(String actor) {
		if (actor == null || actor.trim().isEmpty()) {
			return "system";
		}
		return actor.trim();
	}

	private String summarizeError(Throwable throwable) {
		String type = throwable == null ? "RuntimeException" : throwable.getClass().getSimpleName();
		String message = throwable == null || throwable.getMessage() == null
				? "unexpected error"
				: throwable.getMessage().trim();
		if (message.length() > 220) {
			message = message.substring(0, 220);
		}
		return (type + ": " + message).toLowerCase(Locale.ROOT);
	}

	private String toJson(Object payload) {
		try {
			return objectMapper.writeValueAsString(payload);
		} catch (JsonProcessingException ex) {
			throw new CoreBankException("Unable to serialize partition maintenance payload", ex);
		}
	}

	public record PartitionMaintenanceResult(
			long runId,
			String fromMonth,
			int monthsAhead,
			int createdCount,
			int existingCount,
			int errorCount,
			List<PartitionItem> items,
			String note) {
	}

	public record PartitionItem(
			String parentTable,
			String partitionName,
			String rangeFrom,
			String rangeTo,
			String status,
			String message) {
	}
}
