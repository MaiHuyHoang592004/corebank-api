package com.corebank.corebank_api.ops.system;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/** Same money-mutation path list as {@code MoneyWriteRateLimitWebConfig} — kept in
 * sync deliberately, since both guard the identical set of endpoints. */
@Configuration
public class SystemModeWriteGuardWebConfig implements WebMvcConfigurer {

	private final SystemModeWriteGuardInterceptor systemModeWriteGuardInterceptor;

	public SystemModeWriteGuardWebConfig(SystemModeWriteGuardInterceptor systemModeWriteGuardInterceptor) {
		this.systemModeWriteGuardInterceptor = systemModeWriteGuardInterceptor;
	}

	@Override
	public void addInterceptors(InterceptorRegistry registry) {
		registry.addInterceptor(systemModeWriteGuardInterceptor)
				.addPathPatterns(
						"/api/payments/authorize-hold",
						"/api/payments/capture-hold",
						"/api/payments/void-hold",
						"/api/payments/refund",
						"/api/transfers/internal",
						"/api/deposits/open",
						"/api/deposits/accrue",
						"/api/deposits/maturity",
						"/api/lending/disburse",
						"/api/lending/repay");
	}
}
