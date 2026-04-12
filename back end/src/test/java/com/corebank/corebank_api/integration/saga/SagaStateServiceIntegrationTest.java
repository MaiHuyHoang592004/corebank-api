package com.corebank.corebank_api.integration.saga;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.corebank.corebank_api.TestcontainersConfiguration;
import com.corebank.corebank_api.common.CoreBankException;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
class SagaStateServiceIntegrationTest {

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
	void startSaga_isIdempotentBySagaTypeAndBusinessKey() {
		SagaStateService.SagaInstance first = sagaStateService.startSaga(
				"LOAN_DISBURSEMENT",
				"loan-001",
				"{\"phase\":\"INIT\"}");
		SagaStateService.SagaInstance second = sagaStateService.startSaga(
				"LOAN_DISBURSEMENT",
				"loan-001",
				"{\"phase\":\"SHOULD_NOT_REPLACE\"}");

		assertEquals(first.sagaInstanceId(), second.sagaInstanceId());
		assertEquals(SagaStateService.STATUS_PENDING, second.status());
		assertEquals(0L, second.version());
		assertTrue(second.contextJson().contains("INIT"));
	}

	@Test
	void appendStepLog_isIdempotentAndQueryable() {
		SagaStateService.SagaInstance saga = sagaStateService.startSaga(
				"LOAN_DISBURSEMENT",
				"loan-002",
				"{\"phase\":\"INIT\"}");

		boolean first = sagaStateService.appendStepLog(
				saga.sagaInstanceId(),
				1,
				"PUBLISH_OUTBOX",
				"FORWARD",
				"publish-v1",
				"SUCCEEDED",
				"{\"eventType\":\"LOAN_DISBURSED\"}",
				"{\"topic\":\"loan-events\"}",
				null);
		boolean duplicate = sagaStateService.appendStepLog(
				saga.sagaInstanceId(),
				2,
				"PUBLISH_OUTBOX",
				"FORWARD",
				"publish-v1",
				"SUCCEEDED",
				"{\"eventType\":\"LOAN_DISBURSED\"}",
				"{\"topic\":\"loan-events\"}",
				null);

		assertTrue(first);
		assertFalse(duplicate);

		List<SagaStateService.SagaStepLog> logs = sagaStateService.findStepLogs(saga.sagaInstanceId());
		assertEquals(1, logs.size());
		assertEquals("PUBLISH_OUTBOX", logs.get(0).stepName());
		assertEquals("publish-v1", logs.get(0).stepKey());
		assertEquals("SUCCEEDED", logs.get(0).status());
		assertNotNull(logs.get(0).startedAt());
	}

	@Test
	void updateSagaState_enforcesOptimisticVersionForResume() {
		SagaStateService.SagaInstance started = sagaStateService.startSaga(
				"LOAN_DISBURSEMENT",
				"loan-003",
				"{\"phase\":\"INIT\"}");

		SagaStateService.SagaInstance inProgress = sagaStateService.updateSagaState(
				started.sagaInstanceId(),
				started.version(),
				SagaStateService.STATUS_RUNNING,
				"PUBLISH_OUTBOX",
				"{\"phase\":\"PUBLISHED\"}",
				null);
		assertEquals(1L, inProgress.version());
		assertEquals(SagaStateService.STATUS_RUNNING, inProgress.status());
		assertEquals("PUBLISH_OUTBOX", inProgress.currentStep());

		assertThrows(CoreBankException.class, () -> sagaStateService.updateSagaState(
				started.sagaInstanceId(),
				started.version(),
				SagaStateService.STATUS_FAILED,
				"PUBLISH_OUTBOX",
				"{\"phase\":\"FAILED\"}",
				"{\"reason\":\"stale-update\"}"));

		SagaStateService.SagaInstance completed = sagaStateService.updateSagaState(
				inProgress.sagaInstanceId(),
				inProgress.version(),
				SagaStateService.STATUS_COMPLETED,
				"COMPLETE",
				"{\"phase\":\"DONE\"}",
				null);
		assertEquals(2L, completed.version());
		assertEquals(SagaStateService.STATUS_COMPLETED, completed.status());
	}
}
