package com.corebank.corebank_api.product;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.corebank.corebank_api.TestcontainersConfiguration;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = "spring.task.scheduling.enabled=false")
@AutoConfigureMockMvc
class ProductGovernanceControllerIntegrationTest {

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

	@AfterEach
	void cleanupTestProducts() {
		jdbcTemplate.update(
				"""
				DELETE FROM bank_product_versions
				WHERE product_id IN (
					SELECT product_id
					FROM bank_products
					WHERE product_code LIKE 'PGT-%'
				)
				""");
		jdbcTemplate.update("DELETE FROM bank_products WHERE product_code LIKE 'PGT-%'");
	}

	@Test
	void productGovernance_forbiddenForRegularUser() throws Exception {
		UUID productId = seedProduct("PGT-FORB");

		mockMvc.perform(post("/api/products/{productId}/versions", productId)
						.with(user("viewer").roles("USER"))
						.with(csrf())
						.contentType("application/json")
						.content("{\"versionNo\":1}"))
				.andExpect(status().isForbidden());
	}

	@Test
	void productGovernance_createActivateRetireAndListVersion() throws Exception {
		UUID productId = seedProduct("PGT-LIFECYCLE");

		MvcResult createResult = mockMvc.perform(post("/api/products/{productId}/versions", productId)
						.with(user("ops").roles("OPS"))
						.with(csrf())
						.contentType("application/json")
						.content(
								"""
								{
								  "versionNo": 2,
								  "effectiveFrom": "2026-01-01T00:00:00Z",
								  "configuration": {
								    "interestRate": 6.5,
								    "termMonths": 12
								  }
								}
								"""))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.status").value("DRAFT"))
				.andExpect(jsonPath("$.versionNo").value(2))
				.andReturn();

		JsonNode created = objectMapper.readTree(createResult.getResponse().getContentAsString());
		UUID versionId = UUID.fromString(created.get("productVersionId").asText());
		assertNotNull(versionId);

		mockMvc.perform(post("/api/products/{productId}/versions/{productVersionId}/activate", productId, versionId)
						.with(user("ops").roles("OPS"))
						.with(csrf()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("ACTIVE"));

		mockMvc.perform(get("/api/products/{productId}/versions", productId)
						.with(user("ops").roles("OPS")))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.productId").value(productId.toString()))
				.andExpect(jsonPath("$.items[0].productVersionId").value(versionId.toString()));

		mockMvc.perform(post("/api/products/{productId}/versions/{productVersionId}/retire", productId, versionId)
						.with(user("ops").roles("OPS"))
						.with(csrf()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("RETIRED"));
	}

	@Test
	void governanceMissingAndBackfill_flowSupportsDryRunAndFix() throws Exception {
		UUID noVersionProduct = seedProduct("PGT-NOVER");
		UUID draftOnlyProduct = seedProduct("PGT-DRAFT");
		UUID draftVersionId = insertVersion(draftOnlyProduct, 1, "DRAFT", Instant.now().minusSeconds(3600), null);

		MvcResult reportBefore = mockMvc.perform(get("/api/products/governance/missing-active-versions")
						.param("limit", "500")
						.with(user("ops").roles("OPS")))
				.andExpect(status().isOk())
				.andReturn();

		Set<String> missingBeforeIds = extractProductIds(reportBefore.getResponse().getContentAsString());
		assertTrue(missingBeforeIds.contains(noVersionProduct.toString()));
		assertTrue(missingBeforeIds.contains(draftOnlyProduct.toString()));

		MvcResult dryRun = mockMvc.perform(post("/api/products/governance/backfill-active-versions")
						.with(user("ops").roles("OPS"))
						.with(csrf())
						.contentType("application/json")
						.content("{\"dryRun\":true,\"limit\":500}"))
				.andExpect(status().isOk())
				.andReturn();

		JsonNode dryRunJson = objectMapper.readTree(dryRun.getResponse().getContentAsString());
		assertTrue(containsActionForProduct(dryRunJson, noVersionProduct, "WOULD_FIX_CREATED"));
		assertTrue(containsActionForProduct(dryRunJson, draftOnlyProduct, "WOULD_FIX_PROMOTED"));

		assertEqualsCount("SELECT COUNT(*) FROM bank_product_versions WHERE product_id = ?", 0, noVersionProduct);
		assertEqualsCount(
				"SELECT COUNT(*) FROM bank_product_versions WHERE product_id = ? AND status = 'ACTIVE'",
				0,
				draftOnlyProduct);

		MvcResult apply = mockMvc.perform(post("/api/products/governance/backfill-active-versions")
						.with(user("ops").roles("OPS"))
						.with(csrf())
						.contentType("application/json")
						.content("{\"dryRun\":false,\"limit\":500}"))
				.andExpect(status().isOk())
				.andReturn();

		JsonNode applyJson = objectMapper.readTree(apply.getResponse().getContentAsString());
		assertTrue(containsActionForProduct(applyJson, noVersionProduct, "FIXED_CREATED"));
		assertTrue(containsActionForProduct(applyJson, draftOnlyProduct, "FIXED_PROMOTED"));

		assertEqualsCount(
				"""
				SELECT COUNT(*)
				FROM bank_product_versions
				WHERE product_id = ?
				  AND status = 'ACTIVE'
				  AND effective_from <= now()
				  AND (effective_to IS NULL OR effective_to > now())
				""",
				1,
				noVersionProduct);

		assertEqualsCount(
				"""
				SELECT COUNT(*)
				FROM bank_product_versions
				WHERE product_id = ?
				  AND status = 'ACTIVE'
				  AND effective_from <= now()
				  AND (effective_to IS NULL OR effective_to > now())
				""",
				1,
				draftOnlyProduct);

		MvcResult reportAfter = mockMvc.perform(get("/api/products/governance/missing-active-versions")
						.param("limit", "500")
						.with(user("ops").roles("OPS")))
				.andExpect(status().isOk())
				.andReturn();

		Set<String> missingAfterIds = extractProductIds(reportAfter.getResponse().getContentAsString());
		assertFalse(missingAfterIds.contains(noVersionProduct.toString()));
		assertFalse(missingAfterIds.contains(draftOnlyProduct.toString()));
		assertNotNull(draftVersionId);
	}

	@Test
	void governanceEndpoints_forbiddenForRegularUser() throws Exception {
		mockMvc.perform(get("/api/products/governance/missing-active-versions")
						.with(user("viewer").roles("USER")))
				.andExpect(status().isForbidden());

		mockMvc.perform(post("/api/products/governance/backfill-active-versions")
						.with(user("viewer").roles("USER"))
						.with(csrf())
						.contentType("application/json")
						.content("{\"dryRun\":true}"))
				.andExpect(status().isForbidden());
	}

	@Test
	void governanceEndpoints_return400ForInvalidLimit() throws Exception {
		mockMvc.perform(get("/api/products/governance/missing-active-versions")
						.param("limit", "0")
						.with(user("ops").roles("OPS")))
				.andExpect(status().isBadRequest());

		mockMvc.perform(post("/api/products/governance/backfill-active-versions")
						.with(user("ops").roles("OPS"))
						.with(csrf())
						.contentType("application/json")
						.content("{\"dryRun\":true,\"limit\":501}"))
				.andExpect(status().isBadRequest());
	}

	private UUID seedProduct(String suffix) {
		UUID productId = UUID.randomUUID();
		jdbcTemplate.update(
				"""
				INSERT INTO bank_products (product_id, product_code, product_name, product_type, currency, status)
				VALUES (?, ?, ?, 'TERM_DEPOSIT', 'VND', 'ACTIVE')
				""",
				productId,
				suffix + "-" + productId.toString().substring(0, 6),
				"Governed Product " + suffix);
		return productId;
	}

	private UUID insertVersion(UUID productId, int versionNo, String status, Instant effectiveFrom, Instant effectiveTo) {
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
				"{\"source\":\"test\"}");
		return versionId;
	}

	private void assertEqualsCount(String sql, int expected, UUID productId) {
		Integer count = jdbcTemplate.queryForObject(sql, Integer.class, productId);
		if (count == null || count != expected) {
			throw new AssertionError("Expected count=" + expected + " but got=" + count + " for productId=" + productId);
		}
	}

	private Set<String> extractProductIds(String json) throws Exception {
		JsonNode root = objectMapper.readTree(json);
		return java.util.stream.StreamSupport.stream(root.path("items").spliterator(), false)
				.map(node -> node.path("productId").asText())
				.collect(Collectors.toSet());
	}

	private boolean containsActionForProduct(JsonNode root, UUID productId, String expectedAction) {
		for (JsonNode item : root.path("items")) {
			if (productId.toString().equals(item.path("productId").asText())
					&& expectedAction.equals(item.path("action").asText())) {
				return true;
			}
		}
		return false;
	}
}
