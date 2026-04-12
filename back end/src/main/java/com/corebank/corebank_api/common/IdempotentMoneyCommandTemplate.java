package com.corebank.corebank_api.common;

import com.corebank.corebank_api.integration.IdempotencyService;
import com.corebank.corebank_api.ops.system.SystemModeService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * Backward-compatible alias for legacy wiring.
 *
 * <p>Prefer {@link MoneyCommandTemplate} in new code.
 */
@Deprecated(forRemoval = false)
public class IdempotentMoneyCommandTemplate extends MoneyCommandTemplate {

	public IdempotentMoneyCommandTemplate(
			IdempotencyService idempotencyService,
			SystemModeService systemModeService,
			ObjectMapper objectMapper,
			PlatformTransactionManager transactionManager) {
		super(idempotencyService, systemModeService, objectMapper, transactionManager);
	}
}
