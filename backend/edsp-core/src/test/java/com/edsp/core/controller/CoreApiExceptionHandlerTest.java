package com.edsp.core.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

class CoreApiExceptionHandlerTest {
    @Test
    void freshnessGateReasonsReturnApiResponseFailBody() {
        var handler = new CoreApiExceptionHandler();

        assertBadRequestReason(handler, "shadow_run_stale_after_plan_edit");
        assertBadRequestReason(handler, "shadow_run_plan_fingerprint_missing");
        assertBadRequestReason(handler, "shadow_run_plan_fingerprint_invalid");
    }

    @Test
    void responseStatusExceptionPreservesConflictStatusAndReason() {
        var handler = new CoreApiExceptionHandler();
        var response = handler.handleResponseStatusException(
            new ResponseStatusException(HttpStatus.CONFLICT, "plan_already_activated")
        );

        assertEquals(HttpStatus.CONFLICT, response.getStatusCode());
        var body = response.getBody();
        assertNotNull(body);
        assertFalse(body.success());
        assertNull(body.data());
        assertEquals("plan_already_activated", body.message());
    }

    @Test
    void responseStatusExceptionWithoutReasonFallsBackToStatusCode() {
        var handler = new CoreApiExceptionHandler();
        var response = handler.handleResponseStatusException(
            new ResponseStatusException(HttpStatus.NOT_FOUND)
        );

        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
        var body = response.getBody();
        assertNotNull(body);
        assertFalse(body.success());
        assertNull(body.data());
        assertEquals("404 NOT_FOUND", body.message());
    }

    private void assertBadRequestReason(CoreApiExceptionHandler handler, String reason) {
        var response = handler.handleResponseStatusException(
            new ResponseStatusException(HttpStatus.BAD_REQUEST, reason)
        );

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        var body = response.getBody();
        assertNotNull(body);
        assertFalse(body.success());
        assertNull(body.data());
        assertEquals(reason, body.message());
    }
}
