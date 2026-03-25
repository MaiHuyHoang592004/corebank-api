package com.corebank.corebank_api.ops.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.corebank.corebank_api.TestcontainersConfiguration;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = {
		"spring.task.scheduling.enabled=false",
		"corebank.security.master-key-b64=MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY="
})
@AutoConfigureMockMvc
class OpsCustomerSecretIntegrationTest {

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

	@BeforeEach
	void setUp() {
		jdbcTemplate.update("DELETE FROM encrypted_customer_secrets");
		jdbcTemplate.update("DELETE FROM customers");
	}

	@Test
	void upsertSecret_requiresOpsOrAdminRole() throws Exception {
		UUID customerId = seedCustomer("auth");

		mockMvc.perform(put("/api/ops/customers/{customerId}/secrets/{secretType}", customerId, "NATIONAL_ID")
						.with(user("normal-user").roles("USER"))
						.with(csrf())
						.contentType("application/json")
						.content("{\"plainText\":\"ID-123\"}"))
				.andExpect(status().isForbidden());
	}

	@Test
	void upsertSecret_persistsCiphertextAndWritesAuditWithoutPlaintext() throws Exception {
		UUID customerId = seedCustomer("persist");
		String plainText = "ID-99887766";

		mockMvc.perform(put("/api/ops/customers/{customerId}/secrets/{secretType}", customerId, "NATIONAL_ID")
						.with(user("ops").roles("OPS"))
						.with(csrf())
						.contentType("application/json")
						.content("{\"plainText\":\"" + plainText + "\"}"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.secretType").value("NATIONAL_ID"))
				.andExpect(jsonPath("$.keyVersion").value(1))
				.andExpect(jsonPath("$.encryptionAlgorithm").value("AES/GCM/NoPadding"))
				.andExpect(jsonPath("$.createdAt").exists());

		Map<String, Object> row = jdbcTemplate.queryForMap(
				"""
				SELECT cipher_text, nonce, key_version, encryption_algorithm
				FROM encrypted_customer_secrets
				WHERE customer_id = ? AND secret_type = 'NATIONAL_ID'
				""",
				customerId);

		byte[] cipherText = (byte[]) row.get("cipher_text");
		byte[] nonce = (byte[]) row.get("nonce");
		assertNotNull(cipherText);
		assertNotNull(nonce);
		assertTrue(cipherText.length > 0);
		assertEquals(12, nonce.length);
		assertEquals(1, ((Number) row.get("key_version")).intValue());
		assertEquals("AES/GCM/NoPadding", row.get("encryption_algorithm"));
		assertFalse(Arrays.equals(cipherText, plainText.getBytes(StandardCharsets.UTF_8)));

		Map<String, Object> auditRow = jdbcTemplate.queryForMap(
				"""
				SELECT resource_id, after_state_json::text AS after_json
				FROM audit_events
				WHERE action = 'CUSTOMER_SECRET_UPSERTED'
				ORDER BY created_at DESC
				LIMIT 1
				""");
		assertEquals(customerId + ":NATIONAL_ID", auditRow.get("resource_id"));
		String afterJson = String.valueOf(auditRow.get("after_json"));
		JsonNode afterNode = objectMapper.readTree(afterJson);
		assertEquals("NATIONAL_ID", afterNode.path("secretType").asText());
		assertTrue(afterNode.path("cipherLength").asInt() > 0);
		assertFalse(afterJson.contains(plainText));
	}

	@Test
	void upsertSecret_sameSecretTypeUsesSingleRowUpsert() throws Exception {
		UUID customerId = seedCustomer("upsert");

		upsertAsOps(customerId, "PHONE", "0901234567");
		upsertAsOps(customerId, "PHONE", "0907654321");

		Integer count = jdbcTemplate.queryForObject(
				"""
				SELECT COUNT(*)
				FROM encrypted_customer_secrets
				WHERE customer_id = ? AND secret_type = 'PHONE'
				""",
				Integer.class,
				customerId);
		assertEquals(1, count);
	}

	@Test
	void upsertSecret_validatesSecretTypeAndPayload() throws Exception {
		UUID customerId = seedCustomer("validation");

		mockMvc.perform(put("/api/ops/customers/{customerId}/secrets/{secretType}", customerId, "INVALID_TYPE")
						.with(user("ops").roles("OPS"))
						.with(csrf())
						.contentType("application/json")
						.content("{\"plainText\":\"abc\"}"))
				.andExpect(status().isBadRequest());

		mockMvc.perform(put("/api/ops/customers/{customerId}/secrets/{secretType}", customerId, "EMAIL")
						.with(user("ops").roles("OPS"))
						.with(csrf())
						.contentType("application/json")
						.content("{\"plainText\":\"   \"}"))
				.andExpect(status().isBadRequest());

		String tooLong = "x".repeat(2049);
		mockMvc.perform(put("/api/ops/customers/{customerId}/secrets/{secretType}", customerId, "EMAIL")
						.with(user("ops").roles("OPS"))
						.with(csrf())
						.contentType("application/json")
						.content("{\"plainText\":\"" + tooLong + "\"}"))
				.andExpect(status().isBadRequest());
	}

	@Test
	void upsertSecret_returnsNotFoundForUnknownCustomer() throws Exception {
		UUID unknownCustomerId = UUID.randomUUID();

		mockMvc.perform(put("/api/ops/customers/{customerId}/secrets/{secretType}", unknownCustomerId, "TAX_ID")
						.with(user("ops").roles("OPS"))
						.with(csrf())
						.contentType("application/json")
						.content("{\"plainText\":\"123456789\"}"))
				.andExpect(status().isNotFound());
	}

	@Test
	void listSecrets_returnsMetadataOnly() throws Exception {
		UUID customerId = seedCustomer("list");
		upsertAsOps(customerId, "NATIONAL_ID", "ID-111");
		upsertAsOps(customerId, "PHONE", "0900000111");

		MvcResult result = mockMvc.perform(get("/api/ops/customers/{customerId}/secrets", customerId)
						.with(user("ops").roles("OPS")))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.customerId").value(customerId.toString()))
				.andExpect(jsonPath("$.items.length()").value(2))
				.andExpect(jsonPath("$.items[0].secretType").exists())
				.andExpect(jsonPath("$.items[0].keyVersion").exists())
				.andExpect(jsonPath("$.items[0].encryptionAlgorithm").exists())
				.andReturn();

		String responseBody = result.getResponse().getContentAsString();
		assertFalse(responseBody.contains("plainText"));
		assertFalse(responseBody.contains("cipher_text"));
		assertFalse(responseBody.contains("cipherText"));
	}

	private void upsertAsOps(UUID customerId, String secretType, String plainText) throws Exception {
		mockMvc.perform(put("/api/ops/customers/{customerId}/secrets/{secretType}", customerId, secretType)
						.with(user("ops").roles("OPS"))
						.with(csrf())
						.contentType("application/json")
						.content("{\"plainText\":\"" + plainText + "\"}"))
				.andExpect(status().isOk());
	}

	private UUID seedCustomer(String suffix) {
		UUID customerId = UUID.randomUUID();
		jdbcTemplate.update(
				"""
				INSERT INTO customers (
				    customer_id,
				    customer_type,
				    full_name,
				    email,
				    phone,
				    status,
				    risk_band
				) VALUES (?, 'INDIVIDUAL', ?, ?, ?, 'ACTIVE', 'LOW')
				""",
				customerId,
				"Customer " + suffix,
				"customer-" + suffix + "@example.com",
				"0900000" + suffix.hashCode());
		return customerId;
	}
}
