package com.corebank.corebank_api.ops.system;

import com.corebank.corebank_api.common.CoreBankException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * Backstop for the "no write while EOD_LOCK/MAINTENANCE" rule.
 *
 * <p>Every money command already calls {@link SystemModeService#enforceWriteAllowed()}
 * through {@code IdempotentMoneyCommandTemplate}, and the two batch jobs in
 * {@code LoanApplicationService} that bypass that template call it manually — but
 * both are a convention a future money-mutating endpoint can simply forget to
 * follow, with nothing else catching the omission. This interceptor is the same
 * pattern already used for rate limiting ({@link com.corebank.corebank_api.integration.redis.MoneyWriteRateLimitInterceptor}):
 * applied to a fixed URL allowlist rather than relying on every call site
 * remembering, so a forgotten check still fails closed at the HTTP boundary.
 */
@Component
public class SystemModeWriteGuardInterceptor implements HandlerInterceptor {

	private final SystemModeService systemModeService;
	private final ObjectMapper objectMapper;

	public SystemModeWriteGuardInterceptor(SystemModeService systemModeService, ObjectMapper objectMapper) {
		this.systemModeService = systemModeService;
		this.objectMapper = objectMapper;
	}

	@Override
	public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
			throws IOException {
		try {
			systemModeService.enforceWriteAllowed();
			return true;
		} catch (CoreBankException ex) {
			response.setStatus(HttpStatus.CONFLICT.value());
			response.setContentType(MediaType.APPLICATION_JSON_VALUE);
			response.getWriter().write(objectMapper.writeValueAsString(Map.of("message", ex.getMessage())));
			return false;
		}
	}
}
