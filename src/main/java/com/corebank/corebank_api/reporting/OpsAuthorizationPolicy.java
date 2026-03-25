package com.corebank.corebank_api.reporting;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

@Component
public class OpsAuthorizationPolicy {

	public void requireOpsAccess(Authentication authentication) {
		if (authentication == null) {
			throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Missing authentication");
		}

		boolean authorized = authentication.getAuthorities().stream()
				.map(GrantedAuthority::getAuthority)
				.anyMatch(authority -> "ROLE_OPS".equals(authority) || "ROLE_ADMIN".equals(authority));

		if (!authorized) {
			throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Insufficient authority");
		}
	}
}
