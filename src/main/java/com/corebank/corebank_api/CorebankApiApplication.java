package com.corebank.corebank_api;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Application entry point.
 *
 * <p>{@code @EnableScheduling} is required: without it Spring never registers
 * {@code ScheduledAnnotationBeanPostProcessor}, so every {@code @Scheduled} method in the
 * application — including {@code OutboxEventPublisher#processPendingEvents} — is silently
 * inert and the outbox never drains. See {@code SchedulingConfigurationTest}.
 */
@SpringBootApplication
@EnableScheduling
public class CorebankApiApplication {

	public static void main(String[] args) {
		SpringApplication.run(CorebankApiApplication.class, args);
	}

}
