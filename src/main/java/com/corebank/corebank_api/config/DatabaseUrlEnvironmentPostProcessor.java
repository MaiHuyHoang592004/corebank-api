package com.corebank.corebank_api.config;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

/**
 * Turns a platform-provided {@code postgres://} connection URL into the JDBC URL and credentials
 * that Spring's datasource expects.
 *
 * <p>Render and Railway both hand out {@code postgres://user:pass@host:port/db}. Spring Boot needs
 * {@code jdbc:postgresql://host:port/db} with the user and password as separate properties, so
 * without this the application boots against the local default and fails at the first query.
 *
 * <p>The URL is taken from {@code SPRING_DATASOURCE_URL} if set, otherwise from
 * {@code DATABASE_URL}. Render's blueprint writes the first; Railway's PostgreSQL service
 * publishes the second, and it is the name a Railway service conventionally uses when it
 * references the database. This used to read only {@code SPRING_DATASOURCE_URL}, so a Railway
 * deployment following that convention would have started against {@code localhost:5433}.
 *
 * <p>{@code DATABASE_URL} is checked before the resolved {@code spring.datasource.url}, not after
 * it. By the time this runs, {@code application.yml} has been loaded and supplies a local JDBC
 * default for {@code spring.datasource.url}, so that property is never blank; consulting it first
 * would always find the default and never reach {@code DATABASE_URL}.
 *
 * <p>Credentials are percent-decoded into {@code spring.datasource.username} and
 * {@code spring.datasource.password} and are no longer appended to the JDBC URL. Appending them
 * raw put a password containing {@code &}, {@code #} or {@code %} straight into a query string,
 * where the driver would cut it short or misread it.
 */
public class DatabaseUrlEnvironmentPostProcessor implements EnvironmentPostProcessor {

	static final String SPRING_DATASOURCE_URL = "SPRING_DATASOURCE_URL";
	static final String DATABASE_URL = "DATABASE_URL";

	private static final String JDBC_PREFIX = "jdbc:";
	private static final String[] POSTGRES_SCHEMES = {"postgres://", "postgresql://"};

	@Override
	public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
		Candidate candidate = resolve(environment);
		if (candidate == null) {
			failIfUrlIsBlank(environment);
			return;
		}

		Map<String, Object> overrides = new HashMap<>();

		if (candidate.url().startsWith(JDBC_PREFIX)) {
			// A JDBC URL in SPRING_DATASOURCE_URL already reaches the datasource through
			// application.yml. One in DATABASE_URL does not, so it has to be copied across.
			if (!DATABASE_URL.equals(candidate.source())) {
				return;
			}
			overrides.put("spring.datasource.url", candidate.url());
		} else {
			ParsedUrl parsed = parse(candidate.url());
			if (parsed == null) {
				return;
			}
			overrides.put("spring.datasource.url", parsed.jdbcUrl());
			if (parsed.username() != null) {
				overrides.put("spring.datasource.username", parsed.username());
			}
			if (parsed.password() != null) {
				overrides.put("spring.datasource.password", parsed.password());
			}
		}

		environment.getPropertySources().addFirst(new MapPropertySource("databaseUrlConverter", overrides));

		// The logging system is not initialised yet when environment post-processors run. The
		// JDBC URL printed here carries no credentials, so it is safe to show in platform logs,
		// and it is the first thing to check when a deployment cannot reach its database.
		System.out.println("[DatabaseUrlConverter] spring.datasource.url taken from "
				+ candidate.source() + ": " + overrides.get("spring.datasource.url"));
	}

	private Candidate resolve(ConfigurableEnvironment environment) {
		String explicit = environment.getProperty(SPRING_DATASOURCE_URL);
		if (isPresent(explicit)) {
			return new Candidate(explicit.trim(), SPRING_DATASOURCE_URL);
		}

		String platform = environment.getProperty(DATABASE_URL);
		if (isPresent(platform)) {
			return new Candidate(platform.trim(), DATABASE_URL);
		}

		// Last, a postgres:// URL that reached spring.datasource.url some other way, for example
		// through SPRING_APPLICATION_JSON. The application.yml default is JDBC and is left alone.
		String resolved = environment.getProperty("spring.datasource.url");
		if (isPresent(resolved) && !resolved.trim().startsWith(JDBC_PREFIX)) {
			return new Candidate(resolved.trim(), "spring.datasource.url");
		}

		return null;
	}

	/**
	 * Stops startup with the actual cause when {@code spring.datasource.url} is defined but empty.
	 *
	 * <p>A variable that is set to an empty string is not the same as one that is unset. An empty
	 * {@code SPRING_DATASOURCE_URL} binds straight onto {@code spring.datasource.url} and also
	 * replaces the {@code application.yml} default, so the datasource sees no URL at all. Spring
	 * then reports "Failed to determine suitable jdbc url" and suggests adding an embedded
	 * database, which points away from the cause. On a platform the empty value comes from the
	 * service's own variables: one saved with no value, or a reference such as
	 * {@code ${{Postgres.DATABASE_URL}}} that did not resolve to one.
	 *
	 * <p>Only a blank value is rejected. An unset URL is left alone, and so is an empty
	 * {@code DATABASE_URL} on its own, since the local default still applies then.
	 */
	private static void failIfUrlIsBlank(ConfigurableEnvironment environment) {
		String resolved = environment.getProperty("spring.datasource.url");
		if (resolved == null || !resolved.isBlank()) {
			return;
		}

		throw new IllegalStateException("spring.datasource.url is empty, so there is no database to connect to. "
				+ describe(environment, SPRING_DATASOURCE_URL) + "; " + describe(environment, DATABASE_URL)
				+ ". Set DATABASE_URL (or SPRING_DATASOURCE_URL) to the database's connection URL, and remove "
				+ "whichever of the two is empty. On Railway, check that a reference such as "
				+ "${{Postgres.DATABASE_URL}} names a PostgreSQL service that exists in this environment, "
				+ "spelled as the service is named.");
	}

	private static String describe(ConfigurableEnvironment environment, String name) {
		String value = environment.getProperty(name);
		if (value == null) {
			return name + " is not set";
		}
		return value.isBlank() ? name + " is set but empty" : name + " is set";
	}

	/**
	 * Converts {@code postgres://user:pass@host:port/db?query} into
	 * {@code jdbc:postgresql://host:port/db?query} plus decoded credentials. Returns {@code null}
	 * for any other scheme, which is then left untouched.
	 */
	static ParsedUrl parse(String url) {
		if (url == null) {
			return null;
		}

		String afterScheme = null;
		for (String scheme : POSTGRES_SCHEMES) {
			if (url.startsWith(scheme)) {
				afterScheme = url.substring(scheme.length());
				break;
			}
		}
		if (afterScheme == null) {
			return null;
		}

		String query = null;
		int queryStart = afterScheme.indexOf('?');
		if (queryStart >= 0) {
			query = afterScheme.substring(queryStart + 1);
			afterScheme = afterScheme.substring(0, queryStart);
		}

		// The host cannot contain '@', so the last one ends the credentials even if an
		// unencoded '@' appears inside the password.
		String username = null;
		String password = null;
		String hostPortPath = afterScheme;
		int at = afterScheme.lastIndexOf('@');
		if (at >= 0) {
			String userInfo = afterScheme.substring(0, at);
			hostPortPath = afterScheme.substring(at + 1);
			int colon = userInfo.indexOf(':');
			if (colon >= 0) {
				username = decode(userInfo.substring(0, colon));
				password = decode(userInfo.substring(colon + 1));
			} else if (!userInfo.isEmpty()) {
				username = decode(userInfo);
			}
		}

		StringBuilder jdbc = new StringBuilder("jdbc:postgresql://").append(hostPortPath);
		if (query != null && !query.isBlank()) {
			jdbc.append('?').append(query);
		}

		return new ParsedUrl(jdbc.toString(), username, password);
	}

	/**
	 * Percent-decodes URL user info. {@link URLDecoder} implements form decoding, which turns
	 * '+' into a space; in user info '+' is a literal character, so it is protected first.
	 */
	private static String decode(String value) {
		return URLDecoder.decode(value.replace("+", "%2B"), StandardCharsets.UTF_8);
	}

	private static boolean isPresent(String value) {
		return value != null && !value.isBlank();
	}

	private record Candidate(String url, String source) {
	}

	record ParsedUrl(String jdbcUrl, String username, String password) {
	}
}
