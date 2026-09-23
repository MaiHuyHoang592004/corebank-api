package com.corebank.corebank_api.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.mock.env.MockEnvironment;

/**
 * Guards how a deployment platform's database URL reaches the datasource.
 *
 * <p>Two failures here are silent until the first request: a URL that is not converted leaves the
 * application pointed at {@code localhost:5433}, and a post-processor that is not registered is
 * never called at all. Both are covered — the second by starting a real {@link SpringApplication}
 * with the project's own {@code application.yml}, because that is the only way to prove the
 * {@code spring.factories} entry is read by the Spring Boot version in use.
 */
@Tag("fast")
class DatabaseUrlEnvironmentPostProcessorTest {

	private static final String LOCAL_DEFAULT = "jdbc:postgresql://localhost:5433/corebank";

	private final DatabaseUrlEnvironmentPostProcessor processor = new DatabaseUrlEnvironmentPostProcessor();

	@Test
	@DisplayName("Railway: DATABASE_URL alone is converted, credentials split out of the URL")
	void databaseUrlIsUsedWhenItIsTheOnlySource() {
		MockEnvironment environment = new MockEnvironment()
				.withProperty("DATABASE_URL", "postgresql://postgres:s3cret@postgres.railway.internal:5432/railway");

		processor.postProcessEnvironment(environment, null);

		assertEquals("jdbc:postgresql://postgres.railway.internal:5432/railway",
				environment.getProperty("spring.datasource.url"));
		assertEquals("postgres", environment.getProperty("spring.datasource.username"));
		assertEquals("s3cret", environment.getProperty("spring.datasource.password"));
	}

	@Test
	@DisplayName("DATABASE_URL wins over the application.yml local default, which is never blank")
	void databaseUrlIsNotShadowedByTheLocalDefault() {
		// This is the real ordering: application.yml has already supplied a JDBC default by the
		// time the post-processor runs. Checking spring.datasource.url first would stop here.
		MockEnvironment environment = new MockEnvironment()
				.withProperty("spring.datasource.url", LOCAL_DEFAULT)
				.withProperty("DATABASE_URL", "postgres://u:p@db.internal:5432/railway");

		processor.postProcessEnvironment(environment, null);

		assertEquals("jdbc:postgresql://db.internal:5432/railway", environment.getProperty("spring.datasource.url"));
	}

	@Test
	@DisplayName("Render: SPRING_DATASOURCE_URL in postgres:// form is converted as before")
	void springDatasourceUrlIsStillConverted() {
		MockEnvironment environment = new MockEnvironment()
				.withProperty("SPRING_DATASOURCE_URL", "postgres://corebank:pw@dpg-abc/corebank");

		processor.postProcessEnvironment(environment, null);

		assertEquals("jdbc:postgresql://dpg-abc/corebank", environment.getProperty("spring.datasource.url"));
		assertEquals("corebank", environment.getProperty("spring.datasource.username"));
		assertEquals("pw", environment.getProperty("spring.datasource.password"));
	}

	@Test
	@DisplayName("SPRING_DATASOURCE_URL takes precedence when both are set")
	void explicitSpringVariableWins() {
		MockEnvironment environment = new MockEnvironment()
				.withProperty("SPRING_DATASOURCE_URL", "postgres://a:a@chosen:5432/app")
				.withProperty("DATABASE_URL", "postgres://b:b@ignored:5432/app");

		processor.postProcessEnvironment(environment, null);

		assertEquals("jdbc:postgresql://chosen:5432/app", environment.getProperty("spring.datasource.url"));
	}

	@Test
	@DisplayName("a JDBC URL in SPRING_DATASOURCE_URL is left for application.yml to apply")
	void jdbcSpringDatasourceUrlIsUntouched() {
		MockEnvironment environment = new MockEnvironment()
				.withProperty("SPRING_DATASOURCE_URL", "jdbc:postgresql://explicit:5432/app")
				.withProperty("spring.datasource.url", "jdbc:postgresql://explicit:5432/app");

		processor.postProcessEnvironment(environment, null);

		assertNull(environment.getPropertySources().get("databaseUrlConverter"));
	}

	@Test
	@DisplayName("a JDBC URL in DATABASE_URL is copied across, since nothing else maps it")
	void jdbcDatabaseUrlIsApplied() {
		MockEnvironment environment = new MockEnvironment()
				.withProperty("spring.datasource.url", LOCAL_DEFAULT)
				.withProperty("DATABASE_URL", "jdbc:postgresql://platform:5432/app");

		processor.postProcessEnvironment(environment, null);

		assertEquals("jdbc:postgresql://platform:5432/app", environment.getProperty("spring.datasource.url"));
	}

	@Test
	@DisplayName("with no platform URL the local default is left alone")
	void nothingToDoLocally() {
		MockEnvironment environment = new MockEnvironment().withProperty("spring.datasource.url", LOCAL_DEFAULT);

		processor.postProcessEnvironment(environment, null);

		assertNull(environment.getPropertySources().get("databaseUrlConverter"));
		assertEquals(LOCAL_DEFAULT, environment.getProperty("spring.datasource.url"));
	}

	@Test
	@DisplayName("credentials are percent-decoded and never end up in the JDBC query string")
	void encodedCredentialsAreDecoded() {
		DatabaseUrlEnvironmentPostProcessor.ParsedUrl parsed = DatabaseUrlEnvironmentPostProcessor.parse(
				"postgres://app%40corp:p%26ss%23w%25rd@db:5432/app?sslmode=require");

		assertEquals("jdbc:postgresql://db:5432/app?sslmode=require", parsed.jdbcUrl());
		assertEquals("app@corp", parsed.username());
		assertEquals("p&ss#w%rd", parsed.password());
	}

	@Test
	@DisplayName("a literal '+' in the password stays a '+', not a space")
	void plusIsNotFormDecoded() {
		DatabaseUrlEnvironmentPostProcessor.ParsedUrl parsed =
				DatabaseUrlEnvironmentPostProcessor.parse("postgres://u:a+b@db/app");

		assertEquals("a+b", parsed.password());
	}

	@Test
	@DisplayName("an unencoded '@' in the password does not split the host")
	void lastAtEndsTheCredentials() {
		DatabaseUrlEnvironmentPostProcessor.ParsedUrl parsed =
				DatabaseUrlEnvironmentPostProcessor.parse("postgres://u:p@ss@db:5432/app");

		assertEquals("jdbc:postgresql://db:5432/app", parsed.jdbcUrl());
		assertEquals("p@ss", parsed.password());
	}

	@Test
	@DisplayName("an unknown scheme is not converted")
	void unknownSchemeIsIgnored() {
		assertNull(DatabaseUrlEnvironmentPostProcessor.parse("mysql://u:p@db/app"));
	}

	@Configuration
	static class EmptyConfiguration {
	}

	@Test
	@DisplayName("the post-processor is registered and runs under this Spring Boot version")
	void registeredWithSpringBoot() {
		System.setProperty("DATABASE_URL", "postgresql://postgres:s3cret@postgres.railway.internal:5432/railway");
		try {
			SpringApplication application = new SpringApplication(EmptyConfiguration.class);
			application.setWebApplicationType(WebApplicationType.NONE);
			try (ConfigurableApplicationContext context = application.run()) {
				assertEquals("jdbc:postgresql://postgres.railway.internal:5432/railway",
						context.getEnvironment().getProperty("spring.datasource.url"));
				assertEquals("postgres", context.getEnvironment().getProperty("spring.datasource.username"));
			}
		} finally {
			System.clearProperty("DATABASE_URL");
		}
	}
}
