package com.edsp.alert.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
    private JdbcTemplate jdbcTemplate;
    private StubWebhookClient webhookClient;
    private StubNotificationChannelAdapter feishuAdapter;
    private AlertNotificationService service;

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
                enabled boolean not null default true
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
        var objectMapper = new ObjectMapper();
        var weComAdapter = new WeComNotificationAdapter(webhookClient, objectMapper);
        feishuAdapter = new StubNotificationChannelAdapter("feishu");
        var adapterRegistry = new NotificationChannelAdapterRegistry(List.of(webhookAdapter, weComAdapter, feishuAdapter));
        service = new AlertNotificationService(jdbcTemplate, objectMapper, adapterRegistry);
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
    void rejectsInvalidWeComUrlBeforeWritingDelivery() {
        var alertId = insertAlert("wecom invalid", "open");
        var channelId = insertChannel(
            "wecom",
            "http://qyapi.weixin.qq.com/cgi-bin/webhook/send?key=WESECRET123456",
            true
        );

        assertStatus(HttpStatus.BAD_REQUEST, "invalid_wecom_webhook_url", () -> service.send(alertId, channelId));
        assertEquals(0L, countDeliveries());
        assertEquals(0, webhookClient.calls);
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
