package com.corebank.corebank_api.reporting;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;
import org.springframework.stereotype.Component;

/**
 * Registers operational gauges for read-model projection.
 */
@Component
public class ReadModelMetricsBinder implements MeterBinder {

	private final ReadModelHealthService readModelHealthService;

	public ReadModelMetricsBinder(ReadModelHealthService readModelHealthService) {
		this.readModelHealthService = readModelHealthService;
	}

	@Override
	public void bindTo(MeterRegistry registry) {
		Gauge.builder("corebank.read_model.feed.count", readModelHealthService, s -> (double) s.feedCount())
				.description("Number of projected event-feed rows")
				.register(registry);

		Gauge.builder("corebank.read_model.summary.count", readModelHealthService, s -> (double) s.summaryCount())
				.description("Number of typed read-model summary rows")
				.register(registry);

		Gauge.builder("corebank.read_model.outbox.pending.count", readModelHealthService, s -> (double) s.pendingOutboxCount())
				.description("Outbox rows waiting for projector visibility")
				.register(registry);

		Gauge.builder("corebank.read_model.projection.lag.seconds", readModelHealthService, s -> (double) s.projectionLagSeconds())
				.description("Lag between latest outbox creation and latest projected event occurrence")
				.register(registry);
	}
}
