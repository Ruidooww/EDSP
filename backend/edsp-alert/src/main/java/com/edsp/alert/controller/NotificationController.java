package com.edsp.alert.controller;

import com.edsp.alert.dto.NotificationChannelRequest;
import com.edsp.alert.dto.NotificationSendRequest;
import com.edsp.alert.service.NotificationService;
import com.edsp.common.api.ApiResponse;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Map;
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

    public NotificationController(NotificationService notificationService) {
        this.notificationService = notificationService;
    }

    @GetMapping("/channels")
    public ApiResponse<List<Map<String, Object>>> listChannels() {
        return ApiResponse.ok(notificationService.listChannels());
    }

    @GetMapping("/deliveries")
    public ApiResponse<List<Map<String, Object>>> listDeliveries(
        @RequestParam(value = "limit", defaultValue = "50") int limit
    ) {
        return ApiResponse.ok(notificationService.listDeliveries(limit));
    }

    @PostMapping("/channels")
    public ApiResponse<Map<String, Object>> createChannel(@Valid @RequestBody NotificationChannelRequest request) {
        return ApiResponse.ok(notificationService.createChannel(request), "created");
    }

    @PutMapping("/channels/{id}")
    public ApiResponse<Map<String, Object>> updateChannel(
        @PathVariable("id") long id,
        @Valid @RequestBody NotificationChannelRequest request
    ) {
        return ApiResponse.ok(notificationService.updateChannel(id, request), "updated");
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
        return ApiResponse.ok(notificationService.send(request));
    }
}
