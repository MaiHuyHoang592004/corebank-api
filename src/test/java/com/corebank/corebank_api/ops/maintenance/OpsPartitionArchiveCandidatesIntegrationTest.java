package com.corebank.corebank_api.ops.maintenance;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.corebank.corebank_api.TestcontainersConfiguration;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = "spring.task.scheduling.enabled=false")
@AutoConfigureMockMvc
@WithMockUser(username = "viewer", roles = "USER")
class OpsPartitionArchiveCandidatesIntegrationTest {

	private static final DateTimeFormatter PARTITION_SUFFIX_FORMATTER = DateTimeFormatter.ofPattern("yyyy_MM");
	private static final DateTimeFormatter PARTITION_DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd");
	private static final List<String> PARENTS = List.of("ledger_journals_p", "ledger_postings_p", "audit_events_p");
	private static final List<YearMonth> FIXED_MONTHS = List.of(
			YearMonth.of(2001, 1),
			YearMonth.of(2001, 2),
			YearMonth.of(2001, 3),
			YearMonth.of(2001, 4),
			YearMonth.of(2099, 1),
			YearMonth.of(2099, 2));

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@BeforeEach
	void setUp() {
		for (String parent : PARENTS) {
			for (YearMonth month : FIXED_MONTHS) {
				dropPartitionIfExists(parent, month);
			}
		}
	}

	@Test
	void archiveCandidates_forbiddenForUserRole() throws Exception {
		mockMvc.perform(get("/api/ops/maintenance/partitions/archive-candidates"))
				.andExpect(status().isForbidden());
	}

	@Test
	@WithMockUser(username = "ops-user", roles = "OPS")
	void archiveCandidates_defaultsAndDetectsOldCandidates() throws Exception {
		createPartition("ledger_journals_p", YearMonth.of(2001, 1));
		createPartition("ledger_journals_p", YearMonth.of(2099, 1));

		mockMvc.perform(get("/api/ops/maintenance/partitions/archive-candidates"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.retentionMonths").value(12))
				.andExpect(jsonPath("$.limit").value(200))
				.andExpect(jsonPath("$.candidateCount", greaterThanOrEqualTo(1)))
				.andExpect(jsonPath("$.truncated").value(false))
				.andExpect(content().string(containsString("ledger_journals_p_2001_01")))
				.andExpect(content().string(containsString("OLDER_THAN_RETENTION")));
	}

	@Test
	@WithMockUser(username = "admin-user", roles = "ADMIN")
	void archiveCandidates_filtersByParentTable() throws Exception {
		createPartition("ledger_journals_p", YearMonth.of(2001, 1));
		createPartition("ledger_postings_p", YearMonth.of(2001, 2));
		createPartition("audit_events_p", YearMonth.of(2001, 3));

		mockMvc.perform(get("/api/ops/maintenance/partitions/archive-candidates")
						.queryParam("parentTable", "ledger_postings_p")
						.queryParam("retentionMonths", "12"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.candidateCount", greaterThanOrEqualTo(1)))
				.andExpect(jsonPath("$.items[*].parentTable", everyItem(is("ledger_postings_p"))))
				.andExpect(content().string(containsString("ledger_postings_p_2001_02")));
	}

	@Test
	@WithMockUser(username = "ops-user", roles = "OPS")
	void archiveCandidates_returnsBadRequestForInvalidParams() throws Exception {
		mockMvc.perform(get("/api/ops/maintenance/partitions/archive-candidates")
						.queryParam("retentionMonths", "0"))
				.andExpect(status().isBadRequest());

		mockMvc.perform(get("/api/ops/maintenance/partitions/archive-candidates")
						.queryParam("retentionMonths", "121"))
				.andExpect(status().isBadRequest());

		mockMvc.perform(get("/api/ops/maintenance/partitions/archive-candidates")
						.queryParam("limit", "0"))
				.andExpect(status().isBadRequest());

		mockMvc.perform(get("/api/ops/maintenance/partitions/archive-candidates")
						.queryParam("limit", "501"))
				.andExpect(status().isBadRequest());

		mockMvc.perform(get("/api/ops/maintenance/partitions/archive-candidates")
						.queryParam("parentTable", "unknown_table"))
				.andExpect(status().isBadRequest());
	}

	@Test
	@WithMockUser(username = "ops-user", roles = "OPS")
	void archiveCandidates_appliesLimitAndReturnsTruncated() throws Exception {
		createPartition("ledger_journals_p", YearMonth.of(2001, 1));
		createPartition("ledger_journals_p", YearMonth.of(2001, 2));
		createPartition("ledger_postings_p", YearMonth.of(2001, 3));
		createPartition("audit_events_p", YearMonth.of(2001, 4));

		mockMvc.perform(get("/api/ops/maintenance/partitions/archive-candidates")
						.queryParam("retentionMonths", "12")
						.queryParam("limit", "2"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.limit").value(2))
				.andExpect(jsonPath("$.candidateCount", greaterThanOrEqualTo(4)))
				.andExpect(jsonPath("$.items.length()").value(2))
				.andExpect(jsonPath("$.truncated").value(true))
				.andExpect(jsonPath("$.items[0].partitionMonth").value("2001-01"));
	}

	private void createPartition(String parentTable, YearMonth month) {
		String partitionName = partitionName(parentTable, month);
		String fromDate = month.atDay(1).format(PARTITION_DATE_FORMATTER);
		String toDate = month.plusMonths(1).atDay(1).format(PARTITION_DATE_FORMATTER);
		String sql = "CREATE TABLE IF NOT EXISTS " + partitionName
				+ " PARTITION OF " + parentTable
				+ " FOR VALUES FROM ('" + fromDate + "') TO ('" + toDate + "')";
		jdbcTemplate.execute(sql);
	}

	private void dropPartitionIfExists(String parentTable, YearMonth month) {
		jdbcTemplate.execute("DROP TABLE IF EXISTS " + partitionName(parentTable, month));
	}

	private String partitionName(String parentTable, YearMonth month) {
		return parentTable + "_" + month.format(PARTITION_SUFFIX_FORMATTER);
	}
}
