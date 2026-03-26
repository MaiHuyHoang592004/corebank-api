package com.corebank.corebank_api.transfer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.corebank.corebank_api.common.CoreBankException;
import java.sql.SQLException;
import org.junit.jupiter.api.Test;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DeadlockLoserDataAccessException;
import org.springframework.dao.PessimisticLockingFailureException;

class TransferRetryPolicyTest {

	private final TransferRetryPolicy policy = new TransferRetryPolicy();

	@Test
	void isTransient_trueForLockAndDeadlockExceptions() {
		assertTrue(policy.isTransient(new CannotAcquireLockException("lock")));
		assertTrue(policy.isTransient(new PessimisticLockingFailureException("pessimistic")));
		assertTrue(policy.isTransient(new DeadlockLoserDataAccessException("deadlock", new SQLException("deadlock", "40P01"))));
	}

	@Test
	void isTransient_trueForConfiguredSqlStateInCauseChain() {
		Throwable ex = new RuntimeException(new SQLException("serialization failure", "40001"));
		assertTrue(policy.isTransient(ex));
		assertEquals("40001", policy.extractSqlState(ex));
	}

	@Test
	void isTransient_falseForNonTransientBusinessError() {
		assertFalse(policy.isTransient(new CoreBankException("business validation failed")));
		assertNull(policy.extractSqlState(new CoreBankException("business validation failed")));
	}

	@Test
	void backoffMillisBeforeNextAttempt_matchesPolicy() {
		assertEquals(50L, policy.backoffMillisBeforeNextAttempt(1));
		assertEquals(150L, policy.backoffMillisBeforeNextAttempt(2));
		assertEquals(150L, policy.backoffMillisBeforeNextAttempt(3));
		assertEquals(3, policy.maxAttempts());
	}
}
