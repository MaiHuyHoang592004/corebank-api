package com.corebank.corebank_api.reporting;

import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.corebank.corebank_api.TestcontainersConfiguration;
import com.corebank.corebank_api.integration.saga.SagaStateService;
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
@WithMockUser(username = "reporter", roles = "USER")
class SagaReportingControllerIntegrationTest {

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private SagaStateService sagaStateService;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@BeforeEach
	void setUp() {
		jdbcTemplate.update("DELETE FROM saga_steps");
		jdbcTemplate.update("DELETE FROM saga_instances");
	}

	@Test
	void sagas_returnsPagedAndFilteredData() throws Exception {
		SagaStateService.SagaInstance loanSaga = sagaStateService.startSaga(
				"LOAN_DISBURSEMENT",
				"loan-report-saga-1",
				"{\"phase\":\"INIT\"}");
		sagaStateService.updateSagaState(
				loanSaga.sagaInstanceId(),
				loanSaga.version(),
				SagaStateService.STATUS_RUNNING,
				"RESERVE_FUNDS",
				"{\"phase\":\"RESERVING\"}",
				null);

		SagaStateService.SagaInstance depositSaga = sagaStateService.startSaga(
				"DEPOSIT_OPENING",
				"deposit-report-saga-1",
				"{\"phase\":\"INIT\"}");
		sagaStateService.updateSagaState(
				depositSaga.sagaInstanceId(),
				depositSaga.version(),
				SagaStateService.STATUS_COMPLETED,
				"DONE",
				"{\"phase\":\"DONE\"}",
				null);

		mockMvc.perform(get("/api/reporting/sagas")
						.queryParam("page", "0")
						.queryParam("size", "1"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.page").value(0))
				.andExpect(jsonPath("$.size").value(1))
				.andExpect(jsonPath("$.totalItems").value(2))
				.andExpect(jsonPath("$.items.length()").value(1));

		mockMvc.perform(get("/api/reporting/sagas")
						.queryParam("sagaType", "LOAN_DISBURSEMENT")
						.queryParam("status", SagaStateService.STATUS_RUNNING)
						.queryParam("businessKey", "loan-report-saga-1")
						.queryParam("page", "0")
						.queryParam("size", "10"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.totalItems").value(1))
				.andExpect(jsonPath("$.items.length()").value(1))
				.andExpect(jsonPath("$.items[0].sagaType").value("LOAN_DISBURSEMENT"))
				.andExpect(jsonPath("$.items[0].businessKey").value("loan-report-saga-1"))
				.andExpect(jsonPath("$.items[0].status").value(SagaStateService.STATUS_RUNNING))
				.andExpect(jsonPath("$.items[0].currentStep").value("RESERVE_FUNDS"));
	}

	@Test
	void sagaSteps_returnsOnlyRequestedSagaStepsAndAppliesLimit() throws Exception {
		SagaStateService.SagaInstance loanSaga = sagaStateService.startSaga(
				"LOAN_DISBURSEMENT",
				"loan-report-saga-2",
				"{\"phase\":\"INIT\"}");
		sagaStateService.appendStepLog(
				loanSaga.sagaInstanceId(),
				1,
				"RESERVE_FUNDS",
				"FORWARD",
				"reserve-funds-1",
				"SUCCEEDED",
				"{\"amountMinor\":100000}",
				"{\"reservationId\":\"rsv-1\"}",
				null);
		sagaStateService.appendStepLog(
				loanSaga.sagaInstanceId(),
				2,
				"PUBLISH_EVENT",
				"FORWARD",
				"publish-event-1",
				"SUCCEEDED",
				"{\"eventType\":\"LOAN_DISBURSED\"}",
				"{\"topic\":\"loan-events\"}",
				null);

		SagaStateService.SagaInstance depositSaga = sagaStateService.startSaga(
				"DEPOSIT_OPENING",
				"deposit-report-saga-2",
				"{\"phase\":\"INIT\"}");
		sagaStateService.appendStepLog(
				depositSaga.sagaInstanceId(),
				1,
				"CREATE_ACCOUNT",
				"FORWARD",
				"create-account-1",
				"SUCCEEDED",
				"{\"customerId\":\"cust-1\"}",
				"{\"accountId\":\"acc-1\"}",
				null);

		mockMvc.perform(get("/api/reporting/sagas/{sagaInstanceId}/steps", loanSaga.sagaInstanceId())
						.queryParam("limit", "10"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.sagaInstanceId").value(loanSaga.sagaInstanceId().toString()))
				.andExpect(jsonPath("$.limit").value(10))
				.andExpect(jsonPath("$.items.length()").value(2))
				.andExpect(jsonPath("$.items[*].direction", everyItem(is("FORWARD"))))
				.andExpect(jsonPath("$.items[*].stepName",
						containsInAnyOrder("RESERVE_FUNDS", "PUBLISH_EVENT")));

		mockMvc.perform(get("/api/reporting/sagas/{sagaInstanceId}/steps", loanSaga.sagaInstanceId())
						.queryParam("limit", "1"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.limit").value(1))
				.andExpect(jsonPath("$.items.length()").value(1));
	}
}
