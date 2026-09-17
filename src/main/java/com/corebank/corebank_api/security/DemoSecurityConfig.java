package com.corebank.corebank_api.security;

import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.authorization.AuthorizationManager;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.intercept.RequestAuthorizationContext;

@Configuration
public class DemoSecurityConfig {

	private static final String SHOWCASE_TOKEN_HEADER = "X-Showcase-Token";

	@Value("${corebank.showcase.token-gate-enabled:false}")
	private boolean tokenGateEnabled;

	// No hardcoded fallback: an unset/blank token must fail closed, never silently
	// grant access via a value anyone can read straight out of this public repo.
	@Value("${corebank.showcase.token:}")
	private String showcaseToken;

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
				// HTTP Basic on every request, never a browser session cookie — this is
				// the precondition that makes disabling CSRF above actually safe.
				.sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
				.authorizeHttpRequests(authorize -> {
					authorize
						.requestMatchers("/actuator/health").permitAll()
							.requestMatchers("/", "/index.html").permitAll()
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
								"/api/ops/security/**")
							.access(showcaseTokenGate());
					}

					authorize.anyRequest().authenticated();
				})
				.httpBasic(Customizer.withDefaults())
				.formLogin(form -> form.disable());

		return http.build();
	}

	/**
	 * ADMIN role AND the correct {@value #SHOWCASE_TOKEN_HEADER} header, compared
	 * in constant time. Previously this config declared a "token gate" that never
	 * actually read {@code corebank.showcase.token} anywhere — the boolean flag
	 * alone drove a blanket denyAll(), so the documented "token unlocks destructive
	 * ops" behavior never existed. This makes the token real; a blank/unset token
	 * still fails every request closed, it just no longer misrepresents why.
	 */
	private AuthorizationManager<RequestAuthorizationContext> showcaseTokenGate() {
		return (authentication, context) -> {
			boolean isAdmin = authentication.get().getAuthorities().stream()
					.anyMatch(authority -> "ROLE_ADMIN".equals(authority.getAuthority()));
			boolean tokenMatches = isAdmin && tokenMatches(context.getRequest());
			return new AuthorizationDecision(tokenMatches);
		};
	}

	private boolean tokenMatches(HttpServletRequest request) {
		if (showcaseToken == null || showcaseToken.isBlank()) {
			return false;
		}
		String provided = request.getHeader(SHOWCASE_TOKEN_HEADER);
		if (provided == null) {
			return false;
		}
		return MessageDigest.isEqual(
				provided.getBytes(StandardCharsets.UTF_8),
				showcaseToken.getBytes(StandardCharsets.UTF_8));
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
