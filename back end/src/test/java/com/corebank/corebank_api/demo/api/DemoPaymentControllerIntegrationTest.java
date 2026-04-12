package com.corebank.corebank_api.demo.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.corebank.corebank_api.TestcontainersConfiguration;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Iterator;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
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
class DemoPaymentControllerIntegrationTest {

	private static final UUID SOURCE_ACCOUNT_ID = UUID.fromString("20000000-0000-0000-0000-000000000001");
	private static final UUID DESTINATION_ACCOUNT_ID = UUID.fromString("20000000-0000-0000-0000-000000000002");

	@Autowired
	private MockMvc mockMvc;

	private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

	@BeforeEach
	void setupDemoDataAsAdmin() throws Exception {
		mockMvc.perform(post("/api/demo/setup")
						.with(httpBasic("demo_admin", "demo_admin")))
				.andExpect(status().isOk());
	}

	@Test
	void demoUser_isForbiddenForSetup() throws Exception {
		mockMvc.perform(post("/api/demo/setup")
						.with(httpBasic("demo_user", "demo_user")))
				.andExpect(status().isForbidden());
	}

	@Test
	void demoUser_authorizeCaptureVoid_updatesBalancesHoldsAndActivity() throws Exception {
		JsonNode initialAccount = getDemoAccount(SOURCE_ACCOUNT_ID);
		long initialPosted = initialAccount.path("postedBalanceMinor").asLong();
		long initialAvailable = initialAccount.path("availableBalanceMinor").asLong();

		JsonNode authorizeOne = authorizeHold(500_000L);
		String firstHoldId = authorizeOne.path("holdId").asText();
		assertTrue(firstHoldId != null && !firstHoldId.isBlank());
		assertEquals(initialPosted, authorizeOne.path("postedBalanceMinor").asLong());
		assertEquals(initialAvailable, authorizeOne.path("availableBalanceBeforeMinor").asLong());
		assertEquals(initialAvailable - 500_000L, authorizeOne.path("availableBalanceAfterMinor").asLong());
		assertEquals(500_000L, authorizeOne.path("holdAmountMinor").asLong());
		assertEquals("AUTHORIZED", authorizeOne.path("status").asText());

		JsonNode holdsAfterAuthorize = getActiveHolds(SOURCE_ACCOUNT_ID);
		assertTrue(containsHold(holdsAfterAuthorize.path("items"), firstHoldId));

		JsonNode accountAfterAuthorize = getDemoAccount(SOURCE_ACCOUNT_ID);
		assertEquals(initialPosted, accountAfterAuthorize.path("postedBalanceMinor").asLong());
		assertEquals(initialAvailable - 500_000L, accountAfterAuthorize.path("availableBalanceMinor").asLong());

		JsonNode capturePartial = captureHold(firstHoldId, 200_000L);
		assertEquals(200_000L, capturePartial.path("capturedAmountMinor").asLong());
		assertEquals(300_000L, capturePartial.path("remainingAmountMinor").asLong());
		assertEquals("PARTIALLY_CAPTURED", capturePartial.path("holdStatus").asText());

		JsonNode accountAfterPartialCapture = getDemoAccount(SOURCE_ACCOUNT_ID);
		assertEquals(initialPosted - 200_000L, accountAfterPartialCapture.path("postedBalanceMinor").asLong());
		assertEquals(initialAvailable - 500_000L, accountAfterPartialCapture.path("availableBalanceMinor").asLong());

		JsonNode captureRemaining = captureHold(firstHoldId, 300_000L);
		assertEquals(300_000L, captureRemaining.path("capturedAmountMinor").asLong());
		assertEquals(0L, captureRemaining.path("remainingAmountMinor").asLong());
		assertEquals("FULLY_CAPTURED", captureRemaining.path("holdStatus").asText());

		JsonNode accountAfterFullCapture = getDemoAccount(SOURCE_ACCOUNT_ID);
		assertEquals(initialPosted - 500_000L, accountAfterFullCapture.path("postedBalanceMinor").asLong());
		assertEquals(initialAvailable - 500_000L, accountAfterFullCapture.path("availableBalanceMinor").asLong());

		JsonNode holdsAfterFullCapture = getActiveHolds(SOURCE_ACCOUNT_ID);
		assertFalse(containsHold(holdsAfterFullCapture.path("items"), firstHoldId));

		JsonNode authorizeForVoid = authorizeHold(250_000L);
		String voidHoldId = authorizeForVoid.path("holdId").asText();
		assertTrue(voidHoldId != null && !voidHoldId.isBlank());

		JsonNode accountBeforeVoid = getDemoAccount(SOURCE_ACCOUNT_ID);
		long postedBeforeVoid = accountBeforeVoid.path("postedBalanceMinor").asLong();
		long availableBeforeVoid = accountBeforeVoid.path("availableBalanceMinor").asLong();

		JsonNode voidResult = voidHold(voidHoldId);
		assertEquals(250_000L, voidResult.path("restoredAmountMinor").asLong());
		assertEquals(availableBeforeVoid, voidResult.path("availableBalanceBeforeMinor").asLong());
		assertEquals(availableBeforeVoid + 250_000L, voidResult.path("availableBalanceAfterMinor").asLong());

		JsonNode accountAfterVoid = getDemoAccount(SOURCE_ACCOUNT_ID);
		assertEquals(postedBeforeVoid, accountAfterVoid.path("postedBalanceMinor").asLong());
		assertEquals(availableBeforeVoid + 250_000L, accountAfterVoid.path("availableBalanceMinor").asLong());

		JsonNode holdsAfterVoid = getActiveHolds(SOURCE_ACCOUNT_ID);
		assertFalse(containsHold(holdsAfterVoid.path("items"), voidHoldId));

		JsonNode activity = getAccountActivity(SOURCE_ACCOUNT_ID, 0, 100);
		assertTrue(containsEventType(activity.path("items"), "HOLD_AUTHORIZED"));
		assertTrue(containsEventType(activity.path("items"), "HOLD_CAPTURED"));
		assertTrue(containsEventType(activity.path("items"), "HOLD_VOIDED"));
	}

	private JsonNode authorizeHold(long amountMajor) throws Exception {
		String body = """
				{
				  "payerAccountId": "%s",
				  "payeeAccountId": "%s",
				  "amountMajor": %d,
				  "paymentType": "INTERNAL",
				  "description": "Demo integration authorize"
				}
				""".formatted(SOURCE_ACCOUNT_ID, DESTINATION_ACCOUNT_ID, amountMajor);

		MvcResult result = mockMvc.perform(post("/api/demo/payments/authorize")
						.with(httpBasic("demo_user", "demo_user"))
						.contentType(APPLICATION_JSON)
						.content(body))
				.andExpect(status().isOk())
				.andReturn();

		return readBody(result);
	}

	private JsonNode captureHold(String holdId, long amountMajor) throws Exception {
		String body = """
				{
				  "holdId": "%s",
				  "amountMajor": %d,
				  "description": "Demo integration capture"
				}
				""".formatted(holdId, amountMajor);

		MvcResult result = mockMvc.perform(post("/api/demo/payments/capture")
						.with(httpBasic("demo_user", "demo_user"))
						.contentType(APPLICATION_JSON)
						.content(body))
				.andExpect(status().isOk())
				.andReturn();

		return readBody(result);
	}

	private JsonNode voidHold(String holdId) throws Exception {
		String body = """
				{
				  "holdId": "%s",
				  "description": "Demo integration void"
				}
				""".formatted(holdId);

		MvcResult result = mockMvc.perform(post("/api/demo/payments/void")
						.with(httpBasic("demo_user", "demo_user"))
						.contentType(APPLICATION_JSON)
						.content(body))
				.andExpect(status().isOk())
				.andReturn();

		return readBody(result);
	}

	private JsonNode getActiveHolds(UUID accountId) throws Exception {
		MvcResult result = mockMvc.perform(get("/api/demo/payments/accounts/{accountId}/holds", accountId)
						.with(httpBasic("demo_user", "demo_user"))
						.param("page", "0")
						.param("size", "100"))
				.andExpect(status().isOk())
				.andReturn();

		return readBody(result);
	}

	private JsonNode getDemoAccount(UUID accountId) throws Exception {
		MvcResult result = mockMvc.perform(get("/api/demo/accounts/{accountId}", accountId)
						.with(httpBasic("demo_user", "demo_user")))
				.andExpect(status().isOk())
				.andReturn();

		return readBody(result);
	}

	private JsonNode getAccountActivity(UUID accountId, int page, int size) throws Exception {
		MvcResult result = mockMvc.perform(get("/api/demo/accounts/{accountId}/activity", accountId)
						.with(httpBasic("demo_user", "demo_user"))
						.param("page", String.valueOf(page))
						.param("size", String.valueOf(size)))
				.andExpect(status().isOk())
				.andReturn();

		return readBody(result);
	}

	private JsonNode readBody(MvcResult result) throws Exception {
		return objectMapper.readTree(result.getResponse().getContentAsString());
	}

	private boolean containsHold(JsonNode items, String holdId) {
		if (!items.isArray()) {
			return false;
		}
		Iterator<JsonNode> it = items.elements();
		while (it.hasNext()) {
			JsonNode item = it.next();
			if (holdId.equals(item.path("holdId").asText())) {
				return true;
			}
		}
		return false;
	}

	private boolean containsEventType(JsonNode items, String eventType) {
		if (!items.isArray()) {
			return false;
		}
		Iterator<JsonNode> it = items.elements();
		while (it.hasNext()) {
			JsonNode item = it.next();
			if (eventType.equals(item.path("eventType").asText())) {
				return true;
			}
		}
		return false;
	}
}
