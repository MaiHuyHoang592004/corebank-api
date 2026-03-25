package com.corebank.corebank_api.ops.security;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.corebank.corebank_api.TestcontainersConfiguration;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = {
		"spring.task.scheduling.enabled=false",
		"corebank.security.master-key-b64="
})
@AutoConfigureMockMvc
class OpsCustomerSecretMissingKeyIntegrationTest {

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@BeforeEach
	void setUp() {
		jdbcTemplate.update("DELETE FROM encrypted_customer_secrets");
		jdbcTemplate.update("DELETE FROM customers");
	}

	@Test
	void upsertSecret_returnsServiceUnavailableWhenEncryptionKeyMissing() throws Exception {
		UUID customerId = seedCustomer();

		mockMvc.perform(put("/api/ops/customers/{customerId}/secrets/{secretType}", customerId, "EMAIL")
						.with(user("ops").roles("OPS"))
						.with(csrf())
						.contentType("application/json")
						.content("{\"plainText\":\"missing-key@example.com\"}"))
				.andExpect(status().isServiceUnavailable());
	}

	private UUID seedCustomer() {
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
				"Customer Missing Key",
				"customer-missing-key@example.com",
				"0900000123");
		return customerId;
	}
}
