package com.corebank.corebank_api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.corebank.corebank_api.integration.OutboxEventPublisher;
import java.io.IOException;
import java.lang.reflect.Method;
import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * Guards two configuration facts that are invisible at compile time, are not covered by any
 * behavioural test, and each silently disable a production control when they regress.
 *
 * <p>Both were real defects found in this repository:
 *
 * <ol>
 *   <li>{@code @EnableScheduling} was missing, so the only {@code @Scheduled} method in the
 *       application never ran. The outbox never drained in a deployed instance. No test caught
 *       it because every test invokes {@code processPendingEvents()} directly.
 *   <li>Actuator exposed a single aggregate health endpoint with no liveness/readiness split,
 *       so a shared PostgreSQL or Redis blip would have reported DOWN on every replica at once
 *       and let Kubernetes restart the whole deployment.
 * </ol>
 *
 * <p>These assertions run without a Spring context, a database or Docker, so they stay fast and
 * usable as a pre-push check.
 */
@Tag("fast")
class SchedulingConfigurationTest {

	@Test
	@DisplayName("@EnableScheduling is present, otherwise every @Scheduled method is inert")
	void applicationEnablesScheduling() {
		assertNotNull(
				CorebankApiApplication.class.getAnnotation(EnableScheduling.class),
				"CorebankApiApplication must be annotated with @EnableScheduling. Without it Spring "
						+ "never registers ScheduledAnnotationBeanPostProcessor and OutboxEventPublisher"
						+ "#processPendingEvents is never called, so outbox_events grows without bound.");
	}

	@Test
	@DisplayName("the outbox publisher still carries @Scheduled")
	void outboxPublisherIsScheduled() throws NoSuchMethodException {
		Method processPendingEvents = OutboxEventPublisher.class.getMethod("processPendingEvents");
		Scheduled scheduled = processPendingEvents.getAnnotation(Scheduled.class);

		assertNotNull(scheduled, "OutboxEventPublisher#processPendingEvents must stay @Scheduled");
		assertTrue(scheduled.fixedDelay() > 0, "the outbox poller must have a positive fixed delay");
	}

	@Test
	@DisplayName("liveness consults neither the database nor Redis")
	void livenessProbeHasNoSharedDependencies() throws IOException {
		String liveness = property("management.endpoint.health.group.liveness.include");

		assertEquals(
				"livenessState",
				liveness,
				"Liveness must report only on this JVM. Including a shared dependency such as db or "
						+ "redis makes one outage restart every replica simultaneously.");
	}

	@Test
	@DisplayName("readiness gates on the database but not on optional dependencies")
	void readinessProbeIncludesDatabaseOnly() throws IOException {
		String readiness = property("management.endpoint.health.group.readiness.include");

		assertNotNull(readiness, "a readiness group must be configured");
		assertTrue(
				readiness.contains("db"),
				"every money command needs PostgreSQL, so readiness must include db");
		assertFalse(
				readiness.contains("redis"),
				"Redis is non-authoritative acceleration and degrades open; it must not gate traffic");
		assertFalse(
				readiness.contains("readModel"),
				"read-model projection lag must not remove a healthy pod from the Service");
	}

	private String property(String key) throws IOException {
		List<PropertySource<?>> sources =
				new YamlPropertySourceLoader().load("application", new ClassPathResource("application.yml"));
		assertFalse(sources.isEmpty(), "application.yml must be loadable");

		for (PropertySource<?> source : sources) {
			Object value = source.getProperty(key);
			if (value != null) {
				return String.valueOf(value);
			}
		}
		return null;
	}
}
