package com.corebank.corebank_api.integration.redis;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class MoneyWriteRateLimitWebConfig implements WebMvcConfigurer {

	private final MoneyWriteRateLimitInterceptor moneyWriteRateLimitInterceptor;

	public MoneyWriteRateLimitWebConfig(MoneyWriteRateLimitInterceptor moneyWriteRateLimitInterceptor) {
		this.moneyWriteRateLimitInterceptor = moneyWriteRateLimitInterceptor;
	}

	@Override
	public void addInterceptors(InterceptorRegistry registry) {
		registry.addInterceptor(moneyWriteRateLimitInterceptor)
				.addPathPatterns(
						"/api/deposits/open",
						"/api/deposits/accrue",
						"/api/deposits/maturity");
	}
}
