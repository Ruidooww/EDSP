package com.edsp.core.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Service;

@Service
public class PlanFingerprintSupport {
    public static final String ALGORITHM = "sha256-canonical-json-v1";

    private final ObjectMapper objectMapper;

    public PlanFingerprintSupport(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public PlanFingerprint fingerprint(Object planJson) {
        var canonicalJson = canonicalJson(planJson);
        return new PlanFingerprint(ALGORITHM, sha256(canonicalJson));
    }

    private String canonicalJson(Object planJson) {
        try {
            return objectMapper.writeValueAsString(canonicalize(toJsonNode(planJson)));
        } catch (JsonProcessingException ex) {
            throw new IllegalArgumentException("Unable to canonicalize ingestion plan JSON", ex);
        }
    }

    private JsonNode toJsonNode(Object value) throws JsonProcessingException {
        if (value == null) {
            return objectMapper.nullNode();
        }
        if (value instanceof JsonNode node) {
            return node;
        }
        if (value instanceof byte[] bytes) {
            return parseText(new String(bytes, StandardCharsets.UTF_8));
        }
        if (value instanceof CharSequence text) {
            return parseText(text.toString());
        }
        return objectMapper.valueToTree(value);
    }

    private JsonNode parseText(String text) throws JsonProcessingException {
        var node = objectMapper.readTree(text);
        if (node.isTextual()) {
            node = objectMapper.readTree(node.asText());
        }
        return node;
    }

    private Object canonicalize(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        if (node.isObject()) {
            var result = new LinkedHashMap<String, Object>();
            var fields = new ArrayList<Map.Entry<String, JsonNode>>();
            node.fields().forEachRemaining(fields::add);
            fields.stream()
                .sorted(Comparator.comparing(Map.Entry::getKey))
                .forEach(entry -> result.put(entry.getKey(), canonicalize(entry.getValue())));
            return result;
        }
        if (node.isArray()) {
            var result = new ArrayList<Object>();
            node.forEach(item -> result.add(canonicalize(item)));
            return result;
        }
        if (node.isTextual()) {
            return node.asText();
        }
        if (node.isBoolean()) {
            return node.asBoolean();
        }
        if (node.isIntegralNumber()) {
            return node.canConvertToLong() ? node.asLong() : node.bigIntegerValue();
        }
        if (node.isFloatingPointNumber() || node.isBigDecimal()) {
            return node.decimalValue().stripTrailingZeros();
        }
        if (node.isBigInteger()) {
            return node.bigIntegerValue();
        }
        if (node.isNumber()) {
            return node.numberValue();
        }
        return node.asText();
    }

    private String sha256(String value) {
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is not available", ex);
        }
    }

    public record PlanFingerprint(String algorithm, String hash) {
        public Map<String, Object> asMap() {
            var result = new LinkedHashMap<String, Object>();
            result.put("algorithm", algorithm);
            result.put("hash", hash);
            return result;
        }
    }
}
