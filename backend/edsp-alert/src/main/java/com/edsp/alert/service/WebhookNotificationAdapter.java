package com.edsp.alert.service;

import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public class WebhookNotificationAdapter implements NotificationChannelAdapter {
    private final WebhookClient webhookClient;

    public WebhookNotificationAdapter(WebhookClient webhookClient) {
        this.webhookClient = webhookClient;
    }

    @Override
    public String channelType() {
        return "webhook";
    }

    @Override
    public WebhookDeliveryResult send(Map<String, Object> alert, Map<String, Object> channel, String payloadJson) {
        var endpointUrl = channel.get("endpoint_url") == null ? "" : String.valueOf(channel.get("endpoint_url")).trim();
        return webhookClient.postJson(endpointUrl, payloadJson);
    }
}
