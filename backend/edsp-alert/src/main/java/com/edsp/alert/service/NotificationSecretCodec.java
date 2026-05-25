package com.edsp.alert.service;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

public class NotificationSecretCodec {
    static final String CIPHERTEXT_VERSION = "v1";
    static final String KEY_VERSION = "local-v1";
    private static final int NONCE_BYTES = 12;
    private static final int TAG_BITS = 128;
    private static final String CIPHER = "AES/GCM/NoPadding";

    private final SecureRandom secureRandom;

    public NotificationSecretCodec() {
        this(new SecureRandom());
    }

    NotificationSecretCodec(SecureRandom secureRandom) {
        this.secureRandom = secureRandom;
    }

    public String encrypt(String plaintext, byte[] key) {
        try {
            var nonce = new byte[NONCE_BYTES];
            secureRandom.nextBytes(nonce);
            var cipher = Cipher.getInstance(CIPHER);
            cipher.init(Cipher.ENCRYPT_MODE, secretKey(key), new GCMParameterSpec(TAG_BITS, nonce));
            var ciphertext = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            return CIPHERTEXT_VERSION
                + ":"
                + Base64.getEncoder().encodeToString(nonce)
                + ":"
                + Base64.getEncoder().encodeToString(ciphertext);
        } catch (GeneralSecurityException ex) {
            throw new IllegalStateException("notification_secret_encrypt_failed", ex);
        }
    }

    public String decrypt(String encoded, byte[] key) {
        try {
            var parts = encoded == null ? new String[0] : encoded.split(":", 3);
            if (parts.length != 3 || !CIPHERTEXT_VERSION.equals(parts[0])) {
                throw new IllegalArgumentException("invalid_ciphertext_format");
            }
            var nonce = Base64.getDecoder().decode(parts[1]);
            var ciphertext = Base64.getDecoder().decode(parts[2]);
            if (nonce.length != NONCE_BYTES) {
                throw new IllegalArgumentException("invalid_ciphertext_nonce");
            }
            var cipher = Cipher.getInstance(CIPHER);
            cipher.init(Cipher.DECRYPT_MODE, secretKey(key), new GCMParameterSpec(TAG_BITS, nonce));
            return new String(cipher.doFinal(ciphertext), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException | IllegalArgumentException ex) {
            throw new IllegalStateException("notification_secret_decrypt_failed", ex);
        }
    }

    private SecretKeySpec secretKey(byte[] key) {
        return new SecretKeySpec(key, "AES");
    }
}
