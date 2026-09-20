package com.corebank.corebank_api.security;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Guards that the showcase deny-list names paths that controllers actually serve.
 *
 * <p>This was a real defect. The gate denied {@code /api/ops/security/**}, a path mapped by no
 * controller in this repository, while the endpoints it was plainly written to protect —
 * {@code OpsCustomerSecretController}, which reads and writes encrypted national ids, tax ids and
 * KYC payloads — are mapped at {@code /api/ops/customers}. So in any showcase deployment the rule
 * denied something unreachable and the customer secrets fell through to
 * {@code anyRequest().authenticated()}, which {@code demo_user} satisfies.
 *
 * <p>A deny rule aimed at a path nobody serves is worse than no rule at all, because the gate is
 * visible in the configuration and reads as protection. Nothing about it fails, logs, or shows up
 * in a test: the endpoint simply answers. That is why this test compares the deny-list against the
 * controllers rather than asserting a literal, and why it is a source check rather than a request
 * test — the point is that the two halves agree, not that one particular call is refused.
 */
@Tag("fast")
class ShowcaseTokenGateTest {

	private static final Path CONFIG =
			Path.of("src/main/java/com/corebank/corebank_api/security/DemoSecurityConfig.java");
	private static final Path OPS = Path.of("src/main/java/com/corebank/corebank_api/ops");

	@Test
	@DisplayName("every path in the showcase deny-list is served by some controller")
	void denyListPathsAreReal() throws IOException {
		List<String> denied = deniedPathPrefixes();
		assertFalse(denied.isEmpty(), "no deny-list found — did the token gate move?");

		List<String> mapped = requestMappings();
		for (String path : denied) {
			assertTrue(
					mapped.stream().anyMatch(m -> m.startsWith(path) || path.startsWith(m)),
					"the showcase gate denies " + path + "/** but no controller maps it. A deny "
							+ "rule on a path nobody serves protects nothing while looking like it "
							+ "does. Mapped ops paths: " + mapped);
		}
	}

	@Test
	@DisplayName("the customer-secret endpoints are inside the showcase deny-list")
	void customerSecretsAreDenied() throws IOException {
		assertTrue(
				deniedPathPrefixes().contains("/api/ops/customers"),
				"OpsCustomerSecretController exposes encrypted national ids, tax ids and KYC "
						+ "payloads at /api/ops/customers. In a showcase deployment that endpoint "
						+ "must be denied, not merely authenticated — demo_user is authenticated.");
	}

	/** The literal path prefixes inside the {@code if (tokenGateEnabled)} deny block. */
	private static List<String> deniedPathPrefixes() throws IOException {
		String source = Files.readString(CONFIG);
		int start = source.indexOf("if (tokenGateEnabled)");
		int end = source.indexOf(".denyAll()", start);
		if (start < 0 || end < 0) {
			return List.of();
		}
		return source.substring(start, end).lines()
				.map(String::trim)
				.filter(line -> line.startsWith("\"/api/"))
				.map(line -> line.substring(1, line.indexOf('"', 1)))
				.map(path -> path.endsWith("/**") ? path.substring(0, path.length() - 3) : path)
				.toList();
	}

	/** Every {@code @RequestMapping} value declared by a controller under {@code ops/}. */
	private static List<String> requestMappings() throws IOException {
		try (var files = Files.walk(OPS)) {
			return files.filter(f -> f.toString().endsWith("Controller.java"))
					.flatMap(f -> {
						try {
							return Files.readString(f).lines();
						} catch (IOException ex) {
							throw new IllegalStateException(ex);
						}
					})
					.map(String::trim)
					.filter(line -> line.startsWith("@RequestMapping(\""))
					.map(line -> line.substring(line.indexOf('"') + 1, line.lastIndexOf('"')))
					.distinct()
					.toList();
		}
	}
}
