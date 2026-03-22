package com.corebank.corebank_api.integration;

import com.corebank.corebank_api.common.CoreBankException;
import com.corebank.corebank_api.common.IdempotencyConflictException;
import java.sql.Timestamp;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class IdempotencyService {

	private final JdbcTemplate jdbcTemplate;

	public IdempotencyService(JdbcTemplate jdbcTemplate) {
		this.jdbcTemplate = jdbcTemplate;
	}

	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public StartResult checkBeforeExecution(String idempotencyKey, String requestPayloadJson, Instant expiresAt) {
		String requestHash = sha256Hex(requestPayloadJson);
		Optional<IdempotencyRecord> existing = findByKey(idempotencyKey);

		if (existing.isPresent()) {
			return evaluateExisting(existing.get(), requestHash, expiresAt);
		}

		try {
			jdbcTemplate.update(
					"""
					INSERT INTO idempotency_keys (
					    idempotency_key,
					    request_hash,
					    status,
					    expires_at
					) VALUES (?, ?, 'IN_PROGRESS', ?)
					""",
					idempotencyKey,
					requestHash,
					Timestamp.from(expiresAt));
			return StartResult.started(requestHash, expiresAt);
		} catch (DuplicateKeyException ex) {
			IdempotencyRecord duplicate = findByKey(idempotencyKey)
					.orElseThrow(() -> new CoreBankException("Idempotency key conflict occurred but record is unavailable", ex));
			return evaluateExisting(duplicate, requestHash, expiresAt);
		}
	}

	@Transactional
	public void markSucceeded(String idempotencyKey, String requestHash, Instant expiresAt, String responseBodyJson) {
		int updatedRows = jdbcTemplate.update(
				"""
				UPDATE idempotency_keys
				SET status = 'SUCCEEDED',
				    response_body = ?::jsonb,
				    completed_at = now(),
				    expires_at = ?
				WHERE idempotency_key = ?
				  AND request_hash = ?
				  AND status = 'IN_PROGRESS'
				""",
				responseBodyJson,
				Timestamp.from(expiresAt),
				idempotencyKey,
				requestHash);

		if (updatedRows == 1) {
			return;
		}

		IdempotencyRecord existing = findByKey(idempotencyKey)
				.orElseThrow(() -> new CoreBankException("Idempotency key disappeared before success could be persisted"));

		if (!existing.requestHash().equals(requestHash)) {
			throw new IdempotencyConflictException("Idempotency key has already been used with a different payload");
		}

		if (!"SUCCEEDED".equals(existing.status())) {
			throw new CoreBankException("Idempotent request exists in unsupported state: " + existing.status());
		}
	}

	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public void markFailed(String idempotencyKey, String requestHash) {
		int updatedRows = jdbcTemplate.update(
				"""
				UPDATE idempotency_keys
				SET status = 'FAILED',
				    response_body = NULL,
				    completed_at = now()
				WHERE idempotency_key = ?
				  AND request_hash = ?
				  AND status = 'IN_PROGRESS'
				""",
				idempotencyKey,
				requestHash);

		if (updatedRows == 1) {
			return;
		}

		Optional<IdempotencyRecord> existing = findByKey(idempotencyKey);
		if (existing.isPresent() && !existing.get().requestHash().equals(requestHash)) {
			throw new IdempotencyConflictException("Idempotency key has already been used with a different payload");
		}
	}

	private Optional<IdempotencyRecord> findByKey(String idempotencyKey) {
		List<IdempotencyRecord> results = jdbcTemplate.query(
				"""
				SELECT idempotency_key,
				       request_hash,
				       status,
				       response_body,
				       created_at,
				       completed_at,
				       expires_at
				FROM idempotency_keys
				WHERE idempotency_key = ?
				""",
				(rs, rowNum) -> new IdempotencyRecord(
						rs.getString("idempotency_key"),
						rs.getString("request_hash"),
						rs.getString("status"),
						rs.getString("response_body")),
				idempotencyKey);

		return results.stream().findFirst();
	}

	private StartResult evaluateExisting(IdempotencyRecord existing, String requestHash, Instant expiresAt) {
		if (!existing.requestHash().equals(requestHash)) {
			throw new IdempotencyConflictException("Idempotency key has already been used with a different payload");
		}

		if ("SUCCEEDED".equals(existing.status())) {
			return StartResult.replay(existing.responseBody());
		}

		if ("FAILED".equals(existing.status())) {
			int updatedRows = jdbcTemplate.update(
					"""
					UPDATE idempotency_keys
					SET status = 'IN_PROGRESS',
					    response_body = NULL,
					    completed_at = NULL,
					    expires_at = ?
					WHERE idempotency_key = ?
					  AND request_hash = ?
					  AND status = 'FAILED'
					""",
					Timestamp.from(expiresAt),
					existing.idempotencyKey(),
					requestHash);

			if (updatedRows == 1) {
				return StartResult.started(requestHash, expiresAt);
			}

			IdempotencyRecord latest = findByKey(existing.idempotencyKey())
					.orElseThrow(() -> new CoreBankException("Idempotency key disappeared during retry transition"));
			return evaluateExisting(latest, requestHash, expiresAt);
		}

		if ("IN_PROGRESS".equals(existing.status())) {
			throw new CoreBankException("Idempotent request is already in progress");
		}

		throw new CoreBankException("Idempotent request exists in unsupported state: " + existing.status());
	}

	private String sha256Hex(String value) {
		try {
			MessageDigest digest = MessageDigest.getInstance("SHA-256");
			return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
		} catch (NoSuchAlgorithmException ex) {
			throw new CoreBankException("SHA-256 algorithm is unavailable", ex);
		}
	}

	private record IdempotencyRecord(
			String idempotencyKey,
			String requestHash,
			String status,
			String responseBody) {
	}

	public record StartResult(boolean replay, String requestHash, Instant expiresAt, String responseBodyJson) {

		public static StartResult started(String requestHash, Instant expiresAt) {
			return new StartResult(false, requestHash, expiresAt, null);
		}

		public static StartResult replay(String responseBodyJson) {
			return new StartResult(true, null, null, responseBodyJson);
		}
	}
}