package com.edsp.core.transform.runtime;

import com.edsp.transform.contract.BatchTransformRequest;
import com.edsp.transform.contract.BatchTransformResponse;
import com.edsp.transform.contract.TransformDraftDto;
import com.edsp.transform.contract.TransformResponse;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

public class RemoteTransformRuntimeClient implements TransformRuntimeClient {
    private static final String BATCH_PATH = "/api/transform/standard-events/batch";

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final String baseUrl;
    private final Duration timeout;

    public RemoteTransformRuntimeClient(ObjectMapper objectMapper, String baseUrl, int timeoutMs) {
        this(HttpClient.newBuilder().connectTimeout(Duration.ofMillis(timeoutMs)).build(), objectMapper, baseUrl, timeoutMs);
    }

    RemoteTransformRuntimeClient(HttpClient httpClient, ObjectMapper objectMapper, String baseUrl, int timeoutMs) {
        this.httpClient = httpClient;
        this.objectMapper = objectMapper;
        this.baseUrl = trimTrailingSlash(baseUrl);
        this.timeout = Duration.ofMillis(timeoutMs);
    }

    @Override
    public String mode() {
        return "remote";
    }

    @Override
    public TransformBatchResult transform(BatchTransformRequest request) {
        var attempted = request == null || request.rows() == null ? 0 : request.rows().size();
        var unavailable = TransformRuntimeReport.remoteFailure("remote", "remote_unavailable", false);
        try {
            var body = objectMapper.writeValueAsString(request);
            var httpRequest = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + BATCH_PATH))
                .timeout(timeout)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
            var response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new TransformRuntimeException("remote_unavailable", "Remote transform returned non-2xx", unavailable);
            }
            var remote = readResponse(response.body());
            var results = validate(remote, attempted);
            return new TransformBatchResult(results, TransformRuntimeReport.remoteSuccess("remote"));
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new TransformRuntimeException("remote_unavailable", "Remote transform interrupted", unavailable, ex);
        } catch (IOException ex) {
            throw new TransformRuntimeException("remote_unavailable", "Remote transform unavailable", unavailable, ex);
        } catch (IllegalArgumentException ex) {
            throw new TransformRuntimeException("remote_unavailable", "Remote transform request is invalid", unavailable, ex);
        }
    }

    private BatchTransformResponse readResponse(String body) {
        try {
            return objectMapper.readValue(body, BatchTransformResponse.class);
        } catch (JsonProcessingException ex) {
            throw invalidResponse(ex);
        }
    }

    private List<TransformResponse> validate(BatchTransformResponse response, int attempted) {
        if (response == null || response.results() == null || response.results().size() != attempted) {
            throw invalidResponse(null);
        }
        var results = new ArrayList<TransformResponse>();
        for (var index = 0; index < response.results().size(); index++) {
            var item = response.results().get(index);
            if (item == null || item.index() != index) {
                throw invalidResponse(null);
            }
            validateDraft(item.draft());
            results.add(new TransformResponse(item.draft(), item.errors(), item.warnings()));
        }
        return results;
    }

    private void validateDraft(TransformDraftDto draft) {
        if (draft == null) {
            throw invalidResponse(null);
        }
        if (draft.occurredAt() == null || draft.occurredAt().isBlank()) {
            return;
        }
        try {
            OffsetDateTime.parse(draft.occurredAt());
        } catch (RuntimeException ex) {
            throw invalidResponse(ex);
        }
    }

    private TransformRuntimeException invalidResponse(Throwable cause) {
        var report = TransformRuntimeReport.remoteFailure("remote", "remote_invalid_response", false);
        if (cause == null) {
            return new TransformRuntimeException("remote_invalid_response", "Remote transform response is invalid", report);
        }
        return new TransformRuntimeException("remote_invalid_response", "Remote transform response is invalid", report, cause);
    }

    private static String trimTrailingSlash(String value) {
        var text = value == null || value.isBlank() ? "http://edsp-transform-service:8085" : value.trim();
        while (text.endsWith("/")) {
            text = text.substring(0, text.length() - 1);
        }
        return text;
    }
}
