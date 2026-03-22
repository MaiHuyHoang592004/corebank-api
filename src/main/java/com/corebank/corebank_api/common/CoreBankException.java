package com.corebank.corebank_api.common;

/**
 * Base unchecked exception for the CoreBank application.
 *
 * <p>This type is intentionally generic and reusable across modules.
 * It does not contain domain-specific behavior.</p>
 */
public class CoreBankException extends RuntimeException {

	public CoreBankException(String message) {
		super(message);
	}

	public CoreBankException(String message, Throwable cause) {
		super(message, cause);
	}
}