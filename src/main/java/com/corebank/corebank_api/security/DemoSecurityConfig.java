package com.corebank.corebank_api.security;

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
				.authorizeHttpRequests(authorize -> authorize
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
						.hasAnyRole("USER", "OPS", "ADMIN")
						.anyRequest().authenticated())
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
