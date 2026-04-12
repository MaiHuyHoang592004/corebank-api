package com.corebank.corebank_api.deposit;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.corebank.corebank_api.TestcontainersConfiguration;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = "spring.task.scheduling.enabled=false")
@AutoConfigureMockMvc
class DepositRateLimitIntegrationTest {

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private StringRedisTemplate stringRedisTemplate;

	@MockitoBean
	private DepositApplicationService depositApplicationService;

	@BeforeEach
	void setUp() {
		clearRedis();
		when(depositApplicationService.openDeposit(any())).thenReturn(new DepositApplicationService.OpenDepositResponse(
				UUID.randomUUID(),
				UUID.randomUUID(),
				5_000_000L,
				"VND",
				LocalDate.of(2026, 3, 27),
				LocalDate.of(2027, 3, 27),
				6.5,
				"ACTIVE"));
	}

	@Test
	void depositMoneyWriteEndpoint_returns429AfterWindowLimitAndIncludesHeaders() throws Exception {
		for (int attempt = 1; attempt <= 5; attempt++) {
			mockMvc.perform(post("/api/deposits/open")
						.with(user("customer-a").roles("USER"))
						.with(csrf())
						.contentType("application/json")
						.content(openDepositRequestJson()))
				.andExpect(status().isOk())
				.andExpect(header().string("X-RateLimit-Limit", "5"));
		}

		mockMvc.perform(post("/api/deposits/open")
						.with(user("customer-a").roles("USER"))
						.with(csrf())
						.contentType("application/json")
						.content(openDepositRequestJson()))
				.andExpect(status().isTooManyRequests())
				.andExpect(header().string("X-RateLimit-Limit", "5"))
				.andExpect(header().string("X-RateLimit-Remaining", "0"))
				.andExpect(header().exists("Retry-After"));
	}

	@Test
	void readOnlyReportingEndpointIsNotRateLimited() throws Exception {
		for (int attempt = 1; attempt <= 6; attempt++) {
			mockMvc.perform(get("/api/reporting/aggregate-activity")
						.with(user("reporter").roles("USER")))
				.andExpect(status().isOk());
		}
	}

	@Test
	void opsEndpointIsNotRateLimitedByDepositMoneyWritePolicy() throws Exception {
		for (int attempt = 1; attempt <= 6; attempt++) {
			mockMvc.perform(get("/api/ops/batch-runs")
						.with(user("ops-user").roles("OPS")))
				.andExpect(status().isOk());
		}
	}

	private String openDepositRequestJson() {
		return """
				{
				  "idempotencyKey": "idem-open-rate-limit",
				  "customerAccountId": "00000000-0000-0000-0000-000000000001",
				  "productId": "00000000-0000-0000-0000-000000000002",
				  "productVersionId": "00000000-0000-0000-0000-000000000003",
				  "principalAmountMinor": 5000000,
				  "currency": "VND",
				  "interestRate": 6.5,
				  "termMonths": 12,
				  "earlyClosurePenaltyRate": 1.0,
				  "autoRenew": false,
				  "debitLedgerAccountId": "00000000-0000-0000-0000-000000000004",
				  "creditLedgerAccountId": "00000000-0000-0000-0000-000000000005",
				  "actor": "customer-a",
				  "correlationId": "00000000-0000-0000-0000-000000000006",
				  "requestId": "00000000-0000-0000-0000-000000000007",
				  "sessionId": "00000000-0000-0000-0000-000000000008",
				  "traceId": "trace-rate-limit"
				}
				""";
	}

	private void clearRedis() {
		stringRedisTemplate.execute((RedisConnection connection) -> {
			connection.serverCommands().flushDb();
			return null;
		});
	}
}
