package com.corebank.corebank_api.observability;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.concurrent.atomic.AtomicLong;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Publishes the handful of numbers that say whether this system is healthy as a bank, rather than
 * healthy as a web server.
 *
 * <p>JVM heap, request rate and p99 latency are already covered by the built-in meters and say
 * nothing about whether money is being processed correctly. These five say exactly that:
 *
 * <ul>
 *   <li>a growing outbox backlog means events are written but not published, so downstream systems
 *       are drifting out of date even though every API call is returning 200
 *   <li>dead letters are events that exhausted their retries and now need a human
 *   <li>an open reconciliation break is a discrepancy between this ledger and an external statement
 *   <li>idempotency keys stuck IN_PROGRESS are commands whose outcome nobody knows
 *   <li>journal count is the throughput signal that actually corresponds to money moving
 * </ul>
 *
 * <p>The values are refreshed on a schedule and read from memory rather than queried per scrape.
 * A gauge that runs SQL on every scrape hands any party that can reach the metrics endpoint a way
 * to put load on the database, and with several scrapers the query rate multiplies.
 */
@Component
@Slf4j
public class BankingMetrics {

	private static final String OUTBOX_PENDING =
			"SELECT count(*) FROM outbox_events WHERE status IN ('PENDING', 'PROCESSING')";
	private static final String OUTBOX_DEAD_LETTERS = "SELECT count(*) FROM outbox_dead_letters";
	private static final String OPEN_BREAKS =
			"SELECT count(*) FROM reconciliation_breaks WHERE status IN ('OPEN', 'INVESTIGATING')";
	private static final String IDEMPOTENCY_IN_FLIGHT =
			"SELECT count(*) FROM idempotency_keys WHERE status = 'IN_PROGRESS'";
	private static final String JOURNALS = "SELECT count(*) FROM ledger_journals";

	private final JdbcTemplate jdbcTemplate;

	private final AtomicLong outboxPending = new AtomicLong();
	private final AtomicLong outboxDeadLetters = new AtomicLong();
	private final AtomicLong openReconciliationBreaks = new AtomicLong();
	private final AtomicLong idempotencyInFlight = new AtomicLong();
	private final AtomicLong ledgerJournals = new AtomicLong();

	public BankingMetrics(JdbcTemplate jdbcTemplate, MeterRegistry registry) {
		this.jdbcTemplate = jdbcTemplate;

		gauge(registry, "corebank.outbox.pending", outboxPending,
				"Outbox events written but not yet published. Sustained growth means the publisher "
						+ "is not keeping up or is not running at all.");
		gauge(registry, "corebank.outbox.dead_letters", outboxDeadLetters,
				"Outbox events that exhausted their retries. Every one of these needs an operator.");
		gauge(registry, "corebank.reconciliation.open_breaks", openReconciliationBreaks,
				"Unresolved discrepancies between the ledger and an external statement.");
		gauge(registry, "corebank.idempotency.in_flight", idempotencyInFlight,
				"Money commands claimed but not yet resolved. A value that never returns to zero "
						+ "means keys were stranded by a crash.");
		gauge(registry, "corebank.ledger.journals", ledgerJournals,
				"Journals posted. The rate of change is the throughput of money actually moving.");
	}

	private static void gauge(MeterRegistry registry, String name, AtomicLong source, String description) {
		Gauge.builder(name, source, AtomicLong::doubleValue)
				.description(description)
				.register(registry);
	}

	/**
	 * Refreshes every gauge from the database.
	 *
	 * <p>Failures are logged and swallowed on purpose. Metrics collection must never be able to
	 * fail a money path or a health check; a stale gauge is a much smaller problem than an
	 * application that falls over because its instrumentation could not reach the database.
	 */
	@Scheduled(fixedDelay = 15_000L, initialDelay = 5_000L)
	public void refresh() {
		update(outboxPending, OUTBOX_PENDING, "outbox pending");
		update(outboxDeadLetters, OUTBOX_DEAD_LETTERS, "outbox dead letters");
		update(openReconciliationBreaks, OPEN_BREAKS, "open reconciliation breaks");
		update(idempotencyInFlight, IDEMPOTENCY_IN_FLIGHT, "in-flight idempotency keys");
		update(ledgerJournals, JOURNALS, "ledger journals");
	}

	private void update(AtomicLong target, String sql, String label) {
		try {
			Long value = jdbcTemplate.queryForObject(sql, Long.class);
			target.set(value == null ? 0L : value);
		} catch (RuntimeException ex) {
			log.warn("Could not refresh the {} metric: {}", label, ex.getMessage());
		}
	}
}
