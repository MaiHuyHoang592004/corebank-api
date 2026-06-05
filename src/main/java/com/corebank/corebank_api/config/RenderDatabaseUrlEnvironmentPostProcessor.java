package com.corebank.corebank_api.config;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

import java.util.HashMap;
import java.util.Map;

/**
 * Converts Render/Railway {@code postgres://} DATABASE_URL to JDBC format
 * so Spring Boot datasource auto-config works without manual URL rewriting.
 *
 * Render provides: {@code postgres://user:pass@host:5432/dbname}
 * Spring Boot needs: {@code jdbc:postgresql://host:5432/dbname}
 *
 * Registered via {@code META-INF/spring.factories} or
 * {@code META-INF/spring/org.springframework.boot.env.EnvironmentPostProcessor}.
 */
public class RenderDatabaseUrlEnvironmentPostProcessor implements EnvironmentPostProcessor {

    private static final String DATASOURCE_URL_KEY = "SPRING_DATASOURCE_URL";
    private static final String POSTGRES_SCHEME = "postgres://";
    private static final String POSTGRESQL_SCHEME = "postgresql://";
    private static final String JDBC_PREFIX = "jdbc:";

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        // Try both the env var key and the dotted Spring property key
        String rawUrl = environment.getProperty(DATASOURCE_URL_KEY);
        if (rawUrl == null || rawUrl.isBlank()) {
            rawUrl = environment.getProperty("spring.datasource.url");
        }
        // Last resort: read directly from OS env
        if (rawUrl == null || rawUrl.isBlank()) {
            rawUrl = System.getenv(DATASOURCE_URL_KEY);
        }

        if (rawUrl == null || rawUrl.isBlank()) {
            return;
        }

        // Already JDBC format — nothing to do
        if (rawUrl.startsWith(JDBC_PREFIX)) {
            return;
        }

        String jdbcUrl = convertToJdbc(rawUrl);
        if (jdbcUrl == null) {
            return;
        }

        System.out.println("[RenderDatabaseUrlConverter] Converted postgres:// URL to JDBC format");

        Map<String, Object> overrides = new HashMap<>();
        overrides.put("spring.datasource.url", jdbcUrl);

        // Also extract credentials as separate properties so that
        // application-showcase.yml username/password placeholders resolve correctly.
        Credentials creds = extractCredentials(rawUrl);
        if (creds != null) {
            overrides.put("spring.datasource.username", creds.username);
            overrides.put("spring.datasource.password", creds.password);
        }

        environment.getPropertySources().addFirst(
                new MapPropertySource("renderDatabaseUrlConverter", overrides));
    }

    private static Credentials extractCredentials(String url) {
        if (url == null) return null;
        String withoutScheme;
        if (url.startsWith(POSTGRES_SCHEME)) {
            withoutScheme = url.substring(POSTGRES_SCHEME.length());
        } else if (url.startsWith(POSTGRESQL_SCHEME)) {
            withoutScheme = url.substring(POSTGRESQL_SCHEME.length());
        } else {
            return null;
        }
        int atIdx = withoutScheme.indexOf('@');
        if (atIdx <= 0) return null;
        String creds = withoutScheme.substring(0, atIdx);
        int colonIdx = creds.indexOf(':');
        if (colonIdx <= 0) return null;
        return new Credentials(creds.substring(0, colonIdx), creds.substring(colonIdx + 1));
    }

    private record Credentials(String username, String password) {}

    /**
     * Convert {@code postgres://user:pass@host:5432/dbname}
     * to {@code jdbc:postgresql://host:5432/dbname?user=user&password=pass}.
     * Preserves existing query parameters (e.g. {@code ?sslmode=require}).
     */
    static String convertToJdbc(String url) {
        if (url == null || url.isBlank()) {
            return null;
        }

        int schemeEnd;

        if (url.startsWith(POSTGRES_SCHEME)) {
            schemeEnd = POSTGRES_SCHEME.length();
        } else if (url.startsWith(POSTGRESQL_SCHEME)) {
            schemeEnd = POSTGRESQL_SCHEME.length();
        } else {
            // Unknown scheme — leave unchanged
            return null;
        }

        String afterScheme = url.substring(schemeEnd);

        // Extract credentials: user:pass@host:port/dbname
        int atIndex = afterScheme.indexOf('@');
        String credentials = null;
        String hostPart;

        if (atIndex > 0) {
            credentials = afterScheme.substring(0, atIndex);
            hostPart = afterScheme.substring(atIndex + 1);
        } else {
            hostPart = afterScheme;
        }

        // Split hostPart into path + existing query string
        String path;
        String existingQuery = null;
        int queryIdx = hostPart.indexOf('?');
        if (queryIdx >= 0) {
            path = hostPart.substring(0, queryIdx);
            existingQuery = hostPart.substring(queryIdx + 1);
        } else {
            path = hostPart;
        }

        // Build JDBC URL
        StringBuilder jdbc = new StringBuilder(JDBC_PREFIX + POSTGRESQL_SCHEME + path);

        boolean hasParams = false;
        if (existingQuery != null && !existingQuery.isBlank()) {
            jdbc.append('?').append(existingQuery);
            hasParams = true;
        }

        if (credentials != null && credentials.contains(":")) {
            String[] parts = credentials.split(":", 2);
            jdbc.append(hasParams ? '&' : '?').append("user=").append(parts[0]);
            jdbc.append("&password=").append(parts[1]);
        }

        return jdbc.toString();
    }
}
