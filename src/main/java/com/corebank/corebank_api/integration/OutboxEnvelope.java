package com.corebank.corebank_api.integration;

import java.time.Instant;
import java.util.UUID;

/**
 * Stable event envelope written into outbox event_data.
 */
public record OutboxEnvelope(
		UUID eventId,
		String aggregateType,
		String aggregateId,
		String eventType,
		Instant occurredAt,
		String schemaVersion,
		String correlationId,
		String requestId,
		String actor,
		Object payload) {
}
