package com.corebank.corebank_api.integration.redis;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.security.Principal;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

@Component
public class MoneyWriteRateLimitInterceptor implements HandlerInterceptor {

	private final RedisRateLimitService redisRateLimitService;

	public MoneyWriteRateLimitInterceptor(RedisRateLimitService redisRateLimitService) {
		this.redisRateLimitService = redisRateLimitService;
	}

	@Override
	public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws IOException {
		RedisRateLimitService.Decision decision = redisRateLimitService.evaluate(resolveSubject(request), request.getRequestURI());
		applySuccessHeaders(response, decision);
		if (decision.allowed()) {
			return true;
		}

		applyRejectedHeaders(response, decision);
		response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
		response.setContentType(MediaType.APPLICATION_JSON_VALUE);
		response.getWriter().write("{\"message\":\"Rate limit exceeded\"}");
		return false;
	}

	private void applySuccessHeaders(HttpServletResponse response, RedisRateLimitService.Decision decision) {
		if (decision.degraded() || decision.limit() == null || decision.remaining() == null) {
			return;
		}
		response.setHeader("X-RateLimit-Limit", String.valueOf(decision.limit()));
		response.setHeader("X-RateLimit-Remaining", String.valueOf(decision.remaining()));
	}

	private void applyRejectedHeaders(HttpServletResponse response, RedisRateLimitService.Decision decision) {
		if (decision.limit() != null) {
			response.setHeader("X-RateLimit-Limit", String.valueOf(decision.limit()));
		}
		response.setHeader("X-RateLimit-Remaining", "0");
		if (decision.retryAfterSeconds() != null) {
			response.setHeader("Retry-After", String.valueOf(decision.retryAfterSeconds()));
		}
	}

	private String resolveSubject(HttpServletRequest request) {
		Principal principal = request.getUserPrincipal();
		if (principal != null && principal.getName() != null && !principal.getName().isBlank()) {
			return "principal:" + principal.getName();
		}
		String forwardedFor = request.getHeader("X-Forwarded-For");
		if (forwardedFor != null && !forwardedFor.isBlank()) {
			String firstIp = forwardedFor.split(",")[0].trim();
			if (!firstIp.isBlank()) {
				return "ip:" + firstIp;
			}
		}
		String remoteAddr = request.getRemoteAddr();
		if (remoteAddr != null && !remoteAddr.isBlank()) {
			return "ip:" + remoteAddr;
		}
		return "ip:unknown";
	}
}
