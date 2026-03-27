package com.corebank.corebank_api.ops.approval;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.corebank.corebank_api.TestcontainersConfiguration;
import com.corebank.corebank_api.integration.OutboxService;
import com.corebank.corebank_api.lending.LoanApplicationService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = "spring.task.scheduling.enabled=false")
@AutoConfigureMockMvc
class OpsApprovalExecutionIntegrationTest {

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@Autowired
	private OutboxService outboxService;

	@Autowired
	private LoanApplicationService loanApplicationService;

	private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

	@BeforeEach
	void setUp() {
		jdbcTemplate.update("DELETE FROM approvals");
		jdbcTemplate.update("DELETE FROM iam_user_roles");
		jdbcTemplate.update("DELETE FROM iam_role_permissions");
		jdbcTemplate.update("DELETE FROM iam_staff_users");
		jdbcTemplate.update("DELETE FROM iam_permissions");
		jdbcTemplate.update("DELETE FROM iam_roles");
		jdbcTemplate.update("DELETE FROM outbox_dead_letters");
		jdbcTemplate.update("DELETE FROM outbox_events");
		jdbcTemplate.update("DELETE FROM loan_events");
		jdbcTemplate.update("DELETE FROM repayment_schedules");
		jdbcTemplate.update("DELETE FROM loan_contracts");
		setRuntimeMode("RUNNING");
	}

	@Test
	void approvalFlow_outboxBulkRequeue_requiresMakerCheckerAndOneTimeExecution() throws Exception {
		Long outboxEventId = seedDeadLetterOutboxEvent();

		UUID approvalId = createApproval(
				user("maker").roles("MAKER"),
				"""
				{
				  "operationType": "OUTBOX_BULK_REQUEUE",
				  "operationPayload": {
				    "outboxEventIds": [%d]
				  }
				}
				""".formatted(outboxEventId));

		mockMvc.perform(post("/api/ops/approvals/{approvalId}/approve", approvalId)
						.with(user("approver").roles("APPROVER"))
						.with(csrf())
						.contentType("application/json")
						.content("{\"decisionReason\":\"validated by checker\"}"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("APPROVED"))
				.andExpect(jsonPath("$.decidedBy").value("approver"))
				.andExpect(jsonPath("$.executionStatus").value("NOT_EXECUTED"));

		mockMvc.perform(post("/api/ops/executions/outbox-dead-letter-requeue-bulk")
						.with(user("ops").roles("OPS"))
						.with(csrf())
						.contentType("application/json")
						.content("{\"approvalId\":\"" + approvalId + "\"}"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.approvalId").value(approvalId.toString()))
				.andExpect(jsonPath("$.result.requeuedCount").value(1))
				.andExpect(jsonPath("$.result.notFoundCount").value(0))
				.andExpect(jsonPath("$.result.conflictCount").value(0));

		Map<String, Object> outboxRow = jdbcTemplate.queryForMap(
				"SELECT status, retry_count FROM outbox_events WHERE id = ?",
				outboxEventId);
		assertEquals("PENDING", outboxRow.get("status"));
		assertEquals(0, ((Number) outboxRow.get("retry_count")).intValue());

		mockMvc.perform(post("/api/ops/executions/outbox-dead-letter-requeue-bulk")
						.with(user("ops").roles("OPS"))
						.with(csrf())
						.contentType("application/json")
						.content("{\"approvalId\":\"" + approvalId + "\"}"))
				.andExpect(status().isConflict());
	}

	@Test
	void makerChecker_sameActorCannotApproveOwnRequest() throws Exception {
		UUID approvalId = createApproval(
				user("same-actor").roles("MAKER"),
				"""
				{
				  "operationType": "OUTBOX_BULK_REQUEUE",
				  "operationPayload": {
				    "outboxEventIds": [1001]
				  }
				}
				""");

		mockMvc.perform(post("/api/ops/approvals/{approvalId}/approve", approvalId)
						.with(user("same-actor").roles("APPROVER"))
						.with(csrf())
						.contentType("application/json")
						.content("{\"decisionReason\":\"self-approve\"}"))
				.andExpect(status().isConflict());
	}

	@Test
	void executeRejectedOrPendingApproval_isBlocked() throws Exception {
		UUID approvalId = createApproval(
				user("maker").roles("MAKER"),
				"""
				{
				  "operationType": "OUTBOX_BULK_REQUEUE",
				  "operationPayload": {
				    "outboxEventIds": [2001]
				  }
				}
				""");

		mockMvc.perform(post("/api/ops/executions/outbox-dead-letter-requeue-bulk")
						.with(user("ops").roles("OPS"))
						.with(csrf())
						.contentType("application/json")
						.content("{\"approvalId\":\"" + approvalId + "\"}"))
				.andExpect(status().isConflict());

		mockMvc.perform(post("/api/ops/approvals/{approvalId}/reject", approvalId)
						.with(user("approver").roles("APPROVER"))
						.with(csrf())
						.contentType("application/json")
						.content("{\"decisionReason\":\"not eligible\"}"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("REJECTED"));

		mockMvc.perform(post("/api/ops/executions/outbox-dead-letter-requeue-bulk")
						.with(user("ops").roles("OPS"))
						.with(csrf())
						.contentType("application/json")
						.content("{\"approvalId\":\"" + approvalId + "\"}"))
				.andExpect(status().isConflict());
	}

	@Test
	void executeOutboxBulkRequeue_returnsConflictWhenRuntimeModeIsNotRunning_andKeepsStateUnchanged() throws Exception {
		Long outboxEventId = seedDeadLetterOutboxEvent();
		UUID approvalId = createApproval(
				user("maker").roles("MAKER"),
				"""
				{
				  "operationType": "OUTBOX_BULK_REQUEUE",
				  "operationPayload": {
				    "outboxEventIds": [%d]
				  }
				}
				""".formatted(outboxEventId));

		mockMvc.perform(post("/api/ops/approvals/{approvalId}/approve", approvalId)
						.with(user("approver").roles("APPROVER"))
						.with(csrf())
						.contentType("application/json")
						.content("{\"decisionReason\":\"ready to execute\"}"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("APPROVED"))
				.andExpect(jsonPath("$.executionStatus").value("NOT_EXECUTED"));

		setRuntimeMode("EOD_LOCK");

		mockMvc.perform(post("/api/ops/executions/outbox-dead-letter-requeue-bulk")
						.with(user("ops").roles("OPS"))
						.with(csrf())
						.contentType("application/json")
						.content("{\"approvalId\":\"" + approvalId + "\"}"))
				.andExpect(status().isConflict());

		Map<String, Object> approvalRow = jdbcTemplate.queryForMap(
				"SELECT status, execution_status, executed_by, executed_at FROM approvals WHERE approval_id = ?",
				approvalId);
		assertEquals("APPROVED", approvalRow.get("status"));
		assertEquals("NOT_EXECUTED", approvalRow.get("execution_status"));
		assertNull(approvalRow.get("executed_by"));
		assertNull(approvalRow.get("executed_at"));

		Map<String, Object> outboxRow = jdbcTemplate.queryForMap(
				"SELECT status, retry_count FROM outbox_events WHERE id = ?",
				outboxEventId);
		assertEquals("FAILED", outboxRow.get("status"));
		assertEquals(3, ((Number) outboxRow.get("retry_count")).intValue());
	}

	@Test
	void approvalFlow_loanDefault_executesUsingApprovedPayloadOnly() throws Exception {
		LoanSeed seeded = seedLoanContractEligibleForDefault();
		LocalDate asOfDate = LocalDate.now();

		loanApplicationService.markOverdueInstallments(
				new LoanApplicationService.LoanOverdueTransitionRequest(
						asOfDate,
						"collector",
						UUID.randomUUID(),
						UUID.randomUUID(),
						UUID.randomUUID(),
						"trace-overdue-approval"));

		UUID approvalId = createApproval(
				user("maker").roles("MAKER"),
				"""
				{
				  "operationType": "LOAN_DEFAULT",
				  "operationPayload": {
				    "contractId": "%s",
				    "asOfDate": "%s"
				  }
				}
				""".formatted(seeded.contractId(), asOfDate));

		mockMvc.perform(post("/api/ops/approvals/{approvalId}/approve", approvalId)
						.with(user("approver").roles("APPROVER"))
						.with(csrf())
						.contentType("application/json")
						.content("{\"decisionReason\":\"default approved\"}"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("APPROVED"));

		mockMvc.perform(post("/api/ops/executions/loan-default")
						.with(user("ops").roles("OPS"))
						.with(csrf())
						.contentType("application/json")
						.content("{\"approvalId\":\"" + approvalId + "\"}"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.approvalId").value(approvalId.toString()))
				.andExpect(jsonPath("$.result.contractId").value(seeded.contractId().toString()))
				.andExpect(jsonPath("$.result.transitioned").value(true))
				.andExpect(jsonPath("$.result.status").value("DEFAULTED"));

		Map<String, Object> contract = jdbcTemplate.queryForMap(
				"SELECT status FROM loan_contracts WHERE contract_id = ?",
				seeded.contractId());
		assertEquals("DEFAULTED", contract.get("status"));
	}

	@Test
	void executeLoanDefault_returnsConflictWhenRuntimeModeIsNotRunning_andKeepsStateUnchanged() throws Exception {
		LoanSeed seeded = seedLoanContractEligibleForDefault();
		LocalDate asOfDate = LocalDate.now();

		loanApplicationService.markOverdueInstallments(
				new LoanApplicationService.LoanOverdueTransitionRequest(
						asOfDate,
						"collector",
						UUID.randomUUID(),
						UUID.randomUUID(),
						UUID.randomUUID(),
						"trace-overdue-blocked-execution"));

		UUID approvalId = createApproval(
				user("maker").roles("MAKER"),
				"""
				{
				  "operationType": "LOAN_DEFAULT",
				  "operationPayload": {
				    "contractId": "%s",
				    "asOfDate": "%s"
				  }
				}
				""".formatted(seeded.contractId(), asOfDate));

		mockMvc.perform(post("/api/ops/approvals/{approvalId}/approve", approvalId)
						.with(user("approver").roles("APPROVER"))
						.with(csrf())
						.contentType("application/json")
						.content("{\"decisionReason\":\"loan default approved\"}"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("APPROVED"))
				.andExpect(jsonPath("$.executionStatus").value("NOT_EXECUTED"));

		String statusBefore = jdbcTemplate.queryForObject(
				"SELECT status FROM loan_contracts WHERE contract_id = ?",
				String.class,
				seeded.contractId());
		setRuntimeMode("MAINTENANCE");

		mockMvc.perform(post("/api/ops/executions/loan-default")
						.with(user("ops").roles("OPS"))
						.with(csrf())
						.contentType("application/json")
						.content("{\"approvalId\":\"" + approvalId + "\"}"))
				.andExpect(status().isConflict());

		Map<String, Object> approvalRow = jdbcTemplate.queryForMap(
				"SELECT status, execution_status, executed_by, executed_at FROM approvals WHERE approval_id = ?",
				approvalId);
		assertEquals("APPROVED", approvalRow.get("status"));
		assertEquals("NOT_EXECUTED", approvalRow.get("execution_status"));
		assertNull(approvalRow.get("executed_by"));
		assertNull(approvalRow.get("executed_at"));

		String statusAfter = jdbcTemplate.queryForObject(
				"SELECT status FROM loan_contracts WHERE contract_id = ?",
				String.class,
				seeded.contractId());
		assertEquals(statusBefore, statusAfter);
	}

	private UUID createApproval(
			org.springframework.test.web.servlet.request.RequestPostProcessor actor,
			String requestJson) throws Exception {
		MvcResult result = mockMvc.perform(post("/api/ops/approvals")
						.with(actor)
						.with(csrf())
						.contentType("application/json")
						.content(requestJson))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.status").value("PENDING"))
				.andExpect(jsonPath("$.executionStatus").value("NOT_EXECUTED"))
				.andReturn();

		JsonNode json = objectMapper.readTree(result.getResponse().getContentAsString());
		UUID approvalId = UUID.fromString(json.get("approvalId").asText());
		assertNotNull(approvalId);
		return approvalId;
	}

	private Long seedDeadLetterOutboxEvent() {
		outboxService.appendMessage(
				"OPS_TEST",
				"approval-outbox-1",
				"FAILED_OUTBOX_APPROVAL_EVT",
				Map.of("amountMinor", 100_000L));

		jdbcTemplate.update(
				"""
				UPDATE outbox_events
				SET status = 'FAILED',
				    retry_count = 3,
				    processed_at = CURRENT_TIMESTAMP,
				    last_error = 'exhausted'
				WHERE event_type = 'FAILED_OUTBOX_APPROVAL_EVT'
				""");

		jdbcTemplate.update(
				"""
				INSERT INTO outbox_dead_letters (
				    outbox_event_id,
				    aggregate_type,
				    aggregate_id,
				    event_type,
				    event_data,
				    retry_count,
				    last_error,
				    dead_lettered_at
				)
				SELECT id,
				       aggregate_type,
				       aggregate_id,
				       event_type,
				       event_data,
				       retry_count,
				       last_error,
				       now()
				FROM outbox_events
				WHERE event_type = 'FAILED_OUTBOX_APPROVAL_EVT'
				""");

		return jdbcTemplate.queryForObject(
				"SELECT id FROM outbox_events WHERE event_type = 'FAILED_OUTBOX_APPROVAL_EVT'",
				Long.class);
	}

	private LoanSeed seedLoanContractEligibleForDefault() {
		UUID customerId = UUID.randomUUID();
		UUID productId = UUID.randomUUID();
		UUID productVersionId = UUID.randomUUID();
		UUID borrowerAccountId = UUID.randomUUID();
		UUID loanReceivableLedger = UUID.randomUUID();
		UUID customerSettlementLedger = UUID.randomUUID();

		jdbcTemplate.update(
				"""
				INSERT INTO customers (customer_id, customer_type, full_name, email, phone, status, risk_band)
				VALUES (?, 'INDIVIDUAL', ?, ?, ?, 'ACTIVE', 'LOW')
				""",
				customerId,
				"Loan Default Customer",
				"loan-default@example.com",
				"0900000999");

		jdbcTemplate.update(
				"""
				INSERT INTO bank_products (product_id, product_code, product_name, product_type, currency, status)
				VALUES (?, ?, ?, 'LOAN', 'VND', 'ACTIVE')
				""",
				productId,
				"LN-APPROVAL-" + productId.toString().substring(0, 8),
				"Loan Approval Product");

		jdbcTemplate.update(
				"""
				INSERT INTO bank_product_versions (
				    product_version_id,
				    product_id,
				    version_no,
				    effective_from,
				    effective_to,
				    status,
				    configuration_json,
				    created_at
				) VALUES (?, ?, 1, now() - interval '1 day', NULL, 'ACTIVE', '{}'::jsonb, now())
				""",
				productVersionId,
				productId);

		jdbcTemplate.update(
				"""
				INSERT INTO customer_accounts (
				    customer_account_id, customer_id, product_id, account_number, currency, status,
				    posted_balance_minor, available_balance_minor, version, created_at, updated_at
				) VALUES (?, ?, ?, ?, 'VND', 'ACTIVE', ?, ?, 0, now(), now())
				""",
				borrowerAccountId,
				customerId,
				productId,
				"LN-APPROVAL-ACC-" + borrowerAccountId.toString().substring(0, 8),
				1_000_000L,
				1_000_000L);

		jdbcTemplate.update(
				"""
				INSERT INTO ledger_accounts (
				    ledger_account_id, account_code, account_name, account_type, currency, is_active
				) VALUES (?, ?, ?, 'ASSET', 'VND', true)
				""",
				loanReceivableLedger,
				"LN-RECV-APPROVAL-" + loanReceivableLedger.toString().substring(0, 8),
				"Loan Receivable Approval");

		jdbcTemplate.update(
				"""
				INSERT INTO ledger_accounts (
				    ledger_account_id, account_code, account_name, account_type, currency, is_active
				) VALUES (?, ?, ?, 'LIABILITY', 'VND', true)
				""",
				customerSettlementLedger,
				"LN-CUST-APPROVAL-" + customerSettlementLedger.toString().substring(0, 8),
				"Customer Settlement Approval");

		String idempotencyKey = "idem-approval-loan-default-" + UUID.randomUUID();
		LoanApplicationService.LoanDisbursementResponse disbursement = loanApplicationService.disburseLoan(
				new LoanApplicationService.LoanDisbursementRequest(
						idempotencyKey,
						borrowerAccountId,
						productId,
						productVersionId,
						500_000L,
						"VND",
						12.0,
						3,
						loanReceivableLedger,
						customerSettlementLedger,
						"loan-officer",
						UUID.randomUUID(),
						UUID.randomUUID(),
						UUID.randomUUID(),
						"trace-loan-approval-default"));

		jdbcTemplate.update(
				"""
				UPDATE repayment_schedules
				SET due_date = ?
				WHERE contract_id = ?
				  AND installment_no = 1
				""",
				LocalDate.now().minusDays(2),
				disbursement.contractId());

		return new LoanSeed(disbursement.contractId());
	}

	private record LoanSeed(
			UUID contractId) {
	}

	private void setRuntimeMode(String status) {
		jdbcTemplate.update(
				"""
				UPDATE system_configs
				SET config_value = jsonb_set(config_value, '{status}', to_jsonb(?::text)),
				    updated_at = now(),
				    updated_by = 'test-suite'
				WHERE config_key = 'runtime_mode'
				""",
				status);
	}
}
