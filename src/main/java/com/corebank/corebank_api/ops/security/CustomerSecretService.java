package com.corebank.corebank_api.ops.security;

import com.corebank.corebank_api.ops.audit.AuditService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class CustomerSecretService {

	private static final int MAX_PLAINTEXT_LENGTH = 2048;

	private final CustomerSecretRepository customerSecretRepository;
	private final CustomerSecretCryptoService customerSecretCryptoService;
	private final AuditService auditService;
	private final ObjectMapper objectMapper;

	public CustomerSecretService(
			CustomerSecretRepository customerSecretRepository,
			CustomerSecretCryptoService customerSecretCryptoService,
			AuditService auditService,
			ObjectMapper objectMapper) {
		this.customerSecretRepository = customerSecretRepository;
		this.customerSecretCryptoService = customerSecretCryptoService;
		this.auditService = auditService;
		this.objectMapper = objectMapper.copy().findAndRegisterModules();
	}

	@Transactional
	public SecretMetadata upsertSecret(
			UUID customerId,
			String rawSecretType,
			String plainText,
			String actor) {
		UUID safeCustomerId = requireCustomerId(customerId);
		CustomerSecretType secretType = CustomerSecretType.parse(rawSecretType);
		String safePlainText = requirePlainText(plainText);
		String safeActor = safeActor(actor);

		ensureCustomerExists(safeCustomerId);

		CustomerSecretCryptoService.EncryptionResult encrypted =
				customerSecretCryptoService.encrypt(safeCustomerId, secretType, safePlainText);
		customerSecretRepository.upsertSecret(
				safeCustomerId,
				secretType,
				encrypted.cipherText(),
				encrypted.nonce(),
				encrypted.keyVersion(),
				encrypted.encryptionAlgorithm());

		SecretMetadata metadata = customerSecretRepository.findMetadata(safeCustomerId, secretType)
				.map(this::toMetadata)
				.orElseThrow(() -> new IllegalStateException("Unable to load persisted customer secret metadata"));

		appendAudit(
				safeCustomerId,
				secretType,
				encrypted,
				safeActor);
		return metadata;
	}

	@Transactional(readOnly = true)
	public SecretMetadataPage listSecretMetadata(UUID customerId) {
		UUID safeCustomerId = requireCustomerId(customerId);
		ensureCustomerExists(safeCustomerId);

		List<SecretMetadata> items = customerSecretRepository.listMetadata(safeCustomerId).stream()
				.map(this::toMetadata)
				.toList();
		return new SecretMetadataPage(safeCustomerId, items);
	}

	private SecretMetadata toMetadata(CustomerSecretRepository.CustomerSecretRow row) {
		return new SecretMetadata(
				row.secretType(),
				row.keyVersion(),
				row.encryptionAlgorithm(),
				row.createdAt());
	}

	private void appendAudit(
			UUID customerId,
			CustomerSecretType secretType,
			CustomerSecretCryptoService.EncryptionResult encrypted,
			String actor) {
		Map<String, Object> after = Map.of(
				"customerId", customerId,
				"secretType", secretType.name(),
				"keyVersion", encrypted.keyVersion(),
				"algorithm", encrypted.encryptionAlgorithm(),
				"cipherLength", encrypted.cipherText().length);

		UUID correlationId = UUID.nameUUIDFromBytes(
				("customer-secret:" + customerId + ":" + secretType.name()).getBytes(StandardCharsets.UTF_8));

		auditService.appendEvent(new AuditService.AuditCommand(
				actor,
				"CUSTOMER_SECRET_UPSERTED",
				"CUSTOMER_SECRET",
				customerId + ":" + secretType.name(),
				correlationId,
				null,
				null,
				"ops-customer-secret",
				null,
				toJson(after)));
	}

	private void ensureCustomerExists(UUID customerId) {
		if (!customerSecretRepository.customerExists(customerId)) {
			throw new ResponseStatusException(HttpStatus.NOT_FOUND, "customer not found");
		}
	}

	private UUID requireCustomerId(UUID customerId) {
		if (customerId == null) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "customerId is required");
		}
		return customerId;
	}

	private String requirePlainText(String plainText) {
		if (plainText == null || plainText.trim().isEmpty()) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "plainText is required");
		}
		if (plainText.length() > MAX_PLAINTEXT_LENGTH) {
			throw new ResponseStatusException(
					HttpStatus.BAD_REQUEST,
					"plainText length must be <= " + MAX_PLAINTEXT_LENGTH);
		}
		return plainText;
	}

	private String safeActor(String actor) {
		if (actor == null || actor.trim().isEmpty()) {
			return "system";
		}
		return actor.trim();
	}

	private String toJson(Object value) {
		try {
			return objectMapper.writeValueAsString(value);
		} catch (JsonProcessingException ex) {
			throw new IllegalStateException("Unable to serialize customer secret audit payload", ex);
		}
	}

	public record SecretMetadata(
			String secretType,
			int keyVersion,
			String encryptionAlgorithm,
			Instant createdAt) {
	}

	public record SecretMetadataPage(
			UUID customerId,
			List<SecretMetadata> items) {
	}
}
