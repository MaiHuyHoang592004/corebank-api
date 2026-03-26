package com.corebank.corebank_api.integration.redis;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

@Service
public class RedisIdempotencyCacheService {

	private static final Logger log = LoggerFactory.getLogger(RedisIdempotencyCacheService.class);
	private static final String KEY_PREFIX = "corebank:idempotency:success:";
	private static final long WARNING_THROTTLE_MILLIS = 60_000L;

	private final StringRedisTemplate stringRedisTemplate;
	private final AtomicLong lastWarningAt = new AtomicLong(0);

	public RedisIdempotencyCacheService(StringRedisTemplate stringRedisTemplate) {
		this.stringRedisTemplate = stringRedisTemplate;
	}

	public Optional<String> findSuccessReplay(String idempotencyKey, String requestHash) {
		try {
			String cachedResponse = stringRedisTemplate.opsForValue().get(cacheKey(idempotencyKey, requestHash));
			return Optional.ofNullable(cachedResponse);
		} catch (RuntimeException ex) {
			logWarningThrottled("lookup", ex);
			return Optional.empty();
		}
	}

	public void cacheSuccessReplay(String idempotencyKey, String requestHash, String responseBodyJson, Instant expiresAt) {
		if (responseBodyJson == null || expiresAt == null) {
			return;
		}

		Duration ttl = Duration.between(Instant.now(), expiresAt);
		if (ttl.isZero() || ttl.isNegative()) {
			return;
		}

		try {
			stringRedisTemplate.opsForValue().set(cacheKey(idempotencyKey, requestHash), responseBodyJson, ttl);
		} catch (RuntimeException ex) {
			logWarningThrottled("store", ex);
		}
	}

	private String cacheKey(String idempotencyKey, String requestHash) {
		return KEY_PREFIX + idempotencyKey + ":" + requestHash;
	}

	private void logWarningThrottled(String operation, RuntimeException ex) {
		long now = System.currentTimeMillis();
		long previous = lastWarningAt.get();
		if (now - previous < WARNING_THROTTLE_MILLIS || !lastWarningAt.compareAndSet(previous, now)) {
			return;
		}
		log.warn(
				"Redis idempotency cache degraded during {}. Falling back to PostgreSQL truth. type={} message={}",
				operation,
				ex.getClass().getSimpleName(),
				ex.getMessage());
	}
}
