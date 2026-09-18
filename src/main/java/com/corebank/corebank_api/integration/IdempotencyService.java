package com.corebank.corebank_api.integration;

import com.corebank.corebank_api.common.CoreBankException;
import com.corebank.corebank_api.common.IdempotencyConflictException;
import com.corebank.corebank_api.integration.redis.RedisIdempotencyCacheService;
import java.sql.Timestamp;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@Slf4j
public class IdempotencyService {

	private final JdbcTemplate jdbcTemplate;
	private final RedisIdempotencyCacheService redisIdempotencyCacheService;

	/**
	 * How long a claim is presumed live before a retry may take it over.
	 *
	 * <p>Two minutes is far longer than any money command legitimately takes and far shorter than
	 * the time it takes anyone to notice a stuck payment. Setting it too low risks executing a
	 * command twice while the first attempt is still running; too high and a crash leaves customers
	 * unable to retry for that long. If a command ever genuinely needs longer than this, the answer
	 * is to make it asynchronous, not to raise the lease.
	 */
	private final Duration claimLease;

	public IdempotencyService(
			JdbcTemplate jdbcTemplate,
			RedisIdempotencyCacheService redisIdempotencyCacheService,
			@Value("${corebank.idempotency.claim-lease:PT2M}") Duration claimLease) {
		this.claimLease = claimLease;
		this.jdbcTemplate = jdbcTemplate;
		this.redisIdempotencyCacheService = redisIdempotencyCacheService;
	}

	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public StartResult checkBeforeExecution(String idempotencyKey, String requestPayloadJson, Instant expiresAt) {
		String requestHash = sha256Hex(requestPayloadJson);
		Optional<String> cachedReplay = redisIdempotencyCacheService.findSuccessReplay(idempotencyKey, requestHash);
		if (cachedReplay.isPresent()) {
			return StartResult.replay(cachedReplay.get());
		}
		Optional<IdempotencyRecord> existing = findByKey(idempotencyKey);

		if (existing.isPresent()) {
			return evaluateExisting(existing.get(), requestHash, expiresAt);
		}

		// ON CONFLICT rather than catching DuplicateKeyException.
		//
		// A unique-violation aborts the PostgreSQL transaction, so every statement after it fails
		// with SQLSTATE 25P02 until the transaction ends. The previous version caught the exception
		// and then read the row back inside that same aborted transaction, which could not work: a
		// duplicate that lost the insert race returned 500 instead of being told the request was
		// already running. A client that retries on 5xx would have kept hammering.
		//
		// Letting the database decide the winner keeps the claim race-safe; the difference is only
		// that the loser now learns it lost without poisoning its own transaction.
		int claimed = jdbcTemplate.update(
				"""
				INSERT INTO idempotency_keys (
				    idempotency_key,
				    request_hash,
				    status,
				    expires_at
				) VALUES (?, ?, 'IN_PROGRESS', ?)
				ON CONFLICT (idempotency_key) DO NOTHING
				""",
				idempotencyKey,
				requestHash,
				Timestamp.from(expiresAt));

		if (claimed == 1) {
			return StartResult.started(requestHash, expiresAt);
		}

		IdempotencyRecord duplicate = findByKey(idempotencyKey)
				.orElseThrow(() -> new CoreBankException(
						"Idempotency key conflict occurred but record is unavailable"));
		return evaluateExisting(duplicate, requestHash, expiresAt);
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
			cacheSuccessReplayAfterCommit(idempotencyKey, requestHash, expiresAt, responseBodyJson);
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

		cacheSuccessReplayAfterCommit(
				idempotencyKey,
				requestHash,
				existing.expiresAt(),
				existing.responseBody() != null ? existing.responseBody() : responseBodyJson);
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
				       claimed_at,
				       completed_at,
				       expires_at
				FROM idempotency_keys
				WHERE idempotency_key = ?
				""",
				(rs, rowNum) -> new IdempotencyRecord(
						rs.getString("idempotency_key"),
						rs.getString("request_hash"),
						rs.getString("status"),
						rs.getString("response_body"),
						toInstant(rs.getTimestamp("expires_at")),
						toInstant(rs.getTimestamp("claimed_at"))),
				idempotencyKey);

		return results.stream().findFirst();
	}

	private StartResult evaluateExisting(IdempotencyRecord existing, String requestHash, Instant expiresAt) {
		if (!existing.requestHash().equals(requestHash)) {
			throw new IdempotencyConflictException("Idempotency key has already been used with a different payload");
		}

		if ("SUCCEEDED".equals(existing.status())) {
			redisIdempotencyCacheService.cacheSuccessReplay(
					existing.idempotencyKey(),
					requestHash,
					existing.responseBody(),
					existing.expiresAt());
			return StartResult.replay(existing.responseBody());
		}

		if ("FAILED".equals(existing.status())) {
			int updatedRows = jdbcTemplate.update(
					"""
					UPDATE idempotency_keys
					SET status = 'IN_PROGRESS',
					    response_body = NULL,
					    completed_at = NULL,
					    claimed_at = now(),
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
			return takeOverOrReject(existing, requestHash, expiresAt);
		}

		throw new CoreBankException("Idempotent request exists in unsupported state: " + existing.status());
	}

	/**
	 * Decides whether an IN_PROGRESS claim is still live or was abandoned by a process that is no
	 * longer running.
	 *
	 * <p>A command claims its key, executes, and marks the outcome. If the instance dies between
	 * the first and the last step the claim is left behind. The database rolls the money back, so
	 * nothing is lost or double-posted — but without this, the key stayed IN_PROGRESS forever, the
	 * client's retry was rejected forever, and the maintenance job never touched it because it only
	 * deletes terminal rows. The operation could not be completed and could not be abandoned.
	 *
	 * <p>The lease is the presumption of death. A claim older than the window cannot belong to a
	 * command still running, because a money command that takes minutes has already failed by a
	 * different route. The takeover is a conditional update rather than a read followed by a write,
	 * so when two retries arrive together exactly one wins and the other is told to wait.
	 */
	private StartResult takeOverOrReject(IdempotencyRecord existing, String requestHash, Instant expiresAt) {
		Instant claimedAt = existing.claimedAt();
		boolean leaseExpired =
				claimedAt == null || claimedAt.isBefore(Instant.now().minus(claimLease));

		if (!leaseExpired) {
			// Genuinely concurrent duplicate: the original is still running and will produce the
			// answer. Rejecting is correct; executing twice is not.
			throw new CoreBankException("Idempotent request is already in progress");
		}

		int takenOver = jdbcTemplate.update(
				"""
				UPDATE idempotency_keys
				SET claimed_at = now(),
				    expires_at = ?
				WHERE idempotency_key = ?
				  AND request_hash = ?
				  AND status = 'IN_PROGRESS'
				  AND claimed_at < ?
				""",
				Timestamp.from(expiresAt),
				existing.idempotencyKey(),
				requestHash,
				Timestamp.from(Instant.now().minus(claimLease)));

		if (takenOver == 1) {
			log.warn(
					"Reclaimed an abandoned idempotency claim. key={} claimedAt={} lease={}. The "
							+ "original attempt did not record an outcome, which normally means the "
							+ "instance holding it stopped mid-command.",
					existing.idempotencyKey(),
					claimedAt,
					claimLease);
			return StartResult.started(requestHash, expiresAt);
		}

		// Another retry took it over first; it is live again and this caller must wait.
		throw new CoreBankException("Idempotent request is already in progress");
	}

	private String sha256Hex(String value) {
		try {
			MessageDigest digest = MessageDigest.getInstance("SHA-256");
			return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
		} catch (NoSuchAlgorithmException ex) {
			throw new CoreBankException("SHA-256 algorithm is unavailable", ex);
		}
	}

	private void cacheSuccessReplayAfterCommit(
			String idempotencyKey,
			String requestHash,
			Instant expiresAt,
			String responseBodyJson) {
		if (responseBodyJson == null || expiresAt == null) {
			return;
		}
		if (TransactionSynchronizationManager.isSynchronizationActive()) {
			TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
				@Override
				public void afterCommit() {
					redisIdempotencyCacheService.cacheSuccessReplay(
							idempotencyKey,
							requestHash,
							responseBodyJson,
							expiresAt);
				}
			});
			return;
		}
		redisIdempotencyCacheService.cacheSuccessReplay(idempotencyKey, requestHash, responseBodyJson, expiresAt);
	}

	private Instant toInstant(Timestamp timestamp) {
		return timestamp == null ? null : timestamp.toInstant();
	}

	private record IdempotencyRecord(
			String idempotencyKey,
			String requestHash,
			String status,
			String responseBody,
			Instant expiresAt,
			Instant claimedAt) {
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
