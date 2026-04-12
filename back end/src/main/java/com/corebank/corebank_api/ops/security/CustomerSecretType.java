package com.corebank.corebank_api.ops.security;

import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

public enum CustomerSecretType {
	NATIONAL_ID,
	TAX_ID,
	ADDRESS,
	PHONE,
	EMAIL,
	BIOMETRIC_REF;

	private static final Set<String> ALLOWED_CODES = Set.of(
			NATIONAL_ID.name(),
			TAX_ID.name(),
			ADDRESS.name(),
			PHONE.name(),
			EMAIL.name(),
			BIOMETRIC_REF.name());

	public static CustomerSecretType parse(String value) {
		if (value == null || value.trim().isEmpty()) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "secretType is required");
		}

		String normalized = value.trim().toUpperCase(Locale.ROOT);
		if (!ALLOWED_CODES.contains(normalized)) {
			throw new ResponseStatusException(
					HttpStatus.BAD_REQUEST,
					"secretType must be one of: " + allowedCodes());
		}

		return CustomerSecretType.valueOf(normalized);
	}

	private static String allowedCodes() {
		return ALLOWED_CODES.stream()
				.sorted()
				.collect(Collectors.joining(", "));
	}
}
