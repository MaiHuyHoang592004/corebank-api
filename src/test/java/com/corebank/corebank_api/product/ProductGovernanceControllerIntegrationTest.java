package com.corebank.corebank_api.product;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.corebank.corebank_api.TestcontainersConfiguration;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.UUID;
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

	@Test
	void productGovernance_forbiddenForRegularUser() throws Exception {
		UUID productId = seedProduct();

		mockMvc.perform(post("/api/products/{productId}/versions", productId)
						.with(user("viewer").roles("USER"))
						.with(csrf())
						.contentType("application/json")
						.content("{\"versionNo\":1}"))
				.andExpect(status().isForbidden());
	}

	@Test
	void productGovernance_createActivateRetireAndListVersion() throws Exception {
		UUID productId = seedProduct();

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

	private UUID seedProduct() {
		UUID productId = UUID.randomUUID();
		jdbcTemplate.update(
				"""
				INSERT INTO bank_products (product_id, product_code, product_name, product_type, currency, status)
				VALUES (?, ?, ?, 'TERM_DEPOSIT', 'VND', 'ACTIVE')
				""",
				productId,
				"PG-" + productId.toString().substring(0, 8),
				"Governed Product");
		return productId;
	}
}
