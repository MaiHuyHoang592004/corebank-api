package com.corebank.corebank_api.ops.audit;

import com.corebank.corebank_api.common.CoreBankException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class AuditService {

	private final JdbcTemplate jdbcTemplate;

	public AuditService(JdbcTemplate jdbcTemplate) {
		this.jdbcTemplate = jdbcTemplate;
	}

	public void appendEvent(AuditCommand command) {
		byte[] prevRowHash = findLatestAuditRowHash().orElse(null);
		byte[] rowHash = buildAuditRowHash(command, prevRowHash);

		jdbcTemplate.update(
				"""
				INSERT INTO audit_events (
				    actor,
				    action,
				    resource_type,
				    resource_id,
				    correlation_id,
				    request_id,
				    session_id,
				    trace_id,
				    before_state_json,
				    after_state_json,
				    prev_row_hash,
				    row_hash
				) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?::jsonb, ?, ?)
				""",
				command.actor(),
				command.action(),
				command.resourceType(),
				command.resourceId(),
				command.correlationId(),
				command.requestId(),
				command.sessionId(),
				command.traceId(),
				command.beforeStateJson(),
				command.afterStateJson(),
				prevRowHash,
				rowHash);
	}

	private Optional<byte[]> findLatestAuditRowHash() {
		List<byte[]> hashes = jdbcTemplate.query(
				"""
				SELECT row_hash
				FROM audit_events
				ORDER BY created_at DESC, audit_id DESC
				LIMIT 1
				""",
				(rs, rowNum) -> rs.getBytes("row_hash"));

		return hashes.stream().findFirst();
	}

	private byte[] buildAuditRowHash(AuditCommand command, byte[] prevRowHash) {
		String payload = String.join(
				"||",
				command.actor(),
				command.action(),
				command.resourceType(),
				command.resourceId(),
				String.valueOf(command.correlationId()),
				String.valueOf(command.requestId()),
				String.valueOf(command.sessionId()),
				String.valueOf(command.traceId()),
				String.valueOf(command.beforeStateJson()),
				String.valueOf(command.afterStateJson()),
				Base64.getEncoder().encodeToString(prevRowHash == null ? new byte[0] : prevRowHash));

		return sha256(payload);
	}

	private byte[] sha256(String value) {
		try {
			MessageDigest digest = MessageDigest.getInstance("SHA-256");
			return digest.digest(value.getBytes(StandardCharsets.UTF_8));
		} catch (NoSuchAlgorithmException ex) {
			throw new CoreBankException("SHA-256 algorithm is unavailable", ex);
		}
	}

	public record AuditCommand(
			String actor,
			String action,
			String resourceType,
			String resourceId,
			UUID correlationId,
			UUID requestId,
			UUID sessionId,
			String traceId,
			String beforeStateJson,
			String afterStateJson) {
	}
}