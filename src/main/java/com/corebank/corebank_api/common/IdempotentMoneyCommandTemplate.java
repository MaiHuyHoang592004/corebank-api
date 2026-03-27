package com.corebank.corebank_api.common;

import com.corebank.corebank_api.integration.IdempotencyService;
import com.corebank.corebank_api.ops.system.SystemModeService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

@Component
public class IdempotentMoneyCommandTemplate {

	private static final Logger log = LoggerFactory.getLogger(IdempotentMoneyCommandTemplate.class);

	private final IdempotencyService idempotencyService;
	private final SystemModeService systemModeService;
	private final ObjectMapper objectMapper;
	private final TransactionTemplate transactionTemplate;

	public IdempotentMoneyCommandTemplate(
			IdempotencyService idempotencyService,
			SystemModeService systemModeService,
			ObjectMapper objectMapper,
			PlatformTransactionManager transactionManager) {
		this.idempotencyService = idempotencyService;
		this.systemModeService = systemModeService;
		this.objectMapper = objectMapper;
		this.transactionTemplate = new TransactionTemplate(transactionManager);
		this.transactionTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
	}

	public <TRequest, TResponse> TResponse execute(
			String operation,
			String idempotencyKey,
			TRequest request,
			Class<TResponse> responseType,
			Duration idempotencyTtl,
			MoneyCommandRetryPolicy retryPolicy,
			Supplier<TResponse> businessAction,
			BiConsumer<TResponse, String> beforeMarkSucceeded) {
		return execute(
				operation,
				idempotencyKey,
				request,
				responseType,
				idempotencyTtl,
				retryPolicy,
				businessAction,
				beforeMarkSucceeded,
				response -> {
				});
	}

	public <TRequest, TResponse> TResponse execute(
			String operation,
			String idempotencyKey,
			TRequest request,
			Class<TResponse> responseType,
			Duration idempotencyTtl,
			MoneyCommandRetryPolicy retryPolicy,
			Supplier<TResponse> businessAction,
			BiConsumer<TResponse, String> beforeMarkSucceeded,
			Consumer<TResponse> afterMarkSucceeded) {
		ExecutionContext<TRequest, TResponse> context = new ExecutionContext<>(
				operation,
				idempotencyKey,
				request,
				responseType,
				idempotencyTtl,
				retryPolicy,
				businessAction,
				beforeMarkSucceeded,
				afterMarkSucceeded);

		int maxAttempts = context.retryPolicy().maxAttempts();
		for (int attempt = 1; attempt <= maxAttempts; attempt++) {
			try {
				return Objects.requireNonNull(
						transactionTemplate.execute(status -> executeSingleAttempt(context)),
						context.operation() + " attempt returned null response");
			} catch (RuntimeException ex) {
				if (!context.retryPolicy().isTransient(ex) || attempt >= maxAttempts) {
					throw ex;
				}

				String sqlState = context.retryPolicy().extractSqlState(ex);
				long backoffMillis = context.retryPolicy().backoffMillisBeforeNextAttempt(attempt);
				log.warn(
						"Transient money command failure op={} idempotencyKey={} attempt={}/{} type={} sqlState={} retryInMs={}",
						context.operation(),
						context.idempotencyKey(),
						attempt,
						maxAttempts,
						ex.getClass().getSimpleName(),
						sqlState,
						backoffMillis);
				sleepBackoff(backoffMillis, context.operation());
			}
		}

		throw new CoreBankException("Money command retry attempts exhausted unexpectedly");
	}

	private <TRequest, TResponse> TResponse executeSingleAttempt(ExecutionContext<TRequest, TResponse> context) {
		String requestJson = toJson(context.request(), context.operation(), "request");
		IdempotencyService.StartResult startResult = idempotencyService.checkBeforeExecution(
				context.idempotencyKey(),
				requestJson,
				Instant.now().plus(context.idempotencyTtl()));

		if (startResult.replay()) {
			return fromJson(startResult.responseBodyJson(), context.responseType(), context.operation(), "response");
		}

		try {
			systemModeService.enforceWriteAllowed();

			TResponse response = context.businessAction().get();
			String responseJson = toJson(response, context.operation(), "response");
			context.beforeMarkSucceeded().accept(response, responseJson);

			idempotencyService.markSucceeded(
					context.idempotencyKey(),
					startResult.requestHash(),
					startResult.expiresAt(),
					responseJson);
			context.afterMarkSucceeded().accept(response);
			return response;
		} catch (RuntimeException ex) {
			idempotencyService.markFailed(context.idempotencyKey(), startResult.requestHash());
			throw ex;
		}
	}

	private void sleepBackoff(long backoffMillis, String operation) {
		try {
			Thread.sleep(backoffMillis);
		} catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
			throw new CoreBankException("Money command retry was interrupted for operation " + operation, ex);
		}
	}

	private String toJson(Object value, String operation, String payloadType) {
		try {
			return objectMapper.writeValueAsString(value);
		} catch (JsonProcessingException ex) {
			throw new IllegalStateException("Unable to serialize " + operation + " " + payloadType, ex);
		}
	}

	private <TResponse> TResponse fromJson(String json, Class<TResponse> responseType, String operation, String payloadType) {
		try {
			return objectMapper.readValue(json, responseType);
		} catch (JsonProcessingException ex) {
			throw new IllegalStateException("Unable to deserialize " + operation + " " + payloadType, ex);
		}
	}

	private record ExecutionContext<TRequest, TResponse>(
			String operation,
			String idempotencyKey,
			TRequest request,
			Class<TResponse> responseType,
			Duration idempotencyTtl,
			MoneyCommandRetryPolicy retryPolicy,
			Supplier<TResponse> businessAction,
			BiConsumer<TResponse, String> beforeMarkSucceeded,
			Consumer<TResponse> afterMarkSucceeded) {
		private ExecutionContext {
			Objects.requireNonNull(operation, "operation is required");
			Objects.requireNonNull(idempotencyKey, "idempotencyKey is required");
			Objects.requireNonNull(request, "request is required");
			Objects.requireNonNull(responseType, "responseType is required");
			Objects.requireNonNull(idempotencyTtl, "idempotencyTtl is required");
			Objects.requireNonNull(retryPolicy, "retryPolicy is required");
			Objects.requireNonNull(businessAction, "businessAction is required");
			Objects.requireNonNull(beforeMarkSucceeded, "beforeMarkSucceeded is required");
			Objects.requireNonNull(afterMarkSucceeded, "afterMarkSucceeded is required");
		}
	}
}
