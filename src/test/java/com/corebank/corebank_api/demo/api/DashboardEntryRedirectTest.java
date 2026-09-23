package com.corebank.corebank_api.demo.api;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.tomcat.autoconfigure.servlet.TomcatServletWebServerAutoConfiguration;
import org.springframework.boot.webmvc.autoconfigure.DispatcherServletAutoConfiguration;
import org.springframework.boot.webmvc.autoconfigure.WebMvcAutoConfiguration;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

/**
 * The Location header a browser actually receives from the dashboard entry paths.
 *
 * <p>{@link DashboardEntryControllerTest} covers the controller through MockMvc, which never reaches
 * Tomcat, and Tomcat is what turns the redirect into an absolute URL. Behind Railway's edge that URL
 * said {@code http://}, because TLS ends at the proxy. This starts a real Tomcat with the project's
 * own {@code application.yml} and sends what the proxy forwards: plain HTTP carrying
 * {@code X-Forwarded-Proto: https}.
 */
@Tag("fast")
@SpringBootTest(classes = DashboardEntryRedirectTest.WebOnly.class,
		webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class DashboardEntryRedirectTest {

	@Configuration
	@ImportAutoConfiguration({TomcatServletWebServerAutoConfiguration.class,
			DispatcherServletAutoConfiguration.class, WebMvcAutoConfiguration.class})
	@Import(DashboardEntryController.class)
	static class WebOnly {
	}

	@LocalServerPort
	private int port;

	@ParameterizedTest
	@ValueSource(strings = {"/", "/dashboard", "/dashboard/"})
	@DisplayName("behind a TLS-terminating proxy, entry paths redirect by path and never to http://")
	void redirectIsRelative(String path) throws Exception {
		HttpClient client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();
		HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
				.header("X-Forwarded-Proto", "https")
				.GET()
				.build();

		HttpResponse<Void> response = client.send(request, HttpResponse.BodyHandlers.discarding());

		assertEquals(302, response.statusCode());
		assertEquals("/dashboard/index.html", response.headers().firstValue("Location").orElse(null));
	}
}
