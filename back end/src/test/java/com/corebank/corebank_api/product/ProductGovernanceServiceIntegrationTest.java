package com.corebank.corebank_api.product;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.corebank.corebank_api.TestcontainersConfiguration;
import com.corebank.corebank_api.common.CoreBankException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = "spring.task.scheduling.enabled=false")
class ProductGovernanceServiceIntegrationTest {

	@Autowired
	private ProductGovernanceService productGovernanceService;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@AfterEach
	void cleanupTestProducts() {
		jdbcTemplate.update(
				"""
				DELETE FROM bank_product_versions
				WHERE product_id IN (
					SELECT product_id FROM bank_products WHERE product_code LIKE 'PGS-%'
				)
				""");
		jdbcTemplate.update("DELETE FROM bank_products WHERE product_code LIKE 'PGS-%'");
	}

	@Test
	void validateBinding_rejectsWhenProductVersionBelongsToAnotherProduct() {
		UUID productA = seedProduct("PGS-A");
		UUID productB = seedProduct("PGS-B");
		UUID versionA = seedVersion(productA, 1, "ACTIVE", Instant.now().minus(1, ChronoUnit.DAYS), null);
		UUID versionB = seedVersion(productB, 1, "ACTIVE", Instant.now().minus(1, ChronoUnit.DAYS), null);

		CoreBankException ex = assertThrows(
				CoreBankException.class,
				() -> productGovernanceService.validateProductVersionBinding(productA, versionB));
		assertTrue(ex.getMessage().contains("Product version not found for product"));
		assertTrue(versionA != null);
	}

	@Test
	void validateBinding_rejectsFutureVersion() {
		UUID productId = seedProduct("PGS-FUTURE");
		UUID futureVersion = seedVersion(productId, 1, "ACTIVE", Instant.now().plus(1, ChronoUnit.DAYS), null);

		CoreBankException ex = assertThrows(
				CoreBankException.class,
				() -> productGovernanceService.validateProductVersionBinding(productId, futureVersion));
		assertTrue(ex.getMessage().contains("not effective yet"));
	}

	@Test
	void validateBinding_rejectsExpiredVersion() {
		UUID productId = seedProduct("PGS-EXPIRED");
		UUID expiredVersion = seedVersion(
				productId,
				1,
				"ACTIVE",
				Instant.now().minus(5, ChronoUnit.DAYS),
				Instant.now().minus(1, ChronoUnit.DAYS));

		CoreBankException ex = assertThrows(
				CoreBankException.class,
				() -> productGovernanceService.validateProductVersionBinding(productId, expiredVersion));
		assertTrue(ex.getMessage().contains("expired"));
	}

	@Test
	void validateBinding_rejectsProductWithoutVersionRows() {
		UUID productId = seedProduct("PGS-NOVERSION");

		CoreBankException ex = assertThrows(
				CoreBankException.class,
				() -> productGovernanceService.validateProductVersionBinding(productId, UUID.randomUUID()));
		assertTrue(ex.getMessage().contains("Product version not found for product"));
	}

	private UUID seedProduct(String codePrefix) {
		UUID productId = UUID.randomUUID();
		jdbcTemplate.update(
				"""
				INSERT INTO bank_products (product_id, product_code, product_name, product_type, currency, status)
				VALUES (?, ?, ?, 'TERM_DEPOSIT', 'VND', 'ACTIVE')
				""",
				productId,
				codePrefix + "-" + productId.toString().substring(0, 6),
				"Product " + codePrefix);
		return productId;
	}

	private UUID seedVersion(UUID productId, int versionNo, String status, Instant effectiveFrom, Instant effectiveTo) {
		UUID versionId = UUID.randomUUID();
		jdbcTemplate.update(
				"""
				INSERT INTO bank_product_versions (
				  product_version_id,
				  product_id,
				  version_no,
				  effective_from,
				  effective_to,
				  status,
				  configuration_json,
				  created_at
				) VALUES (?, ?, ?, ?, ?, ?, CAST(? AS jsonb), now())
				""",
				versionId,
				productId,
				versionNo,
				Timestamp.from(effectiveFrom),
				effectiveTo == null ? null : Timestamp.from(effectiveTo),
				status,
				"{\"source\":\"service-test\"}");
		return versionId;
	}
}
