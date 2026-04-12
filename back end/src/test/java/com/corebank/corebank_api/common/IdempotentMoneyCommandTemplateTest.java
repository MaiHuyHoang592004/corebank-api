package com.corebank.corebank_api.common;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.corebank.corebank_api.integration.IdempotencyService;
import com.corebank.corebank_api.ops.system.SystemModeService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;

@ExtendWith(MockitoExtension.class)
class IdempotentMoneyCommandTemplateTest {

	@Mock
	private IdempotencyService idempotencyService;

	@Mock
	private SystemModeService systemModeService;

	private IdempotentMoneyCommandTemplate template;
	private final StubTransactionManager transactionManager = new StubTransactionManager();
	private final ObjectMapper objectMapper = new ObjectMapper();
	private final StubRetryPolicy retryPolicy = new StubRetryPolicy();

	@BeforeEach
	void setUp() {
		template = new IdempotentMoneyCommandTemplate(
				idempotencyService,
				systemModeService,
				objectMapper,
				transactionManager);
	}

	@Test
	void execute_replaysCachedResponseWithoutBusinessExecution() throws Exception {
		String replayJson = objectMapper.writeValueAsString(new TestResponse("REPLAY"));
		when(idempotencyService.checkBeforeExecution(any(), any(), any()))
				.thenReturn(IdempotencyService.StartResult.replay(replayJson));

		TestResponse result = template.execute(
				"testReplay",
				"idem-1",
				new TestRequest("payload"),
				TestResponse.class,
				Duration.ofMinutes(1),
				retryPolicy,
				() -> {
					throw new IllegalStateException("business should not run on replay");
				},
				(response, responseJson) -> {
					throw new IllegalStateException("side effects should not run on replay");
				});

		assertEquals("REPLAY", result.value());
		verify(systemModeService, never()).enforceWriteAllowed();
		verify(idempotencyService, never()).markSucceeded(any(), any(), any(), any());
		verify(idempotencyService, never()).markFailed(any(), any());
	}

	@Test
	void execute_successPathMarksSucceededWithDeterministicOrder() {
		Instant expiresAt = Instant.now().plus(Duration.ofMinutes(5));
		when(idempotencyService.checkBeforeExecution(eq("idem-2"), any(), any()))
				.thenReturn(IdempotencyService.StartResult.started("hash-2", expiresAt));

		@SuppressWarnings("unchecked")
		BiConsumer<TestResponse, String> beforeHook = mock(BiConsumer.class);
		@SuppressWarnings("unchecked")
		Consumer<TestResponse> afterHook = mock(Consumer.class);
		Supplier<TestResponse> businessAction = () -> new TestResponse("SUCCESS");

		TestResponse result = template.execute(
				"testSuccess",
				"idem-2",
				new TestRequest("payload"),
				TestResponse.class,
				Duration.ofMinutes(5),
				retryPolicy,
				businessAction,
				beforeHook,
				afterHook);

		assertEquals("SUCCESS", result.value());

		InOrder inOrder = inOrder(idempotencyService, systemModeService, beforeHook, afterHook);
		inOrder.verify(idempotencyService).checkBeforeExecution(eq("idem-2"), any(), any());
		inOrder.verify(systemModeService).enforceWriteAllowed();
		inOrder.verify(beforeHook).accept(eq(result), any());
		inOrder.verify(idempotencyService).markSucceeded(eq("idem-2"), eq("hash-2"), eq(expiresAt), any());
		inOrder.verify(afterHook).accept(eq(result));

		verify(idempotencyService, never()).markFailed(any(), any());
	}

	@Test
	void execute_nonTransientFailureMarksFailedAndThrows() {
		Instant expiresAt = Instant.now().plus(Duration.ofMinutes(5));
		when(idempotencyService.checkBeforeExecution(eq("idem-3"), any(), any()))
				.thenReturn(IdempotencyService.StartResult.started("hash-3", expiresAt));
		retryPolicy.transientFailure = false;

		CoreBankException exception = assertThrows(
				CoreBankException.class,
				() -> template.execute(
						"testFailure",
						"idem-3",
						new TestRequest("payload"),
						TestResponse.class,
						Duration.ofMinutes(5),
						retryPolicy,
						() -> {
							throw new CoreBankException("non-transient");
						},
						(response, responseJson) -> {
						}));

		assertEquals("non-transient", exception.getMessage());
		verify(idempotencyService).markFailed("idem-3", "hash-3");
		verify(idempotencyService, never()).markSucceeded(any(), any(), any(), any());
	}

	@Test
	void execute_transientFailureRetriesAndEventuallySucceeds() {
		Instant expiresAt = Instant.now().plus(Duration.ofMinutes(5));
		when(idempotencyService.checkBeforeExecution(eq("idem-4"), any(), any()))
				.thenReturn(
						IdempotencyService.StartResult.started("hash-4", expiresAt),
						IdempotencyService.StartResult.started("hash-4", expiresAt));
		retryPolicy.transientFailure = true;

		AtomicInteger attempts = new AtomicInteger(0);
		TestResponse result = template.execute(
				"testRetry",
				"idem-4",
				new TestRequest("payload"),
				TestResponse.class,
				Duration.ofMinutes(5),
				retryPolicy,
				() -> {
					if (attempts.getAndIncrement() == 0) {
						throw new CoreBankException("transient");
					}
					return new TestResponse("RECOVERED");
				},
				(response, responseJson) -> {
				});

		assertEquals("RECOVERED", result.value());
		assertEquals(2, attempts.get());
		verify(idempotencyService, times(2)).checkBeforeExecution(eq("idem-4"), any(), any());
		verify(idempotencyService).markFailed("idem-4", "hash-4");
		verify(idempotencyService).markSucceeded(eq("idem-4"), eq("hash-4"), eq(expiresAt), any());
	}

	private record TestRequest(String value) {
	}

	private record TestResponse(String value) {
	}

	private static final class StubRetryPolicy implements MoneyCommandRetryPolicy {
		private boolean transientFailure;

		@Override
		public int maxAttempts() {
			return 3;
		}

		@Override
		public long backoffMillisBeforeNextAttempt(int failedAttempt) {
			return 0L;
		}

		@Override
		public boolean isTransient(Throwable throwable) {
			return transientFailure;
		}

		@Override
		public String extractSqlState(Throwable throwable) {
			return "40001";
		}
	}

	private static final class StubTransactionManager implements PlatformTransactionManager {
		@Override
		public TransactionStatus getTransaction(TransactionDefinition definition) {
			return new SimpleTransactionStatus();
		}

		@Override
		public void commit(TransactionStatus status) {
		}

		@Override
		public void rollback(TransactionStatus status) {
		}
	}
}
