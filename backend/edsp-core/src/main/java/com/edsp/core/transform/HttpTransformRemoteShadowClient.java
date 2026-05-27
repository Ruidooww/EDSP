package com.edsp.core.transform;

import com.edsp.transform.contract.BatchTransformRequest;
import com.edsp.transform.contract.BatchTransformResponse;
import com.edsp.transform.contract.TransformDraftDto;
import com.edsp.transform.contract.TransformResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public class HttpTransformRemoteShadowClient implements TransformRemoteShadowClient {
    private static final int MAX_MISMATCHES = 20;

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final String baseUrl;
    private final Duration timeout;

    public HttpTransformRemoteShadowClient(ObjectMapper objectMapper, String baseUrl, int timeoutMs) {
        this(HttpClient.newBuilder().connectTimeout(Duration.ofMillis(timeoutMs)).build(), objectMapper, baseUrl, timeoutMs);
    }

    HttpTransformRemoteShadowClient(HttpClient httpClient, ObjectMapper objectMapper, String baseUrl, int timeoutMs) {
        this.httpClient = httpClient;
        this.objectMapper = objectMapper;
        this.baseUrl = trimTrailingSlash(baseUrl);
        this.timeout = Duration.ofMillis(timeoutMs);
    }

    @Override
    public boolean enabled() {
        return true;
    }

    @Override
    public TransformShadowReport shadow(BatchTransformRequest request, List<TransformResponse> localResults) {
        var attempted = localResults == null ? 0 : localResults.size();
        try {
            var body = objectMapper.writeValueAsString(request);
            var httpRequest = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/api/transform/standard-events/batch"))
                .timeout(timeout)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
            var response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                return TransformShadowReport.unavailable(attempted);
            }
            var remote = objectMapper.readValue(response.body(), BatchTransformResponse.class);
            return compare(localResults == null ? List.of() : localResults, remote);
        } catch (IOException | InterruptedException | RuntimeException ex) {
            if (ex instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            return TransformShadowReport.unavailable(attempted);
        }
    }

    private TransformShadowReport compare(List<TransformResponse> localResults, BatchTransformResponse remote) {
        if (remote == null || remote.results().size() != localResults.size()) {
            return TransformShadowReport.unavailable(localResults.size());
        }
        var mismatches = new ArrayList<Map<String, Object>>();
        var matched = 0;
        for (var index = 0; index < localResults.size(); index++) {
            var local = localResults.get(index);
            var remoteItem = remote.results().get(index);
            var before = mismatches.size();
            if (remoteItem.index() != index) {
                addMismatch(mismatches, index, "index", "index_mismatch");
            }
            compareDraft(index, local.draft(), remoteItem.draft(), mismatches);
            if (!Objects.equals(local.errors(), remoteItem.errors())) {
                addMismatch(mismatches, index, "errors", "value_mismatch");
            }
            if (!Objects.equals(local.warnings(), remoteItem.warnings())) {
                addMismatch(mismatches, index, "warnings", "value_mismatch");
            }
            if (mismatches.size() == before) {
                matched++;
            }
        }
        var mismatched = localResults.size() - matched;
        return TransformShadowReport.enabled(localResults.size(), matched, mismatched, 0, mismatches);
    }

    private void compareDraft(
        int index,
        TransformDraftDto local,
        TransformDraftDto remote,
        List<Map<String, Object>> mismatches
    ) {
        if (local == null || remote == null) {
            if (!Objects.equals(local, remote)) {
                addMismatch(mismatches, index, "draft", "presence_mismatch");
            }
            return;
        }
        compareField(index, "sourceSystem", local.sourceSystem(), remote.sourceSystem(), mismatches);
        compareField(index, "externalId", local.externalId(), remote.externalId(), mismatches);
        compareField(index, "eventType", local.eventType(), remote.eventType(), mismatches);
        compareField(index, "occurredAt", local.occurredAt(), remote.occurredAt(), mismatches);
        compareField(index, "actor", local.actor(), remote.actor(), mismatches);
        compareField(index, "assetRef", local.assetRef(), remote.assetRef(), mismatches);
        compareField(index, "subjectType", local.subjectType(), remote.subjectType(), mismatches);
        compareField(index, "subjectRef", local.subjectRef(), remote.subjectRef(), mismatches);
        compareField(index, "action", local.action(), remote.action(), mismatches);
        compareField(index, "result", local.result(), remote.result(), mismatches);
        compareField(index, "severity", local.severity(), remote.severity(), mismatches);
        compareField(index, "riskScore", local.riskScore(), remote.riskScore(), mismatches);
        compareField(index, "dedupKey", local.dedupKey(), remote.dedupKey(), mismatches);
        compareField(index, "normalized", local.normalized(), remote.normalized(), mismatches);
        compareField(index, "extra", local.extra(), remote.extra(), mismatches);
    }

    private void compareField(int index, String field, Object local, Object remote, List<Map<String, Object>> mismatches) {
        if (!Objects.equals(local, remote)) {
            addMismatch(mismatches, index, field, "value_mismatch");
        }
    }

    private void addMismatch(List<Map<String, Object>> mismatches, int index, String field, String type) {
        if (mismatches.size() >= MAX_MISMATCHES) {
            return;
        }
        var item = new LinkedHashMap<String, Object>();
        item.put("index", index);
        item.put("field", field);
        item.put("type", type);
        mismatches.add(item);
    }

    private static String trimTrailingSlash(String value) {
        var text = value == null || value.isBlank() ? "http://edsp-transform-service:8085" : value.trim();
        while (text.endsWith("/")) {
            text = text.substring(0, text.length() - 1);
        }
        return text;
    }
}
