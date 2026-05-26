package com.edsp.alert.controller;

import com.edsp.alert.dto.NotificationChannelRequest;
import com.edsp.alert.dto.AlertNotificationSendRequest;
import com.edsp.alert.dto.NotificationSendRequest;
import com.edsp.alert.service.AlertNotificationService;
import com.edsp.alert.service.NotificationService;
import com.edsp.common.api.ApiResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.server.ResponseStatusException;
import jakarta.validation.Valid;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/notifications")
public class NotificationController {
    private final NotificationService notificationService;
    private final AlertNotificationService alertNotificationService;

    public NotificationController(
        NotificationService notificationService,
        AlertNotificationService alertNotificationService
    ) {
        this.notificationService = notificationService;
        this.alertNotificationService = alertNotificationService;
    }

    @GetMapping("/channels")
    public ApiResponse<List<Map<String, Object>>> listChannels(
        @RequestParam(value = "secretStorageStatus", required = false) String secretStorageStatus,
        @RequestParam(value = "enabled", required = false) String enabled
    ) {
        return ApiResponse.ok(notificationService.listChannels(secretStorageStatus, enabled));
    }

    @GetMapping("/secret-backfill/dry-run")
    public ApiResponse<Map<String, Object>> secretBackfillDryRun(
        @RequestParam(value = "enabled", required = false) String enabled,
        @RequestParam(value = "channelType", required = false) String channelType,
        @RequestParam(value = "limit", required = false) String limit
    ) {
        return ApiResponse.ok(notificationService.secretBackfillDryRun(enabled, channelType, limit));
    }

    @PostMapping("/secret-backfill/execute")
    public ApiResponse<Map<String, Object>> executeSecretBackfill(
        @RequestBody(required = false) Map<String, Object> request
    ) {
        var body = request == null ? Map.<String, Object>of() : request;
        return ApiResponse.ok(notificationService.executeSecretBackfill(body), "executed");
    }

    @GetMapping("/secret-backfill/runs")
    public ApiResponse<Map<String, Object>> listSecretBackfillRuns(
        @RequestParam(value = "status", required = false) String status,
        @RequestParam(value = "limit", required = false) String limit
    ) {
        return ApiResponse.ok(notificationService.listSecretBackfillRuns(status, limit));
    }

    @GetMapping("/secret-backfill/runs/{id}")
    public ApiResponse<Map<String, Object>> secretBackfillRunDetail(@PathVariable("id") long id) {
        return ApiResponse.ok(notificationService.secretBackfillRunDetail(id));
    }

    @GetMapping("/deliveries")
    public ApiResponse<List<Map<String, Object>>> listDeliveries(
        @RequestParam(value = "limit", defaultValue = "50") int limit,
        @RequestParam(value = "alertId", required = false) Long alertId,
        @RequestParam(value = "status", required = false) String status,
        @RequestParam(value = "channelType", required = false) String channelType,
        @RequestParam(value = "channelId", required = false) Long channelId
    ) {
        return ApiResponse.ok(notificationService.listDeliveries(limit, alertId, status, channelType, channelId));
    }

    @PostMapping("/channels")
    public ApiResponse<Map<String, Object>> createChannel(@Valid @RequestBody NotificationChannelRequest request) {
        return ApiResponse.ok(notificationService.createChannel(request), "created");
    }

    @PutMapping("/channels/{id}")
    public ApiResponse<Map<String, Object>> updateChannel(
        @PathVariable("id") long id,
        @RequestBody(required = false) Map<String, Object> request
    ) {
        var body = request == null ? Map.<String, Object>of() : request;
        return ApiResponse.ok(notificationService.updateChannel(id, channelRequest(body), channelFields(body)), "updated");
    }

    @DeleteMapping("/channels/{id}")
    public ApiResponse<Map<String, Object>> deleteChannel(@PathVariable("id") long id) {
        return ApiResponse.ok(notificationService.deleteChannel(id), "deleted");
    }

    @PostMapping("/channels/{id}/test")
    public ApiResponse<Map<String, Object>> testChannel(@PathVariable("id") long id) {
        return ApiResponse.ok(notificationService.testChannel(id));
    }

    @PostMapping("/send")
    public ApiResponse<Map<String, Object>> send(@Valid @RequestBody NotificationSendRequest request) {
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "use_alert_notification_endpoint");
    }

    @PostMapping("/alerts/send")
    public ApiResponse<Map<String, Object>> sendAlert(
        @Valid @RequestBody AlertNotificationSendRequest request
    ) {
        return ApiResponse.ok(alertNotificationService.send(request.alertId(), request.channelId()));
    }

    @PostMapping("/deliveries/{id}/retry")
    public ApiResponse<Map<String, Object>> retryDelivery(
        @PathVariable("id") long id,
        @RequestBody(required = false) Map<String, Object> request
    ) {
        if (request != null && !request.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid_request_contract");
        }
        return ApiResponse.ok(alertNotificationService.retryDelivery(id));
    }

    @ExceptionHandler({HttpMessageNotReadableException.class, MethodArgumentNotValidException.class})
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ApiResponse<Map<String, Object>> invalidRequestContract() {
        return ApiResponse.fail("invalid_request_contract");
    }

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<ApiResponse<Map<String, Object>>> responseStatus(ResponseStatusException ex) {
        return ResponseEntity.status(ex.getStatusCode()).body(ApiResponse.fail(ex.getReason()));
    }

    private NotificationChannelRequest channelRequest(Map<String, Object> body) {
        return new NotificationChannelRequest(
            stringOrNull(body.get("name")),
            stringOrNull(body.get("channelType")),
            endpointValue(body),
            body.containsKey("description") ? stringOrNull(body.get("description")) : null,
            booleanOrNull(body.get("enabled")),
            configOrNull(body.get("config"))
        );
    }

    private Set<String> channelFields(Map<String, Object> body) {
        var fields = new LinkedHashSet<String>();
        if (body.containsKey("name")) {
            fields.add("name");
        }
        if (body.containsKey("channelType") && body.get("channelType") != null) {
            fields.add("channelType");
        }
        if (body.containsKey("webhookUrl")) {
            fields.add("webhookUrl");
        }
        if (body.containsKey("endpointUrl")) {
            fields.add("webhookUrl");
            fields.add("endpointUrl");
        }
        if (body.containsKey("description")) {
            fields.add("description");
        }
        if (body.containsKey("enabled") && body.get("enabled") != null) {
            fields.add("enabled");
        }
        if (body.containsKey("config") && body.get("config") != null) {
            fields.add("config");
        }
        return fields;
    }

    private String endpointValue(Map<String, Object> body) {
        if (body.containsKey("webhookUrl")) {
            return stringOrNull(body.get("webhookUrl"));
        }
        if (body.containsKey("endpointUrl")) {
            return stringOrNull(body.get("endpointUrl"));
        }
        return null;
    }

    private String stringOrNull(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private Boolean booleanOrNull(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Boolean flag) {
            return flag;
        }
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid_request_contract");
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> configOrNull(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Map<?, ?> map) {
            return (Map<String, Object>) map;
        }
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid_request_contract");
    }
}
