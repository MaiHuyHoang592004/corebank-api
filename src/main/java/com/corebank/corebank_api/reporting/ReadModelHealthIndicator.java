package com.corebank.corebank_api.reporting;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.stereotype.Component;

import java.util.Objects;

/**
 * Actuator health indicator for read-model projection pipelines.
 * Reports UP when Kafka projection is intentionally disabled (showcase mode).
 */
@Component
public class ReadModelHealthIndicator implements HealthIndicator {

	private final ReadModelHealthService readModelHealthService;

	@Value("${corebank.kafka.enabled:true}")
	private boolean kafkaEnabled;

	public ReadModelHealthIndicator(ReadModelHealthService readModelHealthService) {
		this.readModelHealthService = readModelHealthService;
	}

	@Override
	public Health health() {
		ReadModelHealthService.ReadModelHealthSnapshot snapshot = readModelHealthService.snapshot();

		// When Kafka is disabled, read model projection is intentionally not running.
		// Do not flag as DOWN — the app is healthy.
		if (!kafkaEnabled) {
			return Health.up()
					.withDetail("feedCount", snapshot.feedCount())
					.withDetail("summaryCount", snapshot.summaryCount())
					.withDetail("pendingOutboxCount", snapshot.pendingOutboxCount())
					.withDetail("note", "Kafka projection disabled — read model not updated")
					.build();
		}

		Health.Builder builder = snapshot.healthy() ? Health.up() : Health.down();
		return builder
				.withDetail("feedCount", snapshot.feedCount())
				.withDetail("summaryCount", snapshot.summaryCount())
				.withDetail("pendingOutboxCount", snapshot.pendingOutboxCount())
				.withDetail("lagSeconds", snapshot.lagSeconds())
				.withDetail("maxAllowedLagSeconds", snapshot.maxAllowedLagSeconds())
				.withDetail("maxPendingOutbox", snapshot.maxPendingOutbox())
				.withDetail("latestProjectedOccurredAt", Objects.toString(snapshot.latestProjectedOccurredAt(), "N/A"))
				.withDetail("latestOutboxCreatedAt", Objects.toString(snapshot.latestOutboxCreatedAt(), "N/A"))
				.build();
	}
}
