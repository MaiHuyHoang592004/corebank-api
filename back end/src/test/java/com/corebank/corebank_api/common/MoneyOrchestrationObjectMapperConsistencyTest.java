package com.corebank.corebank_api.common;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

class MoneyOrchestrationObjectMapperConsistencyTest {

	private static final Pattern NEW_OBJECT_MAPPER_PATTERN = Pattern.compile("\\bnew\\s+ObjectMapper\\s*\\(");
	private static final Pattern COPY_AND_REGISTER_PATTERN = Pattern.compile("\\.copy\\s*\\(\\)\\s*\\.findAndRegisterModules\\s*\\(");

	@Test
	void moneyOrchestrationServices_shouldUseSharedObjectMapperInjection() throws IOException {
		Path sourceRoot = Path.of("src", "main", "java", "com", "corebank", "corebank_api");
		List<Path> serviceFiles = List.of(
				sourceRoot.resolve("deposit").resolve("AccrualService.java"),
				sourceRoot.resolve("deposit").resolve("DepositContractService.java"),
				sourceRoot.resolve("lending").resolve("LoanContractService.java"),
				sourceRoot.resolve("integration").resolve("OutboxEventPublisher.java"),
				sourceRoot.resolve("notification").resolve("NotificationProjector.java"),
				sourceRoot.resolve("reporting").resolve("ReadModelProjector.java"),
				sourceRoot.resolve("ops").resolve("security").resolve("CustomerSecretService.java"));

		List<String> violations = new ArrayList<>();
		for (Path file : serviceFiles) {
			String content = Files.readString(file);
			if (NEW_OBJECT_MAPPER_PATTERN.matcher(content).find()) {
				boolean allowedTestConstructorFallback = file.endsWith("OutboxEventPublisher.java")
						&& content.contains("this(outboxEventRepository, kafkaTemplate, new ObjectMapper().findAndRegisterModules())");
				if (!allowedTestConstructorFallback) {
					violations.add(file + " contains direct new ObjectMapper()");
				}
			}
			if (COPY_AND_REGISTER_PATTERN.matcher(content).find()) {
				violations.add(file + " contains objectMapper.copy().findAndRegisterModules()");
			}
		}

		assertTrue(violations.isEmpty(), String.join(System.lineSeparator(), violations));
	}
}