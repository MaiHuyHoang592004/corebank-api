package com.corebank.corebank_api.common;

/**
 * Raised when an account does not have enough available balance
 * to complete a requested operation.
 */
public class InsufficientFundsException extends CoreBankException {

	public InsufficientFundsException(String message) {
		super(message);
	}
}