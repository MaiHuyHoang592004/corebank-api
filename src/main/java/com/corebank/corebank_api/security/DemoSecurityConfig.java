package com.corebank.corebank_api.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
public class DemoSecurityConfig {

	@Value("${corebank.showcase.token-gate-enabled:false}")
	private boolean tokenGateEnabled;

	@Bean
	public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
		http
				.csrf(csrf -> csrf
						.ignoringRequestMatchers(
								"/api/demo/**",
								"/api/payments/**",
								"/api/transfers/internal",
								"/api/lending/disburse",
								"/api/lending/repay",
								"/api/deposits/open",
								"/api/deposits/accrue",
								"/api/deposits/maturity"))
				.authorizeHttpRequests(authorize -> {
					authorize
						// The probe paths must be anonymous: kubelet sends no credentials, so a 401 on
						// liveness restart-loops every pod and a 401 on readiness keeps them all out of
						// the Service. Listed explicitly rather than as /actuator/health/** so that
						// per-indicator detail paths stay behind authentication.
						.requestMatchers(
								"/actuator/health",
								"/actuator/health/liveness",
								"/actuator/health/readiness")
						.permitAll()
							.requestMatchers("/", "/index.html").permitAll()
							// Spring dispatches handler errors to /error. Without this, an anonymous
							// request that produces a 404 on an otherwise public path is answered with
							// 401, which reports the wrong problem to anyone poking at the demo.
							.requestMatchers("/error").permitAll()
							.requestMatchers("/dashboard", "/dashboard/**").permitAll()
						.requestMatchers("/api/demo/**").hasAnyRole("OPS", "ADMIN")
						.requestMatchers(
								"/api/payments/**",
								"/api/transfers/internal",
								"/api/lending/disburse",
								"/api/lending/repay",
								"/api/deposits/open",
								"/api/deposits/accrue",
								"/api/deposits/maturity")
						.hasAnyRole("USER", "OPS", "ADMIN");

					if (tokenGateEnabled) {
						authorize
							.requestMatchers(
								"/api/ops/maintenance/**",
								"/api/ops/executions/**",
								// Was "/api/ops/security/**", which matched no controller at all.
								// The customer-secret endpoints it was written to protect are
								// mapped at /api/ops/customers by OpsCustomerSecretController, so
								// the rule denied a path nobody could reach while the endpoints
								// that read and write encrypted national ids, tax ids and KYC
								// payloads fell through to anyRequest().authenticated() — reachable
								// by demo_user in any showcase deployment. A deny rule aimed at the
								// wrong path is worse than no rule, because the gate looks present.
								"/api/ops/customers/**")
							.denyAll();
					}

					authorize.anyRequest().authenticated();
				})
				.httpBasic(Customizer.withDefaults())
				.formLogin(form -> form.disable());

		return http.build();
	}

	@Bean
	public UserDetailsService userDetailsService() {
		return new InMemoryUserDetailsManager(
				User.withUsername("demo_user")
						.password("{noop}demo_user")
						.roles("USER")
						.build(),
				User.withUsername("demo_ops")
						.password("{noop}demo_ops")
						.roles("USER", "OPS")
						.build(),
				User.withUsername("demo_admin")
						.password("{noop}demo_admin")
						.roles("USER", "OPS", "ADMIN", "MAKER", "APPROVER")
						.build());
	}
}
