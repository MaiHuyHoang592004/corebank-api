package com.corebank.corebank_api.lending.api;

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
import com.corebank.corebank_api.lending.LoanApplicationService;
import java.time.LocalDate;
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
class LendingControllerIntegrationTest {

	@Autowired
	private MockMvc mockMvc;

	@MockitoBean
	private LoanApplicationService loanApplicationService;

	@MockitoBean
	private RedisRateLimitService redisRateLimitService;

	@BeforeEach
	void setUp() {
		when(redisRateLimitService.evaluate(anyString(), anyString()))
				.thenReturn(new RedisRateLimitService.Decision(true, 5, 4, 60L, false));
		when(loanApplicationService.disburseLoan(any())).thenReturn(new LoanApplicationService.LoanDisbursementResponse(
				UUID.randomUUID(),
				UUID.randomUUID(),
				UUID.fromString("20000000-0000-0000-0000-000000000004"),
				4_000_000L,
				"VND",
				LocalDate.of(2026, 3, 27),
				LocalDate.of(2026, 4, 27),
				6,
				"ACTIVE"));
		when(loanApplicationService.repayLoan(any())).thenReturn(new LoanApplicationService.LoanRepaymentResponse(
				UUID.randomUUID(),
				UUID.randomUUID(),
				UUID.fromString("20000000-0000-0000-0000-000000000004"),
				1_100_000L,
				900_000L,
				200_000L,
				0L,
				3_100_000L,
				"ACTIVE",
				1));
	}

	@Test
	void demoUser_canDisburse_andRepay_withoutCsrf() throws Exception {
		mockMvc.perform(post("/api/lending/disburse")
						.with(httpBasic("demo_user", "demo_user"))
						.contentType("application/json")
						.content(disburseRequestJson()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("ACTIVE"))
				.andExpect(header().string("X-RateLimit-Limit", "5"));

		mockMvc.perform(post("/api/lending/repay")
						.with(httpBasic("demo_user", "demo_user"))
						.contentType("application/json")
						.content(repayRequestJson()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("ACTIVE"));
	}

	@Test
	void disburse_returnsConflictWhenServiceSignalsNonRunningMode() throws Exception {
		when(loanApplicationService.disburseLoan(any()))
				.thenThrow(new CoreBankException("Writes are not allowed while runtime mode is READ_ONLY"));

		mockMvc.perform(post("/api/lending/disburse")
						.with(httpBasic("demo_user", "demo_user"))
						.contentType("application/json")
						.content(disburseRequestJson()))
				.andExpect(status().isConflict());
	}

	@Test
	void disburse_returnsTooManyRequestsWhenRateLimitExceeded() throws Exception {
		when(redisRateLimitService.evaluate(anyString(), anyString()))
				.thenReturn(new RedisRateLimitService.Decision(false, 5, 0, 21L, false));

		mockMvc.perform(post("/api/lending/disburse")
						.with(httpBasic("demo_user", "demo_user"))
						.contentType("application/json")
						.content(disburseRequestJson()))
				.andExpect(status().isTooManyRequests())
				.andExpect(header().string("Retry-After", "21"));
	}

	private String disburseRequestJson() {
		return """
				{
				  "idempotencyKey": "demo-loan-disburse-1",
				  "borrowerAccountId": "20000000-0000-0000-0000-000000000004",
				  "productId": "a1b2c3d4-e5f6-7890-abcd-ef1234567804",
				  "productVersionId": "00000000-0000-0000-0000-000000000041",
				  "principalAmountMinor": 4000000,
				  "currency": "VND",
				  "annualInterestRate": 12.0,
				  "termMonths": 6,
				  "debitLedgerAccountId": "b1c2d3e4-f5a6-7890-bcde-f12345678013",
				  "creditLedgerAccountId": "b1c2d3e4-f5a6-7890-bcde-f12345678023",
				  "actor": "demo_user",
				  "correlationId": "00000000-0000-0000-0000-000000000031",
				  "requestId": "00000000-0000-0000-0000-000000000032",
				  "sessionId": "00000000-0000-0000-0000-000000000033",
				  "traceId": "trace-demo-loan-disburse"
				}
				""";
	}

	private String repayRequestJson() {
		return """
				{
				  "idempotencyKey": "demo-loan-repay-1",
				  "contractId": "00000000-0000-0000-0000-000000000042",
				  "payerAccountId": "20000000-0000-0000-0000-000000000004",
				  "amountMinor": 1100000,
				  "currency": "VND",
				  "debitLedgerAccountId": "b1c2d3e4-f5a6-7890-bcde-f12345678023",
				  "creditLedgerAccountId": "b1c2d3e4-f5a6-7890-bcde-f12345678013",
				  "actor": "demo_user",
				  "correlationId": "00000000-0000-0000-0000-000000000034",
				  "requestId": "00000000-0000-0000-0000-000000000035",
				  "sessionId": "00000000-0000-0000-0000-000000000036",
				  "traceId": "trace-demo-loan-repay"
				}
				""";
	}
}
