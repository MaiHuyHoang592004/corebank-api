package com.corebank.corebank_api.ops.maintenance;

import com.corebank.corebank_api.common.CoreBankException;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class PartitionArchiveReadinessService {

	private static final int DEFAULT_RETENTION_MONTHS = 12;
	private static final int MIN_RETENTION_MONTHS = 1;
	private static final int MAX_RETENTION_MONTHS = 120;

	private static final int DEFAULT_LIMIT = 200;
	private static final int MAX_LIMIT = 500;

	private static final String CANDIDATE_REASON = "OLDER_THAN_RETENTION";
	private static final DateTimeFormatter MONTH_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM");
	private static final DateTimeFormatter PARTITION_SUFFIX_FORMATTER = DateTimeFormatter.ofPattern("yyyy_MM");

	private static final List<String> ALLOWED_PARENT_TABLES = List.of(
			"ledger_journals_p",
			"ledger_postings_p",
			"audit_events_p");

	private final JdbcTemplate jdbcTemplate;

	public PartitionArchiveReadinessService(JdbcTemplate jdbcTemplate) {
		this.jdbcTemplate = jdbcTemplate;
	}

	public PartitionArchiveCandidatePage listArchiveCandidates(
			Integer requestedRetentionMonths,
			Integer requestedLimit,
			List<String> requestedParentTables) {
		int retentionMonths = resolveRetentionMonths(requestedRetentionMonths);
		int limit = resolveLimit(requestedLimit);
		List<String> parentTables = resolveParentTables(requestedParentTables);

		YearMonth asOfMonth = YearMonth.now();
		YearMonth cutoffMonth = asOfMonth.minusMonths(retentionMonths);
		List<PartitionArchiveCandidateItem> candidates = new ArrayList<>();

		for (String parentTable : parentTables) {
			for (String partitionName : listChildPartitions(parentTable)) {
				YearMonth partitionMonth = parsePartitionMonth(parentTable, partitionName);
				if (partitionMonth == null) {
					continue;
				}
				if (partitionMonth.isBefore(cutoffMonth)) {
					candidates.add(new PartitionArchiveCandidateItem(
							parentTable,
							partitionName,
							partitionMonth.format(MONTH_FORMATTER),
							CANDIDATE_REASON));
				}
			}
		}

		candidates.sort(Comparator
				.comparing(PartitionArchiveCandidateItem::partitionMonth)
				.thenComparing(PartitionArchiveCandidateItem::partitionName));

		int candidateCount = candidates.size();
		boolean truncated = candidateCount > limit;
		List<PartitionArchiveCandidateItem> items = truncated
				? candidates.subList(0, limit)
				: candidates;

		return new PartitionArchiveCandidatePage(
				asOfMonth.format(MONTH_FORMATTER),
				retentionMonths,
				limit,
				candidateCount,
				truncated,
				items);
	}

	private int resolveRetentionMonths(Integer value) {
		if (value == null) {
			return DEFAULT_RETENTION_MONTHS;
		}
		if (value < MIN_RETENTION_MONTHS || value > MAX_RETENTION_MONTHS) {
			throw new CoreBankException("retentionMonths must be between 1 and 120");
		}
		return value;
	}

	private int resolveLimit(Integer value) {
		if (value == null) {
			return DEFAULT_LIMIT;
		}
		if (value < 1 || value > MAX_LIMIT) {
			throw new CoreBankException("limit must be between 1 and 500");
		}
		return value;
	}

	private List<String> resolveParentTables(List<String> values) {
		if (values == null || values.isEmpty()) {
			return ALLOWED_PARENT_TABLES;
		}

		Set<String> normalized = new LinkedHashSet<>();
		for (String value : values) {
			if (value == null || value.trim().isEmpty()) {
				throw new CoreBankException("parentTable must not be blank");
			}
			String table = value.trim();
			if (!ALLOWED_PARENT_TABLES.contains(table)) {
				throw new CoreBankException("parentTable must be one of ledger_journals_p, ledger_postings_p, audit_events_p");
			}
			normalized.add(table);
		}
		return new ArrayList<>(normalized);
	}

	private List<String> listChildPartitions(String parentTable) {
		return jdbcTemplate.queryForList(
				"""
				SELECT c.relname
				FROM pg_inherits i
				JOIN pg_class c ON c.oid = i.inhrelid
				JOIN pg_namespace cn ON cn.oid = c.relnamespace
				JOIN pg_class p ON p.oid = i.inhparent
				JOIN pg_namespace pn ON pn.oid = p.relnamespace
				WHERE pn.nspname = 'public'
				  AND cn.nspname = 'public'
				  AND p.relname = ?
				ORDER BY c.relname ASC
				""",
				String.class,
				parentTable);
	}

	private YearMonth parsePartitionMonth(String parentTable, String partitionName) {
		String prefix = parentTable + "_";
		if (!partitionName.startsWith(prefix)) {
			return null;
		}

		String suffix = partitionName.substring(prefix.length());
		try {
			return YearMonth.parse(suffix, PARTITION_SUFFIX_FORMATTER);
		} catch (DateTimeParseException ex) {
			return null;
		}
	}

	public record PartitionArchiveCandidatePage(
			String asOfMonth,
			int retentionMonths,
			int limit,
			int candidateCount,
			boolean truncated,
			List<PartitionArchiveCandidateItem> items) {
	}

	public record PartitionArchiveCandidateItem(
			String parentTable,
			String partitionName,
			String partitionMonth,
			String reason) {
	}
}
