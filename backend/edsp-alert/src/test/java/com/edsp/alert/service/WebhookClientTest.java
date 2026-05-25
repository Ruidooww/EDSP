package com.edsp.alert.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.lang.reflect.Method;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;

class WebhookClientTest {
    @Test
    void postJsonUsesInjectedHttpClientAndSavesSanitizedResponse() throws Exception {
        var httpClient = mock(HttpClient.class);
        @SuppressWarnings("unchecked")
        HttpResponse<String> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(200);
        when(response.body()).thenReturn("{\"errcode\":0,\"echo\":\"WECHATKEY123456\"}");
        when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class))).thenReturn(response);
        var client = new WebhookClient(httpClient);

        var result = client.postJson(
            "https://qyapi.weixin.qq.com/cgi-bin/webhook/send?key=WECHATKEY123456",
            "{\"msgtype\":\"markdown\"}"
        );

        assertEquals("success", result.status());
        assertEquals(200, result.responseCode());
        assertFalse(result.responseBody().contains("WECHATKEY123456"));
        verify(httpClient).send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class));
    }

    @Test
    void postJsonReturnsFailedDeliveryForIoExceptionWithoutLeakingEndpointSecrets() throws Exception {
        var httpClient = mock(HttpClient.class);
        when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
            .thenThrow(new IOException("connect WECHATKEY123456 failed"));
        var client = new WebhookClient(httpClient);

        var result = client.postJson(
            "https://qyapi.weixin.qq.com/cgi-bin/webhook/send?key=WECHATKEY123456",
            "{\"msgtype\":\"markdown\"}"
        );

        assertEquals("failed", result.status());
        assertEquals(null, result.responseCode());
        assertFalse(result.responseBody().contains("WECHATKEY123456"));
        assertFalse(result.message().contains("WECHATKEY123456"));
    }

    @Test
    void unsupportedOrRelativeUrlsReturnFailedDeliveryWithoutSending() {
        var client = new WebhookClient();

        var unsupportedScheme = client.postJson("ftp://example.test/webhook", "{}");
        var relativeUrl = client.postJson("/relative/webhook", "{}");

        assertEquals("failed", unsupportedScheme.status());
        assertEquals("invalid_webhook_url", unsupportedScheme.message());
        assertEquals("failed", relativeUrl.status());
        assertEquals("invalid_webhook_url", relativeUrl.message());
    }

    @Test
    void cleanRedactsPathTokensDecodedQueryValuesAndTruncates() throws Exception {
        var client = new WebhookClient();
        Method clean = WebhookClient.class.getDeclaredMethod("clean", String.class, String.class);
        clean.setAccessible(true);

        var pathSecret = "PATHSECRET123456";
        var decodedQuerySecret = "abc/def";
        var querySecret = "QUERYSECRET123456";
        var endpoint = "https://hook.example.test/robot/" + pathSecret + "/send"
            + "?token=abc%2Fdef&access_token=" + querySecret;
        var response = ("uri contains " + pathSecret + " and " + decodedQuerySecret + " and " + querySecret + " ")
            .repeat(80);

        var cleaned = (String) clean.invoke(client, response, endpoint);

        assertFalse(cleaned.contains(pathSecret));
        assertFalse(cleaned.contains(decodedQuerySecret));
        assertFalse(cleaned.contains(querySecret));
        assertTrue(cleaned.contains("[redacted]"));
        assertTrue(cleaned.length() <= 1000);
    }

    @Test
    void cleanRedactsGenericSecretPatternsEvenWhenTheyAreNotEndpointParts() throws Exception {
        var client = new WebhookClient();
        Method clean = WebhookClient.class.getDeclaredMethod("clean", String.class, String.class);
        clean.setAccessible(true);

        var endpoint = "https://hook.example.test/webhook";
        var response = "endpoint " + endpoint
            + " Authorization: Bearer BEARERSECRET123456"
            + " signature=SIGNATURESECRET123456"
            + " access_token=ACCESSSECRET123456";

        var cleaned = (String) clean.invoke(client, response, endpoint);

        assertFalse(cleaned.contains(endpoint));
        assertFalse(cleaned.contains("BEARERSECRET123456"));
        assertFalse(cleaned.contains("SIGNATURESECRET123456"));
        assertFalse(cleaned.contains("ACCESSSECRET123456"));
        assertTrue(cleaned.contains("[redacted]"));
    }

    @Test
    void cleanRedactsShortFeishuHookToken() throws Exception {
        var client = new WebhookClient();
        Method clean = WebhookClient.class.getDeclaredMethod("clean", String.class, String.class);
        clean.setAccessible(true);

        var endpoint = "https://open.feishu.cn/open-apis/bot/v2/hook/abc123";
        var cleaned = (String) clean.invoke(client, "provider echoed abc123", endpoint);

        assertFalse(cleaned.contains("abc123"));
        assertTrue(cleaned.contains("[redacted]"));
    }
}
