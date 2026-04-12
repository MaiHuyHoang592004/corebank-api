package com.corebank.corebank_api.integration;

import java.util.UUID;

/**
 * Metadata attached to outbox events for traceability.
 */
public record OutboxMetadata(
		String correlationId,
		String requestId,
		String actor,
		String causationId) {

	public static OutboxMetadata empty() {
		return new OutboxMetadata(null, null, null, null);
	}

	public static OutboxMetadata of(String correlationId, String requestId, String actor) {
		return new OutboxMetadata(correlationId, requestId, actor, null);
	}

	public static OutboxMetadata of(UUID correlationId, UUID requestId, String actor) {
		return new OutboxMetadata(asString(correlationId), asString(requestId), actor, null);
	}

	public static OutboxMetadata legacy(String correlationId, String causationId) {
		return new OutboxMetadata(correlationId, null, null, causationId);
	}

	private static String asString(UUID value) {
		return value == null ? null : value.toString();
	}
}
