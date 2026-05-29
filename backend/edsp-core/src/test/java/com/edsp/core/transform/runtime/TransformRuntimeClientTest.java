package com.edsp.core.transform.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.edsp.core.config.TransformRemoteShadowConfig;
import com.edsp.core.config.TransformRuntimeConfig;
import com.edsp.transform.contract.BatchTransformRequest;
import com.edsp.transform.contract.BatchTransformResponse;
import com.edsp.transform.contract.TransformDraftDto;
import com.edsp.transform.contract.TransformFieldMappingDto;
import com.edsp.transform.contract.TransformMappingPlanDto;
import com.edsp.transform.contract.TransformOptionsDto;
import com.edsp.transform.contract.TransformResponse;
import com.edsp.transform.contract.TransformResultItem;
import com.edsp.transform.standardevent.StandardEventTransformService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class TransformRuntimeClientTest {
    private final ObjectMapper objectMapper = new ObjectMapper();
    private HttpServer server;
    private String lastPath;

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void remoteRuntimeUsesBatchEndpointAndReturnsRemoteResults() throws Exception {
        server = startServer(200, objectMapper.writeValueAsString(new BatchTransformResponse(
            List.of(new TransformResultItem(0, draft("REMOTE-1"), List.of(), List.of())),
            List.of(),
            List.of()
        )));
        var client = new RemoteTransformRuntimeClient(objectMapper, "http://127.0.0.1:" + server.getAddress().getPort(), 1000);

        var result = client.transform(request(1));

        assertEquals("/api/transform/standard-events/batch", lastPath);
        assertEquals("REMOTE-1", result.results().get(0).draft().externalId());
        assertEquals("remote", result.report().mode());
        assertEquals(true, result.report().remoteAttempted());
        assertEquals(true, result.report().remoteSucceeded());
        assertEquals(false, result.report().fallbackUsed());
    }

    @Test
    void remoteRuntimeRejectsBatchResponsesWithWrongSizeOrIndex() throws Exception {
        server = startServer(200, objectMapper.writeValueAsString(new BatchTransformResponse(
            List.of(new TransformResultItem(1, draft("REMOTE-1"), List.of(), List.of())),
            List.of(),
            List.of()
        )));
        var client = new RemoteTransformRuntimeClient(objectMapper, "http://127.0.0.1:" + server.getAddress().getPort(), 1000);

        var ex = assertThrows(TransformRuntimeException.class, () -> client.transform(request(2)));

        assertEquals("remote_invalid_response", ex.failureType());
        assertEquals("remote", ex.report().mode());
        assertEquals(true, ex.report().remoteAttempted());
        assertEquals(false, ex.report().remoteSucceeded());
        assertEquals(false, ex.report().fallbackUsed());
    }

    @Test
    void remoteRuntimeRejectsNullDraftAndMalformedOccurredAtAsInvalidResponse() throws Exception {
        server = startServer(200, objectMapper.writeValueAsString(new BatchTransformResponse(
            List.of(new TransformResultItem(0, draftWithOccurredAt("REMOTE-1", "not-a-date"), List.of(), List.of())),
            List.of(),
            List.of()
        )));
        var malformedClient = new RemoteTransformRuntimeClient(objectMapper, "http://127.0.0.1:" + server.getAddress().getPort(), 1000);

        var malformedTime = assertThrows(TransformRuntimeException.class, () -> malformedClient.transform(request(1)));

        assertEquals("remote_invalid_response", malformedTime.failureType());

        server.stop(0);
        server = startServer(200, objectMapper.writeValueAsString(new BatchTransformResponse(
            List.of(new TransformResultItem(0, null, List.of(), List.of())),
            List.of(),
            List.of()
        )));
        var nullDraftClient = new RemoteTransformRuntimeClient(objectMapper, "http://127.0.0.1:" + server.getAddress().getPort(), 1000);

        var nullDraft = assertThrows(TransformRuntimeException.class, () -> nullDraftClient.transform(request(1)));

        assertEquals("remote_invalid_response", nullDraft.failureType());
    }

    @Test
    void remoteRuntimeMapsNon2xxToUnavailableWithoutFallback() throws Exception {
        server = startServer(503, "{}");
        var client = new RemoteTransformRuntimeClient(objectMapper, "http://127.0.0.1:" + server.getAddress().getPort(), 1000);

        var ex = assertThrows(TransformRuntimeException.class, () -> client.transform(request(1)));

        assertEquals("remote_unavailable", ex.failureType());
        assertEquals("remote", ex.report().mode());
        assertEquals(false, ex.report().fallbackUsed());
    }

    @Test
    void fallbackRuntimeUsesLocalResultWhenRemoteFails() {
        var remote = new TransformRuntimeClient() {
            @Override
            public String mode() {
                return "remote";
            }

            @Override
            public TransformBatchResult transform(BatchTransformRequest request) {
                throw new TransformRuntimeException(
                    "remote_unavailable",
                    "remote unavailable",
                    TransformRuntimeReport.remoteFailure("remote", "remote_unavailable", false)
                );
            }
        };
        var localResponse = new TransformResponse(draft("LOCAL-1"), List.of(), List.of());
        var local = new TransformRuntimeClient() {
            @Override
            public String mode() {
                return "local";
            }

            @Override
            public TransformBatchResult transform(BatchTransformRequest request) {
                return new TransformBatchResult(List.of(localResponse), TransformRuntimeReport.disabled());
            }
        };
        var fallback = new FallbackTransformRuntimeClient(remote, local);

        var result = fallback.transform(request(1));

        assertEquals(List.of(localResponse), result.results());
        assertEquals("fallback", result.report().mode());
        assertEquals(true, result.report().remoteAttempted());
        assertEquals(false, result.report().remoteSucceeded());
        assertEquals(true, result.report().fallbackUsed());
        assertEquals("remote_unavailable", result.report().failureType());
    }

    @Test
    void fallbackRuntimeUsesLocalResultWhenRemoteReturnsInvalidResponse() throws Exception {
        server = startServer(200, objectMapper.writeValueAsString(new BatchTransformResponse(
            List.of(new TransformResultItem(0, draftWithOccurredAt("REMOTE-1", "not-a-date"), List.of(), List.of())),
            List.of(),
            List.of()
        )));
        var remote = new RemoteTransformRuntimeClient(objectMapper, "http://127.0.0.1:" + server.getAddress().getPort(), 1000);
        var localResponse = new TransformResponse(draft("LOCAL-1"), List.of(), List.of());
        var local = new TransformRuntimeClient() {
            @Override
            public String mode() {
                return "local";
            }

            @Override
            public TransformBatchResult transform(BatchTransformRequest request) {
                return new TransformBatchResult(List.of(localResponse), TransformRuntimeReport.disabled());
            }
        };
        var fallback = new FallbackTransformRuntimeClient(remote, local);

        var result = fallback.transform(request(1));

        assertEquals(List.of(localResponse), result.results());
        assertEquals("fallback", result.report().mode());
        assertEquals(true, result.report().remoteAttempted());
        assertEquals(false, result.report().remoteSucceeded());
        assertEquals(true, result.report().fallbackUsed());
        assertEquals("remote_invalid_response", result.report().failureType());
    }

    @Test
    void configRejectsUnknownRuntimeModeInsteadOfSilentlyUsingLocal() {
        var config = new TransformRuntimeConfig();

        assertThrows(IllegalStateException.class, () -> config.transformRuntimeClient(
            new StandardEventTransformService(),
            objectMapper,
            "surprise",
            "http://127.0.0.1:18085",
            1000
        ));
    }

    @Test
    void shadowConfigOnlyEnablesRemoteShadowForLocalRuntimeMode() {
        var config = new TransformRemoteShadowConfig();

        var shadow = config.transformRemoteShadowClient(
            objectMapper,
            true,
            "remote",
            "http://127.0.0.1:18085",
            1000
        );

        assertFalse(shadow.enabled());
    }

    @Test
    void contractSupportCarriesFieldMappingDetailsWithoutParsingRules() {
        var mappingPlan = TransformContractSupport.mappingPlan(new TransformMappingPlanDto(
            Map.of("user_account", "actor"),
            List.of("id"),
            List.of(new TransformFieldMappingDto("user_account", "actor", " lower "))
        ));

        assertEquals(Map.of("user_account", "actor"), mappingPlan.fieldMappings());
        assertEquals(List.of("id"), mappingPlan.dedupFields());
        assertEquals(1, mappingPlan.fieldMappingDetails().size());
        assertEquals("user_account", mappingPlan.fieldMappingDetails().get(0).sourceField());
        assertEquals("actor", mappingPlan.fieldMappingDetails().get(0).standardField());
        assertEquals(" lower ", mappingPlan.fieldMappingDetails().get(0).transformRule());
    }

    private HttpServer startServer(int status, String body) throws IOException {
        var httpServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        httpServer.createContext("/api/transform/standard-events/batch", exchange -> {
            lastPath = exchange.getRequestURI().getPath();
            var response = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, response.length);
            try (var output = exchange.getResponseBody()) {
                output.write(response);
            }
        });
        httpServer.start();
        return httpServer;
    }

    private BatchTransformRequest request(int rows) {
        var inputRows = new java.util.ArrayList<Map<String, Object>>();
        for (var index = 0; index < rows; index++) {
            inputRows.add(Map.of("id", "ALERT-" + index, "create_time", "2026-05-20 10:30:00"));
        }
        return new BatchTransformRequest(
            inputRows,
            new TransformMappingPlanDto(Map.of("id", "externalId", "create_time", "occurredAt"), List.of("id")),
            new TransformOptionsDto(7L, 11L, "sec_alert_event", "sync_once")
        );
    }

    private TransformDraftDto draft(String externalId) {
        return draftWithOccurredAt(externalId, "2026-05-20T10:30:00+08:00");
    }

    private TransformDraftDto draftWithOccurredAt(String externalId, String occurredAt) {
        return new TransformDraftDto(
            "remote-source",
            externalId,
            "Remote event",
            occurredAt,
            "actor",
            "asset",
            "event",
            "asset",
            null,
            "detected",
            "high",
            80,
            "dedup-" + externalId,
            Map.of(),
            Map.of()
        );
    }
}
