package com.corebank.corebank_api.payment.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.corebank.corebank_api.TestcontainersConfiguration;
import com.corebank.corebank_api.common.CoreBankException;
import com.corebank.corebank_api.integration.redis.RedisRateLimitService;
import com.corebank.corebank_api.payment.PaymentApplicationService;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = "spring.task.scheduling.enabled=false")
@AutoConfigureMockMvc
class PaymentControllerIntegrationTest {

	@Autowired
	private MockMvc mockMvc;

	@MockitoBean
	private PaymentApplicationService paymentApplicationService;

	@MockitoBean
	private RedisRateLimitService redisRateLimitService;

	@BeforeEach
	void setUp() {
		when(redisRateLimitService.evaluate(anyString(), anyString()))
				.thenReturn(new RedisRateLimitService.Decision(true, 5, 4, 60L, false));
		when(paymentApplicationService.authorizeHold(any())).thenReturn(new PaymentApplicationService.AuthorizeHoldResponse(
				UUID.randomUUID(),
				UUID.randomUUID(),
				UUID.fromString("20000000-0000-0000-0000-000000000001"),
				80_000_000L,
				80_000_000L,
				79_500_000L,
				500_000L,
				"VND",
				"AUTHORIZED"));
	}

	@Test
	void demoUser_canAuthorizeHold_withoutCsrf() throws Exception {
		mockMvc.perform(post("/api/payments/authorize-hold")
						.with(httpBasic("demo_user", "demo_user"))
						.contentType("application/json")
						.content(authorizeHoldRequestJson()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.currency").value("VND"))
				.andExpect(header().string("X-RateLimit-Limit", "5"));
	}

	@Test
	void authorizeHold_returnsConflictWhenServiceSignalsNonRunningMode() throws Exception {
		when(paymentApplicationService.authorizeHold(any()))
				.thenThrow(new CoreBankException("Writes are not allowed while runtime mode is MAINTENANCE"));

		mockMvc.perform(post("/api/payments/authorize-hold")
						.with(httpBasic("demo_user", "demo_user"))
						.contentType("application/json")
						.content(authorizeHoldRequestJson()))
				.andExpect(status().isConflict());
	}

	@Test
	void authorizeHold_returnsTooManyRequestsWhenRateLimitExceeded() throws Exception {
		when(redisRateLimitService.evaluate(anyString(), anyString()))
				.thenReturn(new RedisRateLimitService.Decision(false, 5, 0, 42L, false));

		mockMvc.perform(post("/api/payments/authorize-hold")
						.with(httpBasic("demo_user", "demo_user"))
						.contentType("application/json")
						.content(authorizeHoldRequestJson()))
				.andExpect(status().isTooManyRequests())
				.andExpect(header().string("X-RateLimit-Limit", "5"))
				.andExpect(header().string("X-RateLimit-Remaining", "0"))
				.andExpect(header().string("Retry-After", "42"));
	}

	private String authorizeHoldRequestJson() {
		return """
				{
				  "idempotencyKey": "demo-pay-authorize-1",
				  "payerAccountId": "20000000-0000-0000-0000-000000000001",
				  "payeeAccountId": "20000000-0000-0000-0000-000000000002",
				  "amountMinor": 500000,
				  "currency": "VND",
				  "paymentType": "CARD",
				  "description": "Demo payment hold",
				  "actor": "demo_user",
				  "correlationId": "00000000-0000-0000-0000-000000000011",
				  "requestId": "00000000-0000-0000-0000-000000000012",
				  "sessionId": "00000000-0000-0000-0000-000000000013",
				  "traceId": "trace-demo-payment"
				}
				""";
	}
}
