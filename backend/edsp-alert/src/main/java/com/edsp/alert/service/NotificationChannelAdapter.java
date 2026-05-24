package com.edsp.alert.service;

import java.util.Map;

public interface NotificationChannelAdapter {
    String channelType();

    WebhookDeliveryResult send(Map<String, Object> alert, Map<String, Object> channel, String payloadJson);
}
