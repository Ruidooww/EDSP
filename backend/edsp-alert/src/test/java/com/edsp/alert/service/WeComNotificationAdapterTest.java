package com.edsp.alert.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

class WeComNotificationAdapterTest {
    private static final String WECOM_KEY = "WECHATKEY123456";
    private static final String WECOM_URL = "https://qyapi.weixin.qq.com/cgi-bin/webhook/send?key=" + WECOM_KEY;

    @Test
    void sendsMarkdownPayloadAndTreatsErrcodeZeroAsSuccess() {
        var webhookClient = new StubWebhookClient(
            new WebhookDeliveryResult("success", 200, "{\"errcode\":0,\"errmsg\":\"ok\"}", "webhook_delivered")
        );
        var adapter = adapter(webhookClient);

        var result = adapter.send(alert(), channel(WECOM_URL), "{}");

        assertEquals("wecom", adapter.channelType());
        assertEquals("success", result.status());
        assertEquals(200, result.responseCode());
        assertTrue(webhookClient.lastPayloadJson.contains("\"msgtype\":\"markdown\""));
        assertTrue(webhookClient.lastPayloadJson.contains("\"markdown\""));
        assertFalse(webhookClient.lastPayloadJson.contains(WECOM_KEY));
        assertFalse(result.responseBody().contains(WECOM_KEY));
    }

    @Test
    void treatsTwoHundredWithNonZeroErrcodeAsFailedWithoutLeakingKey() {
        var webhookClient = new StubWebhookClient(
            new WebhookDeliveryResult(
                "success",
                200,
                "{\"errcode\":\"93000-" + WECOM_KEY + "\",\"errmsg\":\"invalid key " + WECOM_KEY + "\"}",
                "webhook_delivered"
            )
        );
        var adapter = adapter(webhookClient);

        var result = adapter.send(alert(), channel(WECOM_URL), "{}");

        assertEquals("failed", result.status());
        assertEquals(200, result.responseCode());
        assertFalse(result.responseBody().contains(WECOM_KEY));
        assertFalse(result.message().contains(WECOM_KEY));
    }

    @Test
    void treatsMalformedTwoHundredResponseAsFailed() {
        var webhookClient = new StubWebhookClient(
            new WebhookDeliveryResult("success", 200, "not-json " + WECOM_KEY, "webhook_delivered")
        );
        var adapter = adapter(webhookClient);

        var result = adapter.send(alert(), channel(WECOM_URL), "{}");

        assertEquals("failed", result.status());
        assertEquals(200, result.responseCode());
        assertFalse(result.responseBody().contains(WECOM_KEY));
        assertFalse(result.message().contains(WECOM_KEY));
    }

    @Test
    void rejectsInvalidWeComWebhookUrlBeforeSending() {
        var webhookClient = new StubWebhookClient(
            new WebhookDeliveryResult("success", 200, "{\"errcode\":0}", "webhook_delivered")
        );
        var adapter = adapter(webhookClient);

        assertInvalid(adapter, "http://qyapi.weixin.qq.com/cgi-bin/webhook/send?key=" + WECOM_KEY);
        assertInvalid(adapter, "https://example.test/cgi-bin/webhook/send?key=" + WECOM_KEY);
        assertInvalid(adapter, "https://qyapi.weixin.qq.com/not-webhook/send?key=" + WECOM_KEY);
        assertInvalid(adapter, "https://qyapi.weixin.qq.com/cgi-bin/webhook/send");
        assertEquals(0, webhookClient.calls);
    }

    private void assertInvalid(WeComNotificationAdapter adapter, String endpointUrl) {
        var error = assertThrows(ResponseStatusException.class, () -> adapter.send(alert(), channel(endpointUrl), "{}"));
        assertEquals(HttpStatus.BAD_REQUEST, error.getStatusCode());
        assertEquals("invalid_wecom_webhook_url", error.getReason());
    }

    private Map<String, Object> alert() {
        return Map.of(
            "id", 101L,
            "title", "Suspicious login",
            "severity", "high",
            "status", "open"
        );
    }

    private WeComNotificationAdapter adapter(StubWebhookClient webhookClient) {
        return new WeComNotificationAdapter(webhookClient, new ObjectMapper());
    }

    private Map<String, Object> channel(String endpointUrl) {
        return Map.of(
            "id", 9L,
            "channel_type", "wecom",
            "endpoint_url", endpointUrl,
            "enabled", true
        );
    }

    private static class StubWebhookClient extends WebhookClient {
        private final WebhookDeliveryResult result;
        private int calls;
        private String lastPayloadJson;

        private StubWebhookClient(WebhookDeliveryResult result) {
            this.result = result;
        }

        @Override
        public WebhookDeliveryResult postJson(String endpointUrl, String payloadJson) {
            calls += 1;
            lastPayloadJson = payloadJson;
            return result;
        }
    }
}
