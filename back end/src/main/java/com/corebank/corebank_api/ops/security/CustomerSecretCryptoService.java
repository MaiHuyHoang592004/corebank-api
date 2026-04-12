package com.corebank.corebank_api.ops.security;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.UUID;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class CustomerSecretCryptoService {

	private static final String ENCRYPTION_ALGORITHM = "AES/GCM/NoPadding";
	private static final String KEY_ALGORITHM = "AES";
	private static final int NONCE_LENGTH_BYTES = 12;
	private static final int AUTH_TAG_LENGTH_BITS = 128;
	private static final int DEFAULT_KEY_VERSION = 1;

	private final SecureRandom secureRandom = new SecureRandom();
	private final String masterKeyBase64;

	public CustomerSecretCryptoService(
			@Value("${corebank.security.master-key-b64:}") String masterKeyBase64) {
		this.masterKeyBase64 = masterKeyBase64;
	}

	public EncryptionResult encrypt(UUID customerId, CustomerSecretType secretType, String plainText) {
		SecretKeySpec key = resolveMasterKey();
		byte[] nonce = new byte[NONCE_LENGTH_BYTES];
		secureRandom.nextBytes(nonce);

		try {
			Cipher cipher = Cipher.getInstance(ENCRYPTION_ALGORITHM);
			cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(AUTH_TAG_LENGTH_BITS, nonce));
			cipher.updateAAD(buildAad(customerId, secretType, DEFAULT_KEY_VERSION));
			byte[] cipherText = cipher.doFinal(plainText.getBytes(StandardCharsets.UTF_8));
			return new EncryptionResult(
					cipherText,
					nonce,
					DEFAULT_KEY_VERSION,
					ENCRYPTION_ALGORITHM);
		} catch (GeneralSecurityException ex) {
			throw new IllegalStateException("Unable to encrypt customer secret payload", ex);
		}
	}

	private SecretKeySpec resolveMasterKey() {
		if (masterKeyBase64 == null || masterKeyBase64.trim().isEmpty()) {
			throw unavailableKey();
		}

		byte[] keyBytes;
		try {
			keyBytes = Base64.getDecoder().decode(masterKeyBase64.trim());
		} catch (IllegalArgumentException ex) {
			throw unavailableKey();
		}

		if (keyBytes.length != 16 && keyBytes.length != 24 && keyBytes.length != 32) {
			throw unavailableKey();
		}

		return new SecretKeySpec(keyBytes, KEY_ALGORITHM);
	}

	private byte[] buildAad(UUID customerId, CustomerSecretType secretType, int keyVersion) {
		String aad = customerId + "|" + secretType.name() + "|" + keyVersion;
		return aad.getBytes(StandardCharsets.UTF_8);
	}

	private ResponseStatusException unavailableKey() {
		return new ResponseStatusException(
				HttpStatus.SERVICE_UNAVAILABLE,
				"Encryption key is unavailable");
	}

	public record EncryptionResult(
			byte[] cipherText,
			byte[] nonce,
			int keyVersion,
			String encryptionAlgorithm) {
	}
}
