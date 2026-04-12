package com.corebank.corebank_api.transfer.api;

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
import com.corebank.corebank_api.transfer.TransferService;
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
class TransferControllerIntegrationTest {

	@Autowired
	private MockMvc mockMvc;

	@MockitoBean
	private TransferService transferService;

	@MockitoBean
	private RedisRateLimitService redisRateLimitService;

	@BeforeEach
	void setUp() {
		when(redisRateLimitService.evaluate(anyString(), anyString()))
				.thenReturn(new RedisRateLimitService.Decision(true, 5, 4, 60L, false));
		when(transferService.transfer(any())).thenReturn(new TransferService.TransferResponse(
				UUID.randomUUID(),
				UUID.fromString("20000000-0000-0000-0000-000000000001"),
				UUID.fromString("20000000-0000-0000-0000-000000000002"),
				700_000L,
				"VND",
				79_300_000L,
				79_300_000L,
				78_600_000L,
				25_700_000L,
				25_700_000L,
				26_400_000L,
				"COMPLETED"));
	}

	@Test
	void demoUser_canTransfer_withoutCsrf() throws Exception {
		mockMvc.perform(post("/api/transfers/internal")
						.with(httpBasic("demo_user", "demo_user"))
						.contentType("application/json")
						.content(transferRequestJson()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("COMPLETED"))
				.andExpect(header().string("X-RateLimit-Limit", "5"));
	}

	@Test
	void transfer_returnsConflictWhenServiceSignalsNonRunningMode() throws Exception {
		when(transferService.transfer(any()))
				.thenThrow(new CoreBankException("Writes are not allowed while runtime mode is EOD_LOCK"));

		mockMvc.perform(post("/api/transfers/internal")
						.with(httpBasic("demo_user", "demo_user"))
						.contentType("application/json")
						.content(transferRequestJson()))
				.andExpect(status().isConflict());
	}

	@Test
	void transfer_returnsTooManyRequestsWhenRateLimitExceeded() throws Exception {
		when(redisRateLimitService.evaluate(anyString(), anyString()))
				.thenReturn(new RedisRateLimitService.Decision(false, 5, 0, 18L, false));

		mockMvc.perform(post("/api/transfers/internal")
						.with(httpBasic("demo_user", "demo_user"))
						.contentType("application/json")
						.content(transferRequestJson()))
				.andExpect(status().isTooManyRequests())
				.andExpect(header().string("Retry-After", "18"));
	}

	private String transferRequestJson() {
		return """
				{
				  "idempotencyKey": "demo-transfer-1",
				  "sourceAccountId": "20000000-0000-0000-0000-000000000001",
				  "destinationAccountId": "20000000-0000-0000-0000-000000000002",
				  "amountMinor": 700000,
				  "currency": "VND",
				  "debitLedgerAccountId": "b1c2d3e4-f5a6-7890-bcde-f12345678021",
				  "creditLedgerAccountId": "b1c2d3e4-f5a6-7890-bcde-f12345678021",
				  "description": "Demo transfer",
				  "actor": "demo_user",
				  "correlationId": "00000000-0000-0000-0000-000000000021",
				  "requestId": "00000000-0000-0000-0000-000000000022",
				  "sessionId": "00000000-0000-0000-0000-000000000023",
				  "traceId": "trace-demo-transfer"
				}
				""";
	}
}
