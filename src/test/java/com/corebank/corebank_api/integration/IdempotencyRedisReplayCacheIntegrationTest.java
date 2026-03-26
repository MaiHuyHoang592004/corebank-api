package com.corebank.corebank_api.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.corebank.corebank_api.TestcontainersConfiguration;
import com.corebank.corebank_api.common.IdempotencyConflictException;
import com.corebank.corebank_api.integration.redis.RedisIdempotencyCacheService;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
class IdempotencyRedisReplayCacheIntegrationTest {

	@Autowired
	private IdempotencyService idempotencyService;

	@Autowired
	private RedisIdempotencyCacheService redisIdempotencyCacheService;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@Autowired
	private StringRedisTemplate stringRedisTemplate;

	@BeforeEach
	void setUp() {
		jdbcTemplate.update("DELETE FROM idempotency_keys");
		clearRedis();
	}

	@Test
	void successfulReplayIsCachedInRedisAndReturnedForSamePayload() {
		String idempotencyKey = "idem-redis-success-" + UUID.randomUUID();
		String requestPayloadJson = "{\"amountMinor\":1000,\"currency\":\"VND\"}";
		String responseBodyJson = "{\"status\":\"COMPLETED\",\"journalId\":\"journal-1\"}";
		Instant expiresAt = Instant.now().plusSeconds(3_600);

		IdempotencyService.StartResult startResult = idempotencyService.checkBeforeExecution(idempotencyKey, requestPayloadJson, expiresAt);
		assertFalse(startResult.replay());

		idempotencyService.markSucceeded(idempotencyKey, startResult.requestHash(), startResult.expiresAt(), responseBodyJson);

		assertEquals(
				responseBodyJson,
				redisIdempotencyCacheService.findSuccessReplay(idempotencyKey, startResult.requestHash()).orElseThrow());

		IdempotencyService.StartResult replayResult = idempotencyService.checkBeforeExecution(
				idempotencyKey,
				requestPayloadJson,
				Instant.now().plusSeconds(3_600));

		assertTrue(replayResult.replay());
		assertEquals(responseBodyJson, replayResult.responseBodyJson());
	}

	@Test
	void differentPayloadStillConflictsAgainstDatabaseTruthWhenSuccessReplayExists() {
		String idempotencyKey = "idem-redis-conflict-" + UUID.randomUUID();
		String originalPayload = "{\"amountMinor\":1000,\"currency\":\"VND\"}";
		String changedPayload = "{\"amountMinor\":2000,\"currency\":\"VND\"}";
		Instant expiresAt = Instant.now().plusSeconds(3_600);

		IdempotencyService.StartResult startResult = idempotencyService.checkBeforeExecution(idempotencyKey, originalPayload, expiresAt);
		idempotencyService.markSucceeded(
				idempotencyKey,
				startResult.requestHash(),
				startResult.expiresAt(),
				"{\"status\":\"COMPLETED\"}");

		assertThrows(
				IdempotencyConflictException.class,
				() -> idempotencyService.checkBeforeExecution(idempotencyKey, changedPayload, Instant.now().plusSeconds(3_600)));
	}

	@Test
	void failedExecutionDoesNotCreateSuccessReplayCache() {
		String idempotencyKey = "idem-redis-failed-" + UUID.randomUUID();
		String requestPayloadJson = "{\"amountMinor\":3000,\"currency\":\"VND\"}";
		Instant expiresAt = Instant.now().plusSeconds(3_600);

		IdempotencyService.StartResult startResult = idempotencyService.checkBeforeExecution(idempotencyKey, requestPayloadJson, expiresAt);
		idempotencyService.markFailed(idempotencyKey, startResult.requestHash());

		assertTrue(redisIdempotencyCacheService.findSuccessReplay(idempotencyKey, startResult.requestHash()).isEmpty());
	}

	private void clearRedis() {
		stringRedisTemplate.execute((RedisConnection connection) -> {
			connection.serverCommands().flushDb();
			return null;
		});
	}
}
