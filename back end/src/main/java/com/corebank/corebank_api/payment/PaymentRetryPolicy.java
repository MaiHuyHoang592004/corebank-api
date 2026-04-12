package com.corebank.corebank_api.payment;

import com.corebank.corebank_api.common.MoneyCommandRetryPolicy;
import java.sql.SQLException;
import java.util.Set;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DeadlockLoserDataAccessException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.stereotype.Component;

@Component
public class PaymentRetryPolicy implements MoneyCommandRetryPolicy {

	private static final int MAX_ATTEMPTS = 3;
	private static final long FIRST_RETRY_BACKOFF_MILLIS = 50L;
	private static final long NEXT_RETRY_BACKOFF_MILLIS = 150L;

	private static final Set<String> TRANSIENT_SQL_STATES = Set.of(
			"40P01",
			"55P03",
			"40001");

	public int maxAttempts() {
		return MAX_ATTEMPTS;
	}

	public long backoffMillisBeforeNextAttempt(int failedAttempt) {
		return failedAttempt <= 1
				? FIRST_RETRY_BACKOFF_MILLIS
				: NEXT_RETRY_BACKOFF_MILLIS;
	}

	public boolean isTransient(Throwable throwable) {
		for (Throwable current = throwable; current != null; current = current.getCause()) {
			if (current instanceof CannotAcquireLockException
					|| current instanceof PessimisticLockingFailureException
					|| current instanceof DeadlockLoserDataAccessException) {
				return true;
			}
			if (current instanceof SQLException sql && isTransientSqlState(sql.getSQLState())) {
				return true;
			}
		}
		return false;
	}

	public String extractSqlState(Throwable throwable) {
		for (Throwable current = throwable; current != null; current = current.getCause()) {
			if (current instanceof SQLException sql && sql.getSQLState() != null && !sql.getSQLState().isBlank()) {
				return sql.getSQLState();
			}
		}
		return null;
	}

	private boolean isTransientSqlState(String sqlState) {
		if (sqlState == null) {
			return false;
		}
		return TRANSIENT_SQL_STATES.contains(sqlState.trim().toUpperCase());
	}
}
