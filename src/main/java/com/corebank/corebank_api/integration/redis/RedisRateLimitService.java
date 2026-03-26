package com.corebank.corebank_api.integration.redis;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

@Service
public class RedisRateLimitService {

	private static final Logger log = LoggerFactory.getLogger(RedisRateLimitService.class);
	private static final String KEY_PREFIX = "corebank:rate-limit:";
	private static final Duration WINDOW = Duration.ofMinutes(1);
	private static final int MAX_REQUESTS_PER_WINDOW = 5;
	private static final long WARNING_THROTTLE_MILLIS = 60_000L;

	private final StringRedisTemplate stringRedisTemplate;
	private final AtomicLong lastWarningAt = new AtomicLong(0);

	public RedisRateLimitService(StringRedisTemplate stringRedisTemplate) {
		this.stringRedisTemplate = stringRedisTemplate;
	}

	public Decision evaluate(String subject, String route) {
		Instant now = Instant.now();
		long windowSeconds = WINDOW.toSeconds();
		long currentEpochSecond = now.getEpochSecond();
		long windowStartEpochSecond = (currentEpochSecond / windowSeconds) * windowSeconds;
		long retryAfterSeconds = Math.max(1L, (windowStartEpochSecond + windowSeconds) - currentEpochSecond);
		String key = cacheKey(subject, route, windowStartEpochSecond);

		try {
			Long requestCount = stringRedisTemplate.opsForValue().increment(key);
			if (requestCount == null) {
				return degradedAllow();
			}
			if (requestCount == 1L) {
				stringRedisTemplate.expire(key, WINDOW.plusSeconds(5));
			}
			int remaining = Math.max(0, MAX_REQUESTS_PER_WINDOW - requestCount.intValue());
			return new Decision(requestCount <= MAX_REQUESTS_PER_WINDOW, MAX_REQUESTS_PER_WINDOW, remaining, retryAfterSeconds, false);
		} catch (RuntimeException ex) {
			logWarningThrottled(subject, route, ex);
			return degradedAllow();
		}
	}

	private Decision degradedAllow() {
		return new Decision(true, null, null, null, true);
	}

	private String cacheKey(String subject, String route, long windowStartEpochSecond) {
		String normalizedSubject = subject.replace(' ', '_');
		String normalizedRoute = route.replace(' ', '_');
		return KEY_PREFIX + normalizedSubject + ":" + normalizedRoute + ":" + windowStartEpochSecond;
	}

	private void logWarningThrottled(String subject, String route, RuntimeException ex) {
		long now = System.currentTimeMillis();
		long previous = lastWarningAt.get();
		if (now - previous < WARNING_THROTTLE_MILLIS || !lastWarningAt.compareAndSet(previous, now)) {
			return;
		}
		log.warn(
				"Redis rate limiting degraded for subject={} route={}. Allowing request to proceed. type={} message={}",
				subject,
				route,
				ex.getClass().getSimpleName(),
				ex.getMessage());
	}

	public record Decision(
			boolean allowed,
			Integer limit,
			Integer remaining,
			Long retryAfterSeconds,
			boolean degraded) {
	}
}
