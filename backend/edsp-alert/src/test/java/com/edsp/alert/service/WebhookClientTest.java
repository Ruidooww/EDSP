package com.edsp.alert.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Method;
import org.junit.jupiter.api.Test;

class WebhookClientTest {
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
}
