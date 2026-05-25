package com.edsp.alert.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.web.server.ResponseStatusException;

class AlertNotificationServiceTest {
    private static final String TEST_MASTER_KEY =
        "MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=";

    private JdbcTemplate jdbcTemplate;
    private StubWebhookClient webhookClient;
    private StubNotificationChannelAdapter feishuAdapter;
    private AlertNotificationService service;
    private ObjectMapper objectMapper;
    private NotificationChannelAdapterRegistry adapterRegistry;

    @BeforeEach
    void setUp() {
        var dataSource = new JdbcDataSource();
        dataSource.setURL(
            "jdbc:h2:mem:alert_notification_service_test_" + System.nanoTime()
                + ";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;"
                + "DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1;"
                + "INIT=CREATE DOMAIN IF NOT EXISTS JSONB AS JSON\\;"
                + "CREATE DOMAIN IF NOT EXISTS TIMESTAMPTZ AS TIMESTAMP WITH TIME ZONE"
        );
        dataSource.setUser("sa");
        dataSource.setPassword("");

        jdbcTemplate = new JdbcTemplate(dataSource);
        jdbcTemplate.execute("""
            create table alerts (
                id bigserial primary key,
                title varchar(240) not null,
                severity varchar(32) not null,
                status varchar(32) not null default 'open',
                rule_id bigint,
                subject_type varchar(80),
                subject_ref varchar(160),
                detail_json jsonb not null default '{}',
                source_system varchar(80),
                external_id varchar(160),
                alert_type varchar(80),
                occurred_at timestamptz,
                actor varchar(160),
                asset_ref varchar(160),
                policy_name varchar(180),
                standard_event_id bigint,
                alert_decision_id bigint,
                created_at timestamptz not null default now(),
                updated_at timestamptz not null default now()
            )
            """);
        jdbcTemplate.execute("""
            create table notification_channels (
                id bigserial primary key,
                name varchar(160) not null,
                channel_type varchar(40) not null default 'webhook',
                endpoint_url text,
                endpoint_secret_ciphertext text,
                endpoint_secret_key_version varchar(64),
                endpoint_masked text,
                secret_storage_status varchar(32) not null default 'legacy_plaintext',
                description text,
                config_json jsonb not null default '{}',
                enabled boolean not null default true,
                status varchar(40) not null default 'ready'
            )
            """);
        jdbcTemplate.execute("""
            create table notification_deliveries (
                id bigserial primary key,
                channel_id bigint,
                alert_id bigint,
                title varchar(240) not null,
                severity varchar(32),
                status varchar(32) not null,
                response_code integer,
                response_body text,
                payload_json jsonb not null default '{}',
                failure_type varchar(80),
                failure_reason text,
                retryable boolean not null default false,
                retry_of_delivery_id bigint,
                retry_count integer not null default 0,
                created_at timestamptz not null default now()
            )
            """);
        jdbcTemplate.execute("""
            create table alert_lifecycle_events (
                id bigserial primary key,
                alert_id bigint not null,
                from_status varchar(32),
                to_status varchar(32) not null,
                actor varchar(160),
                note text,
                created_at timestamptz not null default now()
            )
            """);

        webhookClient = new StubWebhookClient();
        var webhookAdapter = new WebhookNotificationAdapter(webhookClient);
        objectMapper = new ObjectMapper();
        var weComAdapter = new WeComNotificationAdapter(webhookClient, objectMapper);
        feishuAdapter = new StubNotificationChannelAdapter("feishu");
        adapterRegistry = new NotificationChannelAdapterRegistry(List.of(webhookAdapter, weComAdapter, feishuAdapter));
        service = new AlertNotificationService(
            jdbcTemplate,
            objectMapper,
            adapterRegistry,
            new NotificationSecretStore(TEST_MASTER_KEY)
        );
    }

    @Test
    void rejectsMissingAlertNonOpenAlertMissingChannelDisabledChannelAndUnsupportedType() {
        var closedAlertId = insertAlert("closed alert", "closed");
        var openAlertId = insertAlert("open alert", "open");
        var disabledChannelId = insertChannel("webhook", "http://example.test/webhook", false);
        var emailChannelId = insertChannel("email", "http://example.test/email", true);

        assertStatus(HttpStatus.NOT_FOUND, () -> service.send(999999L, disabledChannelId));
        assertStatus(HttpStatus.BAD_REQUEST, () -> service.send(closedAlertId, disabledChannelId));
        assertStatus(HttpStatus.NOT_FOUND, () -> service.send(openAlertId, 999999L));
        assertStatus(HttpStatus.BAD_REQUEST, () -> service.send(openAlertId, disabledChannelId));
        assertStatus(HttpStatus.BAD_REQUEST, "unsupported_channel", () -> service.send(openAlertId, emailChannelId));
        assertEquals(0L, countDeliveries());
    }

    @Test
    void keepsPreAdapterBoundariesForFeishuChannels() {
        var closedAlertId = insertAlert("closed feishu alert", "closed");
        var openAlertId = insertAlert("open feishu alert", "open");
        var disabledChannelId = insertChannel(
            "feishu",
            "https://open.feishu.cn/open-apis/bot/v2/hook/FEISHUTOKEN123456",
            false
        );

        assertStatus(HttpStatus.BAD_REQUEST, "alert_not_open", () -> service.send(closedAlertId, disabledChannelId));
        assertStatus(HttpStatus.BAD_REQUEST, "channel_disabled", () -> service.send(openAlertId, disabledChannelId));
        assertEquals(0L, countDeliveries());
        assertEquals(0, feishuAdapter.calls);
    }

    @Test
    void recordsSuccessFailedHttpAndTimeoutLikeFailuresWithoutRealHttpRequests() {
        var successAlertId = insertAlert("success alert", "open");
        var failedAlertId = insertAlert("failed alert", "open");
        var timeoutAlertId = insertAlert("timeout alert", "open");
        var channelId = insertChannel("webhook", "http://example.test/webhook?token=secret", true);

        webhookClient.nextResult = new WebhookDeliveryResult("success", 204, "accepted", "webhook_delivered");
        var success = service.send(successAlertId, channelId);

        webhookClient.nextResult = new WebhookDeliveryResult("failed", 500, "server error", "webhook_http_500");
        var failed = service.send(failedAlertId, channelId);

        webhookClient.nextResult = new WebhookDeliveryResult("failed", null, "webhook_timeout", "webhook_timeout");
        var timeout = service.send(timeoutAlertId, channelId);

        assertEquals("success", success.get("status"));
        assertEquals(204, success.get("responseCode"));
        assertEquals("failed", failed.get("status"));
        assertEquals(500, failed.get("responseCode"));
        assertEquals("failed", timeout.get("status"));
        assertEquals(null, timeout.get("responseCode"));
        assertEquals(3L, countDeliveries());
        assertEquals(1L, countDeliveriesByStatus("success"));
        assertEquals(2L, countDeliveriesByStatus("failed"));
        assertEquals(3, webhookClient.calls);
        assertEquals("http://example.test/webhook?token=secret", webhookClient.lastEndpointUrl);
        assertEquals(true, webhookClient.lastPayloadJson.contains("\"alertId\""));
    }

    @Test
    void sendsEncryptedChannelByResolvingSecretWithoutPersistingPlaintextEndpoint() {
        var alertId = insertAlert("encrypted send", "open");
        var endpoint = "https://example.test/webhook?token=WEBHOOKTOKEN123456";
        var notificationService = new NotificationService(
            jdbcTemplate,
            objectMapper,
            new NotificationSecretStore(TEST_MASTER_KEY)
        );
        var created = notificationService.createChannel(new com.edsp.alert.dto.NotificationChannelRequest(
            "encrypted webhook",
            "webhook",
            endpoint,
            null,
            true,
            Map.of()
        ));
        var channelId = ((Number) created.get("id")).longValue();

        webhookClient.nextResult = new WebhookDeliveryResult("success", 204, "accepted", "webhook_delivered");
        var result = service.send(alertId, channelId);
        var channel = channelRow(channelId);
        var delivery = deliveryRow(((Number) result.get("deliveryId")).longValue());

        assertEquals("success", result.get("status"));
        assertEquals(endpoint, webhookClient.lastEndpointUrl);
        assertEquals(null, channel.get("endpoint_url"));
        assertEquals("encrypted", channel.get("secret_storage_status"));
        assertFalse(String.valueOf(channel.get("endpoint_secret_ciphertext")).contains("WEBHOOKTOKEN123456"));
        assertNoSecretLeak(String.valueOf(normalizeDbValue(delivery.get("payload_json"))));
    }

    @Test
    void encryptedChannelResolveFailureDoesNotCallAdapterWriteDeliveryOrMutateRetrySource() {
        var alertId = insertAlert("encrypted unavailable", "open");
        var endpoint = "https://example.test/webhook?token=WEBHOOKTOKEN123456";
        var notificationService = new NotificationService(
            jdbcTemplate,
            objectMapper,
            new NotificationSecretStore(TEST_MASTER_KEY)
        );
        var created = notificationService.createChannel(new com.edsp.alert.dto.NotificationChannelRequest(
            "encrypted webhook",
            "webhook",
            endpoint,
            null,
            true,
            Map.of()
        ));
        var channelId = ((Number) created.get("id")).longValue();
        jdbcTemplate.update(
            "update notification_channels set endpoint_secret_ciphertext = ? where id = ?",
            "v1:invalid:invalid",
            channelId
        );
        var originalDeliveryId = insertDelivery(channelId, alertId, "failed", true);
        var originalBefore = deliveryRow(originalDeliveryId);
        var beforeCount = countDeliveries();

        assertStatus(HttpStatus.BAD_REQUEST, "notification_secret_unavailable", () -> service.send(alertId, channelId));
        assertStatus(HttpStatus.BAD_REQUEST, "notification_secret_unavailable", () -> service.retryDelivery(originalDeliveryId));

        assertEquals(0, webhookClient.calls);
        assertEquals(beforeCount, countDeliveries());
        assertEquals(0, ((Number) deliveryRow(originalDeliveryId).get("retry_count")).intValue());
        assertOriginalDeliveryUnchangedExceptRetryCount(originalBefore, deliveryRow(originalDeliveryId));
        assertEquals(0L, countAlertLifecycleEvents());
        assertEquals("open", alertStatus(alertId));
    }

    @Test
    void missingChannelResolveFailureDoesNotCallAdapterWriteDeliveryOrIncrementRetryCount() {
        var alertId = insertAlert("missing secret", "open");
        var channelId = insertMissingChannel("webhook", true);
        var originalDeliveryId = insertDelivery(channelId, alertId, "failed", true);
        var originalBefore = deliveryRow(originalDeliveryId);
        var beforeCount = countDeliveries();

        assertStatus(HttpStatus.BAD_REQUEST, "notification_secret_unavailable", () -> service.send(alertId, channelId));
        assertStatus(HttpStatus.BAD_REQUEST, "notification_secret_unavailable", () -> service.retryDelivery(originalDeliveryId));

        assertEquals(0, webhookClient.calls);
        assertEquals(beforeCount, countDeliveries());
        assertEquals(0, ((Number) deliveryRow(originalDeliveryId).get("retry_count")).intValue());
        assertOriginalDeliveryUnchangedExceptRetryCount(originalBefore, deliveryRow(originalDeliveryId));
        assertEquals(0L, countAlertLifecycleEvents());
        assertEquals("open", alertStatus(alertId));
    }

    @Test
    void legacyPlaintextChannelWithoutEndpointIsTreatedAsSecretUnavailable() {
        var alertId = insertAlert("legacy blank secret", "open");
        var channelId = insertChannel("webhook", null, true);
        var originalDeliveryId = insertDelivery(channelId, alertId, "failed", true);
        var beforeCount = countDeliveries();

        assertStatus(HttpStatus.BAD_REQUEST, "notification_secret_unavailable", () -> service.send(alertId, channelId));
        assertStatus(HttpStatus.BAD_REQUEST, "notification_secret_unavailable", () -> service.retryDelivery(originalDeliveryId));

        assertEquals(0, webhookClient.calls);
        assertEquals(beforeCount, countDeliveries());
        assertEquals(0, ((Number) deliveryRow(originalDeliveryId).get("retry_count")).intValue());
        assertEquals(0L, countAlertLifecycleEvents());
        assertEquals("open", alertStatus(alertId));
    }

    @Test
    void recordsStructuredReliabilityFieldsForSuccessAndRetryableFailures() {
        var channelId = insertChannel("webhook", "http://example.test/webhook?token=secret", true);

        webhookClient.nextResult = new WebhookDeliveryResult("success", 204, "accepted", "webhook_delivered");
        var successAlertId = insertAlert("success reliability", "open");
        service.send(successAlertId, channelId);
        assertReliability(successAlertId, null, null, false);

        var retryableCases = List.of(
            new FailureCase("timeout", null, "webhook_timeout"),
            new FailureCase("connection_error", null, "webhook_connection_failed: ConnectException"),
            new FailureCase("http_408", 408, "webhook_http_408"),
            new FailureCase("http_429", 429, "webhook_http_429"),
            new FailureCase("http_5xx", 503, "webhook_http_503")
        );
        for (var testCase : retryableCases) {
            var alertId = insertAlert("retryable " + testCase.expectedType(), "open");
            webhookClient.nextResult = new WebhookDeliveryResult(
                "failed",
                testCase.responseCode(),
                "failure " + testCase.expectedType(),
                testCase.message()
            );

            service.send(alertId, channelId);

            assertReliability(alertId, testCase.expectedType(), testCase.message(), true);
        }
    }

    @Test
    void finalRedactionGuardSanitizesDeliveryStorageAndSendResult() {
        var alertId = insertAlert("secret guard alert", "open");
        var endpoint = "http://example.test/webhook?token=WEBHOOKTOKEN123456";
        var channelId = insertChannel("webhook", endpoint, true);
        webhookClient.nextResult = new WebhookDeliveryResult(
            "failed",
            500,
            "failed endpoint " + endpoint + " token=WEBHOOKTOKEN123456",
            "webhook_http_500 Authorization: Bearer BEARERSECRET123456"
        );

        var result = service.send(alertId, channelId);
        var deliveryId = ((Number) result.get("deliveryId")).longValue();
        var row = deliveryRow(deliveryId);

        assertNoSecretLeak(String.valueOf(result.get("responseBody")));
        assertNoSecretLeak(String.valueOf(result.get("message")));
        assertNoSecretLeak(String.valueOf(result.get("failureReason")));
        assertNoSecretLeak(String.valueOf(normalizeDbValue(row.get("response_body"))));
        assertNoSecretLeak(String.valueOf(normalizeDbValue(row.get("failure_reason"))));
        assertNoSecretLeak(String.valueOf(normalizeDbValue(row.get("payload_json"))));
    }

    @Test
    void finalRedactionGuardSanitizesRetryResultAndNewDelivery() {
        var alertId = insertAlert("retry secret guard", "open");
        var endpoint = "http://example.test/webhook?token=WEBHOOKTOKEN123456";
        var channelId = insertChannel("webhook", endpoint, true);
        webhookClient.nextResult = new WebhookDeliveryResult("failed", 503, "server down", "webhook_http_503");
        var original = service.send(alertId, channelId);
        var originalDeliveryId = ((Number) original.get("deliveryId")).longValue();

        webhookClient.nextResult = new WebhookDeliveryResult(
            "failed",
            500,
            "retry failed " + endpoint,
            "webhook_http_500 access_token=ACCESSSECRET123456"
        );

        var retry = service.retryDelivery(originalDeliveryId);
        var retryDeliveryId = ((Number) retry.get("deliveryId")).longValue();
        var row = deliveryRow(retryDeliveryId);

        assertNoSecretLeak(String.valueOf(retry.get("responseBody")));
        assertNoSecretLeak(String.valueOf(retry.get("message")));
        assertNoSecretLeak(String.valueOf(retry.get("failureReason")));
        assertNoSecretLeak(String.valueOf(normalizeDbValue(row.get("response_body"))));
        assertNoSecretLeak(String.valueOf(normalizeDbValue(row.get("failure_reason"))));
        assertNoSecretLeak(String.valueOf(normalizeDbValue(row.get("payload_json"))));
    }

    @Test
    void recordsStructuredReliabilityFieldsForNonRetryableFailures() {
        var webhookChannelId = insertChannel("webhook", "http://example.test/webhook?token=secret", true);

        var cases = List.of(
            new FailureCase("http_4xx", 400, "webhook_http_400"),
            new FailureCase("unknown_error", null, "unexpected_failure")
        );
        for (var testCase : cases) {
            var alertId = insertAlert("non retryable " + testCase.expectedType(), "open");
            webhookClient.nextResult = new WebhookDeliveryResult(
                "failed",
                testCase.responseCode(),
                "failure " + testCase.expectedType(),
                testCase.message()
            );

            service.send(alertId, webhookChannelId);

            assertReliability(alertId, testCase.expectedType(), testCase.message(), false);
        }

        var unsupportedAlertId = insertAlert("unsupported adapter result", "open");
        var feishuChannelId = insertChannel(
            "feishu",
            "https://open.feishu.cn/open-apis/bot/v2/hook/FEISHUTOKEN123456",
            true
        );
        feishuAdapter.nextResult = new WebhookDeliveryResult("failed", null, "unsupported", "unsupported_channel");
        service.send(unsupportedAlertId, feishuChannelId);
        assertReliability(unsupportedAlertId, "unsupported_channel", "unsupported_channel", false);

        var providerAlertId = insertAlert("provider business failure", "open");
        feishuAdapter.nextResult = new WebhookDeliveryResult("failed", 200, "{\"code\":9499}", "feishu_code_9499");
        service.send(providerAlertId, feishuChannelId);
        assertReliability(providerAlertId, "provider_business_error", "feishu_code_9499", false);

        var weComChannelId = insertChannel(
            "wecom",
            "https://qyapi.weixin.qq.com/cgi-bin/webhook/send?key=WESECRET123456",
            true
        );
        var malformedAlertId = insertAlert("malformed wecom failure", "open");
        webhookClient.nextResult = new WebhookDeliveryResult("success", 200, "not-json", "ok");
        service.send(malformedAlertId, weComChannelId);
        assertReliability(malformedAlertId, "malformed_response", "wecom_malformed_response", false);

        var invalidEndpointAlertId = insertAlert("invalid endpoint failure", "open");
        var invalidWeComChannelId = insertChannel(
            "wecom",
            "http://qyapi.weixin.qq.com/cgi-bin/webhook/send?key=WESECRET123456",
            true
        );
        service.send(invalidEndpointAlertId, invalidWeComChannelId);
        assertReliability(invalidEndpointAlertId, "invalid_endpoint", "invalid_wecom_webhook_url", false);
    }

    @Test
    void sendsWeComMarkdownAndTreatsErrcodeZeroAsSuccess() {
        var alertId = insertAlert("wecom alert", "open");
        var channelId = insertChannel(
            "wecom",
            "https://qyapi.weixin.qq.com/cgi-bin/webhook/send?key=WESECRET123456",
            true
        );

        webhookClient.nextResult = new WebhookDeliveryResult("success", 200, "{\"errcode\":0,\"errmsg\":\"ok\"}", "ok");
        var result = service.send(alertId, channelId);
        var storedPayload = jdbcTemplate.queryForObject(
            "select cast(payload_json as varchar) from notification_deliveries where alert_id = ?",
            String.class,
            alertId
        );

        assertEquals("success", result.get("status"));
        assertEquals(1L, countDeliveriesByStatus("success"));
        assertEquals(true, webhookClient.lastEndpointUrl.startsWith("https://qyapi.weixin.qq.com/cgi-bin/webhook/send?"));
        assertEquals(true, webhookClient.lastEndpointUrl.contains("key="));
        assertEquals(true, webhookClient.lastPayloadJson.contains("\"msgtype\":\"markdown\""));
        assertEquals(true, webhookClient.lastPayloadJson.contains("wecom alert"));
        assertEquals(true, webhookClient.lastPayloadJson.contains("Alert ID"));
        assertEquals(false, webhookClient.lastPayloadJson.contains("WESECRET123456"));
        assertEquals(false, storedPayload.contains("WESECRET123456"));
    }

    @Test
    void sendsFeishuThroughAdapterRegistryWithoutChangingAlertLifecycleState() {
        var alertId = insertAlert("feishu alert", "open");
        var channelId = insertChannel(
            "feishu",
            "https://open.feishu.cn/open-apis/bot/v2/hook/FEISHUTOKEN123456",
            true
        );

        var result = service.send(alertId, channelId);

        assertEquals(alertId, result.get("alertId"));
        assertEquals(channelId, result.get("channelId"));
        assertEquals("success", result.get("status"));
        assertEquals(202, result.get("responseCode"));
        assertEquals(1, feishuAdapter.calls);
        assertEquals("feishu", feishuAdapter.lastChannel.get("channel_type"));
        assertEquals(true, feishuAdapter.lastPayloadJson.contains("\"alertId\":" + alertId));
        assertEquals(1L, countDeliveries());
        assertEquals(1L, countDeliveriesByStatus("success"));
        assertEquals(0L, countAlertLifecycleEvents());
        assertEquals("open", alertStatus(alertId));
    }

    @Test
    void preservesAlertIdAndChannelIdOnFailedFeishuDelivery() {
        var alertId = insertAlert("failed feishu alert", "open");
        var channelId = insertChannel(
            "feishu",
            "https://open.feishu.cn/open-apis/bot/v2/hook/FEISHUTOKEN123456",
            true
        );
        feishuAdapter.nextResult = new WebhookDeliveryResult("failed", 200, "{\"code\":9499}", "feishu_code_9499");

        var result = service.send(alertId, channelId);

        assertEquals(alertId, result.get("alertId"));
        assertEquals(channelId, result.get("channelId"));
        assertEquals("failed", result.get("status"));
        assertEquals(1L, countDeliveriesByStatus("failed"));
        assertEquals(0L, countAlertLifecycleEvents());
        assertEquals("open", alertStatus(alertId));
    }

    @Test
    void marksWeComBusinessFailureAndMalformedResponseAsFailedWithoutDuplicatingDeliveriesAsSuccess() {
        var failedAlertId = insertAlert("wecom business failure", "open");
        var malformedAlertId = insertAlert("wecom malformed", "open");
        var channelId = insertChannel(
            "wecom",
            "https://qyapi.weixin.qq.com/cgi-bin/webhook/send?key=WESECRET123456",
            true
        );

        webhookClient.nextResult = new WebhookDeliveryResult(
            "success",
            200,
            "{\"errcode\":93000,\"errmsg\":\"invalid hook\"}",
            "ok"
        );
        var failed = service.send(failedAlertId, channelId);

        webhookClient.nextResult = new WebhookDeliveryResult("success", 200, "not-json", "ok");
        var malformed = service.send(malformedAlertId, channelId);

        assertEquals("failed", failed.get("status"));
        assertEquals("wecom_errcode_93000", failed.get("message"));
        assertEquals("failed", malformed.get("status"));
        assertEquals("wecom_malformed_response", malformed.get("message"));
        assertEquals(0L, countDeliveriesByStatus("success"));
        assertEquals(2L, countDeliveriesByStatus("failed"));
    }

    @Test
    void recordsInvalidWeComUrlAsNonRetryableFailedDelivery() {
        var alertId = insertAlert("wecom invalid", "open");
        var channelId = insertChannel(
            "wecom",
            "http://qyapi.weixin.qq.com/cgi-bin/webhook/send?key=WESECRET123456",
            true
        );

        var result = service.send(alertId, channelId);

        assertEquals("failed", result.get("status"));
        assertEquals("invalid_wecom_webhook_url", result.get("message"));
        assertReliability(alertId, "invalid_endpoint", "invalid_wecom_webhook_url", false);
        assertEquals(0, webhookClient.calls);
    }

    @Test
    void retryCreatesNewDeliveryThroughAlertBasedPathAndOnlyIncrementsOriginalRetryCount() {
        var alertId = insertAlert("retry source", "open");
        var channelId = insertChannel("webhook", "http://example.test/webhook?token=secret", true);
        webhookClient.nextResult = new WebhookDeliveryResult("failed", 503, "server down", "webhook_http_503");
        var original = service.send(alertId, channelId);
        var originalDeliveryId = ((Number) original.get("deliveryId")).longValue();
        var originalBefore = deliveryRow(originalDeliveryId);

        webhookClient.nextResult = new WebhookDeliveryResult("failed", 500, "still down", "webhook_http_500");
        var firstRetry = service.retryDelivery(originalDeliveryId);
        var firstRetryDeliveryId = ((Number) firstRetry.get("deliveryId")).longValue();

        assertEquals(2L, countDeliveries());
        assertEquals(1, ((Number) deliveryRow(originalDeliveryId).get("retry_count")).intValue());
        assertEquals(0, ((Number) deliveryRow(firstRetryDeliveryId).get("retry_count")).intValue());
        assertEquals(originalDeliveryId, ((Number) deliveryRow(firstRetryDeliveryId).get("retry_of_delivery_id")).longValue());
        assertOriginalDeliveryUnchangedExceptRetryCount(originalBefore, deliveryRow(originalDeliveryId));
        assertEquals("failed", firstRetry.get("status"));

        webhookClient.nextResult = new WebhookDeliveryResult("success", 204, "accepted", "webhook_delivered");
        var secondRetry = service.retryDelivery(firstRetryDeliveryId);
        var secondRetryDeliveryId = ((Number) secondRetry.get("deliveryId")).longValue();

        assertEquals(3L, countDeliveries());
        assertEquals(1, ((Number) deliveryRow(firstRetryDeliveryId).get("retry_count")).intValue());
        assertEquals(firstRetryDeliveryId, ((Number) deliveryRow(secondRetryDeliveryId).get("retry_of_delivery_id")).longValue());
        assertEquals(0, ((Number) deliveryRow(secondRetryDeliveryId).get("retry_count")).intValue());
        assertEquals("success", secondRetry.get("status"));
        assertEquals(0L, countAlertLifecycleEvents());
        assertEquals("open", alertStatus(alertId));
    }

    @Test
    void retryRejectsInvalidSourcesWithoutWritingOrMutatingDeliveries() {
        var openAlertId = insertAlert("open retry reject", "open");
        var closedAlertId = insertAlert("closed retry reject", "closed");
        var enabledChannelId = insertChannel("webhook", "http://example.test/webhook", true);
        var disabledChannelId = insertChannel("webhook", "http://example.test/disabled", false);
        var unsupportedChannelId = insertChannel("email", "http://example.test/email", true);
        var successDeliveryId = insertDelivery(enabledChannelId, openAlertId, "success", false);
        var nonRetryableDeliveryId = insertDelivery(enabledChannelId, openAlertId, "failed", false);
        var missingAlertDeliveryId = insertDelivery(enabledChannelId, 999999L, "failed", true);
        var closedAlertDeliveryId = insertDelivery(enabledChannelId, closedAlertId, "failed", true);
        var missingChannelDeliveryId = insertDelivery(999999L, openAlertId, "failed", true);
        var disabledChannelDeliveryId = insertDelivery(disabledChannelId, openAlertId, "failed", true);
        var unsupportedChannelDeliveryId = insertDelivery(unsupportedChannelId, openAlertId, "failed", true);
        var beforeCount = countDeliveries();

        assertStatus(HttpStatus.NOT_FOUND, "delivery_not_found", () -> service.retryDelivery(999999L));
        assertStatus(HttpStatus.BAD_REQUEST, "delivery_not_failed", () -> service.retryDelivery(successDeliveryId));
        assertStatus(HttpStatus.BAD_REQUEST, "delivery_not_retryable", () -> service.retryDelivery(nonRetryableDeliveryId));
        assertStatus(HttpStatus.NOT_FOUND, "alert_not_found", () -> service.retryDelivery(missingAlertDeliveryId));
        assertStatus(HttpStatus.BAD_REQUEST, "alert_not_open", () -> service.retryDelivery(closedAlertDeliveryId));
        assertStatus(HttpStatus.NOT_FOUND, "channel_not_found", () -> service.retryDelivery(missingChannelDeliveryId));
        assertStatus(HttpStatus.BAD_REQUEST, "channel_disabled", () -> service.retryDelivery(disabledChannelDeliveryId));
        assertStatus(HttpStatus.BAD_REQUEST, "unsupported_channel", () -> service.retryDelivery(unsupportedChannelDeliveryId));

        assertEquals(beforeCount, countDeliveries());
        for (var deliveryId : List.of(
            successDeliveryId,
            nonRetryableDeliveryId,
            missingAlertDeliveryId,
            closedAlertDeliveryId,
            missingChannelDeliveryId,
            disabledChannelDeliveryId,
            unsupportedChannelDeliveryId
        )) {
            assertEquals(0, ((Number) deliveryRow(deliveryId).get("retry_count")).intValue());
        }
        assertEquals(0L, countAlertLifecycleEvents());
        assertEquals("open", alertStatus(openAlertId));
    }

    private void assertStatus(HttpStatus status, Runnable action) {
        var error = assertThrows(ResponseStatusException.class, action::run);
        assertEquals(status, error.getStatusCode());
    }

    private void assertStatus(HttpStatus status, String reason, Runnable action) {
        var error = assertThrows(ResponseStatusException.class, action::run);
        assertEquals(status, error.getStatusCode());
        assertEquals(reason, error.getReason());
    }

    private Long insertAlert(String title, String status) {
        return insertAndReturnId("""
            insert into alerts(title, severity, status, detail_json)
            values (?, 'high', ?, cast('{}' as jsonb))
            """, title, status);
    }

    private Long insertChannel(String type, String endpointUrl, boolean enabled) {
        return insertAndReturnId("""
            insert into notification_channels(name, channel_type, endpoint_url, enabled)
            values ('channel', ?, ?, ?)
            """, type, endpointUrl, enabled);
    }

    private Long insertMissingChannel(String type, boolean enabled) {
        return insertAndReturnId("""
            insert into notification_channels(
                name, channel_type, endpoint_url, endpoint_masked, secret_storage_status, enabled
            )
            values ('missing channel', ?, null, 'demo://not-configured', 'missing', ?)
            """, type, enabled);
    }

    private Long insertAndReturnId(String sql, Object... args) {
        var keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            var statement = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS);
            for (var index = 0; index < args.length; index++) {
                statement.setObject(index + 1, args[index]);
            }
            return statement;
        }, keyHolder);
        var keys = keyHolder.getKeys();
        Number key = null;
        if (keys != null && keys.get("id") instanceof Number id) {
            key = id;
        } else if (keys != null && keys.get("ID") instanceof Number id) {
            key = id;
        } else {
            key = keyHolder.getKey();
        }
        if (key == null) {
            throw new IllegalStateException("Insert did not return a generated id");
        }
        return key.longValue();
    }

    private long countDeliveries() {
        return jdbcTemplate.queryForObject("select count(*) from notification_deliveries", Long.class);
    }

    private long countDeliveriesByStatus(String status) {
        return jdbcTemplate.queryForObject(
            "select count(*) from notification_deliveries where status = ?",
            Long.class,
            status
        );
    }

    private void assertReliability(Long alertId, String failureType, String failureReason, boolean retryable) {
        var row = jdbcTemplate.queryForMap("""
            select failure_type, failure_reason, retryable
            from notification_deliveries
            where alert_id = ?
            order by id desc
            limit 1
            """, alertId);
        assertEquals(failureType, row.get("failure_type"));
        assertEquals(failureReason, row.get("failure_reason"));
        assertEquals(retryable, row.get("retryable"));
    }

    private Long insertDelivery(Long channelId, Long alertId, String status, boolean retryable) {
        return insertAndReturnId("""
            insert into notification_deliveries(
                channel_id, alert_id, title, severity, status, response_code, response_body,
                payload_json, failure_type, failure_reason, retryable
            )
            values (?, ?, 'seed delivery', 'high', ?, 500, 'seed body',
                cast('{}' as jsonb), ?, 'seed reason', ?)
            """, channelId, alertId, status, retryable ? "http_5xx" : "http_4xx", retryable);
    }

    private Map<String, Object> deliveryRow(Long deliveryId) {
        return jdbcTemplate.queryForMap("select * from notification_deliveries where id = ?", deliveryId);
    }

    private Map<String, Object> channelRow(Long channelId) {
        return jdbcTemplate.queryForMap("select * from notification_channels where id = ?", channelId);
    }

    private void assertOriginalDeliveryUnchangedExceptRetryCount(
        Map<String, Object> before,
        Map<String, Object> after
    ) {
        for (var key : List.of(
            "status",
            "response_body",
            "payload_json",
            "failure_type",
            "failure_reason",
            "retryable",
            "retry_of_delivery_id"
        )) {
            assertEquals(normalizeDbValue(before.get(key)), normalizeDbValue(after.get(key)));
        }
    }

    private Object normalizeDbValue(Object value) {
        if (value instanceof byte[] bytes) {
            return new String(bytes);
        }
        return value;
    }

    private void assertNoSecretLeak(String value) {
        assertEquals(false, value.contains("WEBHOOKTOKEN123456"));
        assertEquals(false, value.contains("BEARERSECRET123456"));
        assertEquals(false, value.contains("ACCESSSECRET123456"));
        assertEquals(false, value.contains("http://example.test/webhook?token="));
    }

    private record FailureCase(String expectedType, Integer responseCode, String message) {
    }

    private long countAlertLifecycleEvents() {
        return jdbcTemplate.queryForObject("select count(*) from alert_lifecycle_events", Long.class);
    }

    private String alertStatus(Long alertId) {
        return jdbcTemplate.queryForObject("select status from alerts where id = ?", String.class, alertId);
    }

    private static class StubWebhookClient extends WebhookClient {
        private WebhookDeliveryResult nextResult = new WebhookDeliveryResult("success", 200, "ok", "ok");
        private int calls;
        private String lastEndpointUrl;
        private String lastPayloadJson;

        @Override
        public WebhookDeliveryResult postJson(String endpointUrl, String payloadJson) {
            calls += 1;
            lastEndpointUrl = endpointUrl;
            lastPayloadJson = payloadJson;
            return nextResult;
        }
    }

    private static class StubNotificationChannelAdapter implements NotificationChannelAdapter {
        private final String channelType;
        private WebhookDeliveryResult nextResult =
            new WebhookDeliveryResult("success", 202, "{\"code\":0}", "feishu_stub_delivered");
        private int calls;
        private Map<String, Object> lastChannel;
        private String lastPayloadJson;

        private StubNotificationChannelAdapter(String channelType) {
            this.channelType = channelType;
        }

        @Override
        public String channelType() {
            return channelType;
        }

        @Override
        public WebhookDeliveryResult send(Map<String, Object> alert, Map<String, Object> channel, String payloadJson) {
            calls += 1;
            lastChannel = channel;
            lastPayloadJson = payloadJson;
            return nextResult;
        }
    }
}
