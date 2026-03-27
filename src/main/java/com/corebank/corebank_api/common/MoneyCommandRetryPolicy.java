package com.corebank.corebank_api.common;

public interface MoneyCommandRetryPolicy {

	int maxAttempts();

	long backoffMillisBeforeNextAttempt(int failedAttempt);

	boolean isTransient(Throwable throwable);

	String extractSqlState(Throwable throwable);
}
