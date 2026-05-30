package com.edsp.core.controller;

import com.edsp.common.api.ApiResponse;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;

@RestControllerAdvice(basePackages = "com.edsp.core")
public class CoreApiExceptionHandler {
    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<ApiResponse<Void>> handleResponseStatusException(ResponseStatusException ex) {
        var reason = ex.getReason();
        var message = reason == null || reason.isBlank()
            ? ex.getStatusCode().toString()
            : reason;
        return ResponseEntity
            .status(ex.getStatusCode())
            .body(ApiResponse.fail(message));
    }
}
