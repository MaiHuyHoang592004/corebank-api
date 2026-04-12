package com.corebank.corebank_api.common;

/**
 * Raised when the same idempotency key is reused with a different payload.
 */
public class IdempotencyConflictException extends CoreBankException {

	public IdempotencyConflictException(String message) {
		super(message);
	}
}