package com.corebank.corebank_api.reporting;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.ToDoubleFunction;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Registers operational gauges for read-model projection.
 *
 * <p>Values come from a snapshot refreshed on a schedule rather than from a query per scrape. Each
 * of these four gauges previously ran its own SQL every time the metrics endpoint was read, so a
 * single scrape cost four queries and several scrapers multiplied that against the same database
 * the money paths use.
 */
@Component
@Slf4j
public class ReadModelMetricsBinder implements MeterBinder {

	private final ReadModelHealthService readModelHealthService;
	private final AtomicReference<ReadModelHealthService.ReadModelHealthSnapshot> snapshot =
			new AtomicReference<>();

	public ReadModelMetricsBinder(ReadModelHealthService readModelHealthService) {
		this.readModelHealthService = readModelHealthService;
	}

	@Override
	public void bindTo(MeterRegistry registry) {
		gauge(registry, "corebank.read_model.feed.count",
				"Number of projected event-feed rows",
				s -> s.feedCount());

		gauge(registry, "corebank.read_model.summary.count",
				"Number of typed read-model summary rows",
				s -> s.summaryCount());

		gauge(registry, "corebank.read_model.outbox.pending.count",
				"Outbox rows waiting for projector visibility",
				s -> s.pendingOutboxCount());

		gauge(registry, "corebank.read_model.projection.lag.seconds",
				"Lag between latest outbox creation and latest projected event occurrence",
				ReadModelMetricsBinder::lagSeconds);
	}

	/**
	 * Projection lag, with "nothing has been projected yet" reported as absent rather than as a
	 * number.
	 *
	 * <p>{@code ReadModelHealthService} returns {@code Long.MAX_VALUE} for that state, which is the
	 * right sentinel for a boolean health decision and a terrible value for a time series: exported
	 * verbatim it becomes 9.2e18, which flattens every dashboard axis it shares and holds any
	 * "lag above threshold" alert permanently firing. NaN is how Prometheus spells "no value here",
	 * so the series simply has a gap until the projector produces something.
	 */
	private static double lagSeconds(ReadModelHealthService.ReadModelHealthSnapshot snapshot) {
		long lag = snapshot.lagSeconds();
		return lag == Long.MAX_VALUE ? Double.NaN : (double) lag;
	}

	private void gauge(
			MeterRegistry registry,
			String name,
			String description,
			ToDoubleFunction<ReadModelHealthService.ReadModelHealthSnapshot> reader) {

		Gauge.builder(name, this, binder -> {
					ReadModelHealthService.ReadModelHealthSnapshot current = binder.snapshot.get();
					return current == null ? Double.NaN : reader.applyAsDouble(current);
				})
				.description(description)
				.register(registry);
	}

	/**
	 * Refreshes the snapshot. Failures are logged and swallowed: a stale or absent gauge is a far
	 * smaller problem than instrumentation that can take the application down with it.
	 */
	@Scheduled(fixedDelay = 15_000L, initialDelay = 5_000L)
	public void refresh() {
		try {
			snapshot.set(readModelHealthService.snapshot());
		} catch (RuntimeException ex) {
			log.warn("Could not refresh read-model metrics: {}", ex.getMessage());
		}
	}
}
