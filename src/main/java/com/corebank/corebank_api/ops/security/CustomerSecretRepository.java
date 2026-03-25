package com.corebank.corebank_api.ops.security;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

@Repository
public class CustomerSecretRepository {

	private final JdbcTemplate jdbcTemplate;

	private final RowMapper<CustomerSecretRow> metadataMapper = new RowMapper<>() {
		@Override
		public CustomerSecretRow mapRow(ResultSet rs, int rowNum) throws SQLException {
			return new CustomerSecretRow(
					rs.getString("secret_type"),
					rs.getInt("key_version"),
					rs.getString("encryption_algorithm"),
					readInstant(rs.getTimestamp("created_at")));
		}
	};

	public CustomerSecretRepository(JdbcTemplate jdbcTemplate) {
		this.jdbcTemplate = jdbcTemplate;
	}

	public boolean customerExists(UUID customerId) {
		Integer count = jdbcTemplate.queryForObject(
				"SELECT COUNT(*) FROM customers WHERE customer_id = ?",
				Integer.class,
				customerId);
		return count != null && count > 0;
	}

	public void upsertSecret(
			UUID customerId,
			CustomerSecretType secretType,
			byte[] cipherText,
			byte[] nonce,
			int keyVersion,
			String encryptionAlgorithm) {
		jdbcTemplate.update(
				"""
				INSERT INTO encrypted_customer_secrets (
				    customer_id,
				    secret_type,
				    cipher_text,
				    key_version,
				    encryption_algorithm,
				    nonce,
				    created_at
				) VALUES (?, ?, ?, ?, ?, ?, now())
				ON CONFLICT (customer_id, secret_type)
				DO UPDATE SET
				    cipher_text = EXCLUDED.cipher_text,
				    key_version = EXCLUDED.key_version,
				    encryption_algorithm = EXCLUDED.encryption_algorithm,
				    nonce = EXCLUDED.nonce,
				    created_at = now()
				""",
				customerId,
				secretType.name(),
				cipherText,
				keyVersion,
				encryptionAlgorithm,
				nonce);
	}

	public Optional<CustomerSecretRow> findMetadata(UUID customerId, CustomerSecretType secretType) {
		List<CustomerSecretRow> rows = jdbcTemplate.query(
				"""
				SELECT secret_type,
				       key_version,
				       encryption_algorithm,
				       created_at
				FROM encrypted_customer_secrets
				WHERE customer_id = ?
				  AND secret_type = ?
				""",
				metadataMapper,
				customerId,
				secretType.name());
		return rows.stream().findFirst();
	}

	public List<CustomerSecretRow> listMetadata(UUID customerId) {
		return jdbcTemplate.query(
				"""
				SELECT secret_type,
				       key_version,
				       encryption_algorithm,
				       created_at
				FROM encrypted_customer_secrets
				WHERE customer_id = ?
				ORDER BY created_at DESC, secret_type
				""",
				metadataMapper,
				customerId);
	}

	public int countByCustomerAndType(UUID customerId, CustomerSecretType secretType) {
		Integer count = jdbcTemplate.queryForObject(
				"""
				SELECT COUNT(*)
				FROM encrypted_customer_secrets
				WHERE customer_id = ?
				  AND secret_type = ?
				""",
				Integer.class,
				customerId,
				secretType.name());
		return count == null ? 0 : count;
	}

	private Instant readInstant(Timestamp timestamp) {
		return timestamp == null ? null : timestamp.toInstant();
	}

	public record CustomerSecretRow(
			String secretType,
			int keyVersion,
			String encryptionAlgorithm,
			Instant createdAt) {
	}
}
