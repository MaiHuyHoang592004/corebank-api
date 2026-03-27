package com.corebank.corebank_api.demo.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.corebank.corebank_api.TestcontainersConfiguration;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = "spring.task.scheduling.enabled=false")
@AutoConfigureMockMvc
class DemoSetupControllerIntegrationTest {

	@Autowired
	private MockMvc mockMvc;

	private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

	@Test
	void demoAdmin_canInitializeSetup_withoutCsrf_andResultIsDeterministic() throws Exception {
		MvcResult first = mockMvc.perform(post("/api/demo/setup")
						.with(httpBasic("demo_admin", "demo_admin")))
				.andExpect(status().isOk())
				.andReturn();

		MvcResult second = mockMvc.perform(post("/api/demo/setup")
						.with(httpBasic("demo_admin", "demo_admin")))
				.andExpect(status().isOk())
				.andReturn();

		JsonNode firstBody = objectMapper.readTree(first.getResponse().getContentAsString());
		JsonNode secondBody = objectMapper.readTree(second.getResponse().getContentAsString());

		assertEquals(
				"20000000-0000-0000-0000-000000000001",
				firstBody.path("accountIds").path("sourceAccountId").asText());
		assertEquals(
				firstBody.path("accountIds").path("sourceAccountId").asText(),
				secondBody.path("accountIds").path("sourceAccountId").asText());
		assertEquals(
				firstBody.path("sampleContractIds").path("maturityReadyContractId").asText(),
				secondBody.path("sampleContractIds").path("maturityReadyContractId").asText());
		assertNotNull(firstBody.path("productIds").path("loanProductId").asText(null));
		assertNotNull(firstBody.path("productVersionIds").path("loanVersionId").asText(null));
		assertTrue(firstBody.path("note").asText().contains("PostgreSQL is authoritative truth"));
	}

	@Test
	void demoUser_isForbiddenForSetup() throws Exception {
		mockMvc.perform(post("/api/demo/setup")
						.with(httpBasic("demo_user", "demo_user")))
				.andExpect(status().isForbidden());
	}

	@Test
	void csrfRelaxation_isScoped_onlyToDemoAndShowcaseWriteEndpoints() throws Exception {
		mockMvc.perform(post("/api/ops/maintenance/idempotency/cleanup")
						.with(httpBasic("demo_admin", "demo_admin"))
						.contentType("application/json")
						.content("{\"limit\":10,\"dryRun\":true}"))
				.andExpect(status().isForbidden());
	}

	@Test
	void dashboardIndex_isPublic() throws Exception {
		mockMvc.perform(get("/dashboard/"))
				.andExpect(status().is3xxRedirection())
				.andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl("/dashboard/index.html"));
	}

	@Test
	void dashboardDocs_isPublicAndServesMarkdown() throws Exception {
		mockMvc.perform(get("/dashboard/docs/readme"))
				.andExpect(status().isOk())
				.andExpect(content().contentTypeCompatibleWith("text/markdown"));
	}
}
