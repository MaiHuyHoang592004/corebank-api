package com.corebank.corebank_api.reporting;

import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.stereotype.Component;

/**
 * Actuator health indicator for read-model projection pipelines.
 */
@Component
public class ReadModelHealthIndicator implements HealthIndicator {

	private final ReadModelHealthService readModelHealthService;

	public ReadModelHealthIndicator(ReadModelHealthService readModelHealthService) {
		this.readModelHealthService = readModelHealthService;
	}

	@Override
	public Health health() {
		ReadModelHealthService.ReadModelHealthSnapshot snapshot = readModelHealthService.snapshot();
		Health.Builder builder = snapshot.healthy() ? Health.up() : Health.down();
		return builder
				.withDetail("feedCount", snapshot.feedCount())
				.withDetail("summaryCount", snapshot.summaryCount())
				.withDetail("pendingOutboxCount", snapshot.pendingOutboxCount())
				.withDetail("lagSeconds", snapshot.lagSeconds())
				.withDetail("maxAllowedLagSeconds", snapshot.maxAllowedLagSeconds())
				.withDetail("maxPendingOutbox", snapshot.maxPendingOutbox())
				.withDetail("latestProjectedOccurredAt", snapshot.latestProjectedOccurredAt())
				.withDetail("latestOutboxCreatedAt", snapshot.latestOutboxCreatedAt())
				.build();
	}
}
