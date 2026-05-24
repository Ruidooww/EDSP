package com.edsp.alert.service;

public record WebhookDeliveryResult(
    String status,
    Integer responseCode,
    String responseBody,
    String message
) {
}
