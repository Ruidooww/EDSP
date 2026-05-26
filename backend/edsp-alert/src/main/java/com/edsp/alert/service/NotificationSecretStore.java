package com.edsp.alert.service;

import java.util.Base64;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class NotificationSecretStore {
    private static final int MASTER_KEY_BYTES = 32;

    private final String configuredMasterKey;
    private final NotificationSecretCodec codec;

    @Autowired
    public NotificationSecretStore(@Value("${edsp.notification.secret.master-key:}") String configuredMasterKey) {
        this(configuredMasterKey, new NotificationSecretCodec());
    }

    NotificationSecretStore(String configuredMasterKey, NotificationSecretCodec codec) {
        this.configuredMasterKey = configuredMasterKey;
        this.codec = codec;
    }

    public StoredEndpoint storeEndpoint(String endpointUrl, String endpointMasked) {
        var key = masterKeyForWrite();
        return new StoredEndpoint(
            codec.encrypt(endpointUrl, key),
            NotificationSecretCodec.KEY_VERSION,
            endpointMasked,
            "encrypted"
        );
    }

    public void requireWritableMasterKey() {
        masterKeyForWrite();
    }

    public String resolveEndpoint(Map<String, Object> channel) {
        var status = stringOrBlank(channel.get("secret_storage_status"));
        var ciphertext = stringOrBlank(channel.get("endpoint_secret_ciphertext"));
        var endpointUrl = stringOrBlank(channel.get("endpoint_url"));
        if ("missing".equals(status)) {
            throw unavailable();
        }
        if ("encrypted".equals(status) || !ciphertext.isBlank()) {
            if (ciphertext.isBlank()) {
                throw unavailable();
            }
            try {
                return codec.decrypt(ciphertext, masterKeyForRead());
            } catch (RuntimeException ex) {
                throw unavailable();
            }
        }
        if (endpointUrl.isBlank()) {
            throw unavailable();
        }
        return endpointUrl;
    }

    private byte[] masterKeyForWrite() {
        var value = configuredMasterKey();
        if (value.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "notification_secret_key_missing");
        }
        return decodeMasterKey(value, "notification_secret_key_invalid");
    }

    private byte[] masterKeyForRead() {
        var value = configuredMasterKey();
        if (value.isBlank()) {
            throw unavailable();
        }
        try {
            return decodeMasterKey(value, "notification_secret_key_invalid");
        } catch (ResponseStatusException ex) {
            throw unavailable();
        }
    }

    private byte[] decodeMasterKey(String value, String reason) {
        try {
            var decoded = Base64.getDecoder().decode(value);
            if (decoded.length != MASTER_KEY_BYTES) {
                throw new IllegalArgumentException("invalid_key_length");
            }
            return decoded;
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, reason);
        }
    }

    private String configuredMasterKey() {
        if (configuredMasterKey != null && !configuredMasterKey.isBlank()) {
            return configuredMasterKey.trim();
        }
        var envValue = System.getenv("EDSP_NOTIFICATION_SECRET_KEY");
        return envValue == null ? "" : envValue.trim();
    }

    private ResponseStatusException unavailable() {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, "notification_secret_unavailable");
    }

    private String stringOrBlank(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    public record StoredEndpoint(
        String ciphertext,
        String keyVersion,
        String endpointMasked,
        String status
    ) {
    }
}
