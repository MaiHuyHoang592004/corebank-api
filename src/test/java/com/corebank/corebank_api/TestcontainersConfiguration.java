package com.corebank.corebank_api;

import java.util.TimeZone;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Container fixtures for the integration suite.
 *
 * <p>Image tags are pinned deliberately. A floating {@code latest} makes a green suite fail later
 * with no code change when the upstream image bumps a major version, and it also meant the suite
 * was validating against a different PostgreSQL major than {@code docker-compose.yml} and
 * {@code render.yaml} deploy. These tags track the deployed versions; change them together.
 */
@TestConfiguration(proxyBeanMethods = false)
public class TestcontainersConfiguration {

	static {
		TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
	}

	@Bean
	@ServiceConnection
	PostgreSQLContainer postgresContainer() {
		return new PostgreSQLContainer(DockerImageName.parse("postgres:16-alpine"));
	}

	@Bean
	@ServiceConnection(name = "redis")
	GenericContainer<?> redisContainer() {
		return new GenericContainer<>(DockerImageName.parse("redis:7-alpine")).withExposedPorts(6379);
	}

}
