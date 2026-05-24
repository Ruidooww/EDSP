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

class FeishuNotificationAdapterTest {
    private static final String FEISHU_TOKEN = "FEISHUTOKEN123456";
    private static final String FEISHU_URL =
        "https://open.feishu.cn/open-apis/bot/v2/hook/" + FEISHU_TOKEN;

    @Test
    void sendsTextPayloadWithoutTokenOrWeComShape() {
        var webhookClient = new StubWebhookClient(
            new WebhookDeliveryResult("success", 200, "{\"StatusCode\":0,\"StatusMessage\":\"success\"}", "ok")
        );
        var adapter = adapter(webhookClient);

        var result = adapter.send(alert(), channel(FEISHU_URL), "{}");

        assertEquals("feishu", adapter.channelType());
        assertEquals("success", result.status());
        assertEquals(200, result.responseCode());
        assertTrue(webhookClient.lastEndpointUrl.startsWith("https://open.feishu.cn/open-apis/bot/v2/hook/"));
        assertTrue(webhookClient.lastPayloadJson.contains("\"msg_type\":\"text\""));
        assertTrue(webhookClient.lastPayloadJson.contains("\"text\""));
        assertTrue(webhookClient.lastPayloadJson.contains("Suspicious login"));
        assertFalse(webhookClient.lastPayloadJson.contains(FEISHU_TOKEN));
        assertFalse(webhookClient.lastPayloadJson.contains("\"msgtype\""));
        assertFalse(webhookClient.lastPayloadJson.contains("\"markdown\""));
    }

    @Test
    void treatsStatusCodeZeroAsSuccess() {
        var result = adapter(new StubWebhookClient(
            new WebhookDeliveryResult("success", 200, "{\"StatusCode\":0,\"StatusMessage\":\"success\"}", "ok")
        )).send(alert(), channel(FEISHU_URL), "{}");

        assertEquals("success", result.status());
        assertEquals("feishu_delivered", result.message());
    }

    @Test
    void treatsCodeZeroAsSuccess() {
        var result = adapter(new StubWebhookClient(
            new WebhookDeliveryResult("success", 200, "{\"code\":0,\"msg\":\"success\"}", "ok")
        )).send(alert(), channel(FEISHU_URL), "{}");

        assertEquals("success", result.status());
        assertEquals("feishu_delivered", result.message());
    }

    @Test
    void treatsNonZeroStatusCodeAsFailedWithoutLeakingToken() {
        var result = adapter(new StubWebhookClient(
            new WebhookDeliveryResult(
                "success",
                200,
                "{\"StatusCode\":19001,\"StatusMessage\":\"bad token " + FEISHU_TOKEN + "\"}",
                "ok"
            )
        )).send(alert(), channel(FEISHU_URL), "{}");

        assertEquals("failed", result.status());
        assertEquals(200, result.responseCode());
        assertEquals("feishu_status_code_19001", result.message());
        assertNoTokenLeak(result);
    }

    @Test
    void treatsNonZeroCodeAsFailedWithoutLeakingToken() {
        var result = adapter(new StubWebhookClient(
            new WebhookDeliveryResult(
                "success",
                200,
                "{\"code\":9499,\"msg\":\"bad token " + FEISHU_TOKEN + "\"}",
                "ok"
            )
        )).send(alert(), channel(FEISHU_URL), "{}");

        assertEquals("failed", result.status());
        assertEquals(200, result.responseCode());
        assertEquals("feishu_code_9499", result.message());
        assertNoTokenLeak(result);
    }

    @Test
    void treatsMalformedTwoHundredResponseAsFailedWithoutLeakingToken() {
        var result = adapter(new StubWebhookClient(
            new WebhookDeliveryResult("success", 200, "not-json " + FEISHU_TOKEN, "ok")
        )).send(alert(), channel(FEISHU_URL), "{}");

        assertEquals("failed", result.status());
        assertEquals(200, result.responseCode());
        assertEquals("feishu_malformed_response", result.message());
        assertNoTokenLeak(result);
    }

    @Test
    void treatsNonTwoHundredHttpResponseAsFailedWithoutLeakingToken() {
        var result = adapter(new StubWebhookClient(
            new WebhookDeliveryResult("failed", 500, "bad token " + FEISHU_TOKEN, "webhook_http_500")
        )).send(alert(), channel(FEISHU_URL), "{}");

        assertEquals("failed", result.status());
        assertEquals(500, result.responseCode());
        assertEquals("feishu_status_code_500", result.message());
        assertNoTokenLeak(result);
    }

    @Test
    void treatsTimeoutLikeFailureAsFailedWithoutLeakingToken() {
        var result = adapter(new StubWebhookClient(
            new WebhookDeliveryResult("failed", null, "timeout " + FEISHU_TOKEN, "webhook_timeout")
        )).send(alert(), channel(FEISHU_URL), "{}");

        assertEquals("failed", result.status());
        assertEquals(null, result.responseCode());
        assertEquals("feishu_malformed_response", result.message());
        assertNoTokenLeak(result);
    }

    @Test
    void rejectsInvalidFeishuWebhookUrlBeforeSending() {
        var webhookClient = new StubWebhookClient(
            new WebhookDeliveryResult("success", 200, "{\"StatusCode\":0}", "ok")
        );
        var adapter = adapter(webhookClient);

        assertInvalid(adapter, "http://open.feishu.cn/open-apis/bot/v2/hook/" + FEISHU_TOKEN);
        assertInvalid(adapter, "https://example.test/open-apis/bot/v2/hook/" + FEISHU_TOKEN);
        assertInvalid(adapter, "https://open.feishu.cn/open-apis/bot/v1/hook/" + FEISHU_TOKEN);
        assertInvalid(adapter, "https://open.feishu.cn/open-apis/bot/v2/hook/");
        assertInvalid(adapter, "https://open.feishu.cn/open-apis/bot/v2/hook/" + FEISHU_TOKEN + "/extra");
        assertEquals(0, webhookClient.calls);
    }

    private void assertInvalid(FeishuNotificationAdapter adapter, String endpointUrl) {
        var error = assertThrows(ResponseStatusException.class, () -> adapter.send(alert(), channel(endpointUrl), "{}"));
        assertEquals(HttpStatus.BAD_REQUEST, error.getStatusCode());
        assertEquals("invalid_feishu_webhook_url", error.getReason());
        assertFalse(String.valueOf(error.getReason()).contains(FEISHU_TOKEN));
    }

    private void assertNoTokenLeak(WebhookDeliveryResult result) {
        assertFalse(String.valueOf(result.responseBody()).contains(FEISHU_TOKEN));
        assertFalse(String.valueOf(result.message()).contains(FEISHU_TOKEN));
    }

    private Map<String, Object> alert() {
        return Map.of(
            "id", 101L,
            "title", "Suspicious login",
            "severity", "high",
            "status", "open"
        );
    }

    private FeishuNotificationAdapter adapter(StubWebhookClient webhookClient) {
        return new FeishuNotificationAdapter(webhookClient, new ObjectMapper());
    }

    private Map<String, Object> channel(String endpointUrl) {
        return Map.of(
            "id", 9L,
            "channel_type", "feishu",
            "endpoint_url", endpointUrl,
            "enabled", true
        );
    }

    private static class StubWebhookClient extends WebhookClient {
        private final WebhookDeliveryResult result;
        private int calls;
        private String lastEndpointUrl;
        private String lastPayloadJson;

        private StubWebhookClient(WebhookDeliveryResult result) {
            this.result = result;
        }

        @Override
        public WebhookDeliveryResult postJson(String endpointUrl, String payloadJson) {
            calls += 1;
            lastEndpointUrl = endpointUrl;
            lastPayloadJson = payloadJson;
            return result;
        }
    }
}
