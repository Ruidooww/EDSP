package com.edsp.alert.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.edsp.alert.dto.NotificationChannelRequest;
import com.edsp.alert.dto.NotificationSendRequest;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Statement;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.web.server.ResponseStatusException;

class NotificationServiceTest {
    private static final String FEISHU_TOKEN = "FEISHUTOKEN123456";
    private static final String FEISHU_URL =
        "https://open.feishu.cn/open-apis/bot/v2/hook/" + FEISHU_TOKEN;

    private JdbcTemplate jdbcTemplate;
    private NotificationService service;

    @BeforeEach
    void setUp() {
        var dataSource = new JdbcDataSource();
        dataSource.setURL(
            "jdbc:h2:mem:notification_service_test_" + System.nanoTime()
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
                status varchar(32) not null default 'open'
            )
            """);
        jdbcTemplate.execute("""
            create table notification_channels (
                id bigserial primary key,
                name varchar(160) not null,
                channel_type varchar(40) not null default 'webhook',
                endpoint_url text,
                description text,
                config_json jsonb not null default '{}',
                enabled boolean not null default true,
                status varchar(40) not null default 'draft',
                last_test_status varchar(40),
                last_test_message text,
                last_test_at timestamptz,
                created_at timestamptz not null default now(),
                updated_at timestamptz not null default now()
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
        service = new NotificationService(jdbcTemplate, new ObjectMapper());
    }

    @Test
    void listDeliveriesCanFilterByAlertIdWithoutChangingDefaultLimitBehavior() {
        var firstAlertId = insertAlert("first alert");
        var secondAlertId = insertAlert("second alert");
        var channelId = insertChannel("webhook");
        insertDelivery(channelId, firstAlertId, "first");
        insertDelivery(channelId, secondAlertId, "second");

        var all = service.listDeliveries(50, null);
        var filtered = service.listDeliveries(50, firstAlertId);

        assertEquals(2, all.size());
        assertEquals(1, filtered.size());
        assertEquals(firstAlertId, ((Number) filtered.get(0).get("alert_id")).longValue());
    }

    @Test
    void listDeliveriesReturnsReliabilityFieldsWithSafeDefaultsForOldRows() {
        var alertId = insertAlert("reliability fields alert");
        var channelId = insertChannel("webhook");
        insertDelivery(channelId, alertId, "old delivery", "success");
        var retrySourceId = insertDelivery(
            channelId,
            alertId,
            "retry source",
            "failed",
            "http_5xx",
            "server failed",
            true,
            null,
            2
        );
        insertDelivery(
            channelId,
            alertId,
            "retry child",
            "failed",
            "timeout",
            "webhook_timeout",
            true,
            retrySourceId,
            0
        );

        var deliveries = service.listDeliveries(50, alertId);

        assertEquals(3, deliveries.size());
        var retryChild = deliveries.stream()
            .filter(row -> "retry child".equals(row.get("title")))
            .findFirst()
            .orElseThrow();
        var oldDelivery = deliveries.stream()
            .filter(row -> "old delivery".equals(row.get("title")))
            .findFirst()
            .orElseThrow();

        assertEquals("timeout", retryChild.get("failure_type"));
        assertEquals("webhook_timeout", retryChild.get("failure_reason"));
        assertEquals(true, retryChild.get("retryable"));
        assertEquals(retrySourceId, ((Number) retryChild.get("retry_of_delivery_id")).longValue());
        assertEquals(0, ((Number) retryChild.get("retry_count")).intValue());
        assertEquals(null, oldDelivery.get("failure_type"));
        assertEquals(null, oldDelivery.get("failure_reason"));
        assertEquals(false, oldDelivery.get("retryable"));
        assertEquals(null, oldDelivery.get("retry_of_delivery_id"));
        assertEquals(0, ((Number) oldDelivery.get("retry_count")).intValue());
    }

    @Test
    void listDeliveriesCanFilterBySuccessAndFailedStatus() {
        var alertId = insertAlert("status alert");
        var channelId = insertChannel("webhook");
        insertDelivery(channelId, alertId, "success delivery", "success");
        insertDelivery(channelId, alertId, "failed delivery", "failed");

        var success = service.listDeliveries(50, null, "success", null, null);
        var failed = service.listDeliveries(50, null, "failed", null, null);

        assertEquals(1, success.size());
        assertEquals("success", success.get(0).get("status"));
        assertEquals(1, failed.size());
        assertEquals("failed", failed.get(0).get("status"));
    }

    @Test
    void listDeliveriesCanFilterBySupportedChannelTypes() {
        var alertId = insertAlert("channel type alert");
        insertDelivery(insertChannel("webhook"), alertId, "webhook delivery");
        insertDelivery(insertChannel("wecom"), alertId, "wecom delivery");
        insertDelivery(insertChannel("feishu"), alertId, "feishu delivery");

        for (var channelType : List.of("webhook", "wecom", "feishu")) {
            var deliveries = service.listDeliveries(50, null, null, channelType, null);

            assertEquals(1, deliveries.size());
            assertEquals(channelType, deliveries.get(0).get("channel_type"));
        }
    }

    @Test
    void listDeliveriesCanFilterByChannelId() {
        var alertId = insertAlert("channel id alert");
        var firstChannelId = insertChannel("webhook");
        var secondChannelId = insertChannel("webhook");
        insertDelivery(firstChannelId, alertId, "first channel");
        insertDelivery(secondChannelId, alertId, "second channel");

        var deliveries = service.listDeliveries(50, null, null, null, firstChannelId);

        assertEquals(1, deliveries.size());
        assertEquals(firstChannelId, ((Number) deliveries.get(0).get("channel_id")).longValue());
    }

    @Test
    void listDeliveriesCanCombineAlertStatusChannelTypeAndChannelIdFilters() {
        var targetAlertId = insertAlert("target alert");
        var otherAlertId = insertAlert("other alert");
        var targetChannelId = insertChannel("wecom");
        var otherChannelId = insertChannel("wecom");
        insertDelivery(targetChannelId, targetAlertId, "target", "failed");
        insertDelivery(targetChannelId, targetAlertId, "wrong status", "success");
        insertDelivery(targetChannelId, otherAlertId, "wrong alert", "failed");
        insertDelivery(otherChannelId, targetAlertId, "wrong channel", "failed");
        insertDelivery(insertChannel("webhook"), targetAlertId, "wrong type", "failed");

        var deliveries = service.listDeliveries(50, targetAlertId, "failed", "wecom", targetChannelId);

        assertEquals(1, deliveries.size());
        assertEquals("target", deliveries.get(0).get("title"));
        assertEquals(targetAlertId, ((Number) deliveries.get(0).get("alert_id")).longValue());
        assertEquals(targetChannelId, ((Number) deliveries.get(0).get("channel_id")).longValue());
        assertEquals("failed", deliveries.get(0).get("status"));
        assertEquals("wecom", deliveries.get(0).get("channel_type"));
    }

    @Test
    void listDeliveriesRejectsUnsupportedStatusAndChannelTypeFilters() {
        var invalidStatus = assertThrows(
            ResponseStatusException.class,
            () -> service.listDeliveries(50, null, "pending", null, null)
        );
        var invalidChannelType = assertThrows(
            ResponseStatusException.class,
            () -> service.listDeliveries(50, null, null, "email", null)
        );

        assertEquals(HttpStatus.BAD_REQUEST, invalidStatus.getStatusCode());
        assertEquals("invalid_delivery_status", invalidStatus.getReason());
        assertEquals(HttpStatus.BAD_REQUEST, invalidChannelType.getStatusCode());
        assertEquals("unsupported_channel", invalidChannelType.getReason());
    }

    @Test
    void legacyChannelTestAndSendPathsAreDisabledForNotificationMvp() {
        var error = assertThrows(ResponseStatusException.class, () -> service.testChannel(1L));
        assertEquals(HttpStatus.BAD_REQUEST, error.getStatusCode());
        assertEquals("use_alert_notification_endpoint", error.getReason());

        var sendError = assertThrows(ResponseStatusException.class, () -> service.send(new NotificationSendRequest(
            List.of(1L),
            1L,
            "title",
            "message",
            "high",
            Map.of()
        )));
        assertEquals(HttpStatus.BAD_REQUEST, sendError.getStatusCode());
        assertEquals("use_alert_notification_endpoint", sendError.getReason());
        assertEquals(0L, jdbcTemplate.queryForObject("select count(*) from notification_deliveries", Long.class));
    }

    @Test
    void createChannelOnlyAcceptsWebhookWithValidHttpEndpoint() {
        var webhookUrl = "https://hook.example.test/robot/PATHSECRET123456/send?Access_Token=QUERYSECRET123456&SIGNATURE=SIGNATUREQUERY123456";
        var config = new LinkedHashMap<String, Object>();
        config.put("team", "secops");
        config.put("webhookUrl", webhookUrl);
        config.put("endpointUrl", webhookUrl);
        config.put("url", webhookUrl);
        config.put("key", "KEYSECRET123456");
        config.put("token", "QUERYSECRET123456");
        config.put("secret", "SECRET123456");
        config.put("access_token", "ACCESSSECRET123456");
        config.put("signature", "SIGNATURESECRET123456");
        config.put("authorization", "Bearer AUTHSECRET123456");
        config.put("bearer", "BEARERSECRET123456");
        config.put("nested", Map.of("safeNote", "keep", "token", "NESTEDTOKEN123456"));

        var unsupported = assertThrows(ResponseStatusException.class, () -> service.createChannel(
            new NotificationChannelRequest("email", "email", "https://mail.example.test/send", null, true, Map.of())
        ));
        var invalidUrl = assertThrows(ResponseStatusException.class, () -> service.createChannel(
            new NotificationChannelRequest("bad webhook", "webhook", "/relative/path", null, true, Map.of())
        ));

        var created = service.createChannel(new NotificationChannelRequest(
            "webhook",
            "webhook",
            webhookUrl,
            "webhook only",
            true,
            config
        ));
        var id = ((Number) created.get("id")).longValue();
        var channel = service.listChannels().stream()
            .filter(row -> ((Number) row.get("id")).longValue() == id)
            .findFirst()
            .orElseThrow();
        var storedConfig = jdbcTemplate.queryForObject(
            "select cast(config_json as varchar) from notification_channels where id = ?",
            String.class,
            id
        );

        assertEquals(HttpStatus.BAD_REQUEST, unsupported.getStatusCode());
        assertEquals("unsupported_channel", unsupported.getReason());
        assertEquals(HttpStatus.BAD_REQUEST, invalidUrl.getStatusCode());
        assertEquals("invalid_webhook_url", invalidUrl.getReason());
        assertEquals("webhook", channel.get("channel_type"));
        assertEquals(
            "https://hook.example.test/robot/[redacted]/send?Access_Token=[redacted]&SIGNATURE=[redacted]",
            channel.get("endpoint_masked")
        );
        assertFalse(String.valueOf(channel.get("endpoint_masked")).contains("PATHSECRET123456"));
        assertFalse(String.valueOf(channel.get("endpoint_masked")).contains("QUERYSECRET123456"));
        assertFalse(String.valueOf(channel.get("endpoint_masked")).contains("SIGNATUREQUERY123456"));
        assertFalse(storedConfig.contains(webhookUrl));
        assertFalse(storedConfig.contains("PATHSECRET123456"));
        assertFalse(storedConfig.contains("QUERYSECRET123456"));
        assertFalse(storedConfig.contains("SIGNATUREQUERY123456"));
        assertFalse(storedConfig.contains("KEYSECRET123456"));
        assertFalse(storedConfig.contains("SECRET123456"));
        assertFalse(storedConfig.contains("ACCESSSECRET123456"));
        assertFalse(storedConfig.contains("SIGNATURESECRET123456"));
        assertFalse(storedConfig.contains("AUTHSECRET123456"));
        assertFalse(storedConfig.contains("BEARERSECRET123456"));
        assertFalse(storedConfig.contains("NESTEDTOKEN123456"));
        assertFalse(storedConfig.contains("webhookUrl"));
        assertFalse(storedConfig.contains("endpointUrl"));
        assertFalse(storedConfig.contains("access_token"));
        assertEquals(true, storedConfig.contains("secops"));
        assertEquals(true, storedConfig.contains("keep"));
    }

    @Test
    void updateChannelAlsoSanitizesConfigJson() {
        var created = service.createChannel(new NotificationChannelRequest(
            "webhook",
            "webhook",
            "https://hook.example.test/first?token=FIRSTSECRET123456",
            null,
            true,
            Map.of("team", "secops")
        ));
        var id = ((Number) created.get("id")).longValue();
        var updatedUrl = "https://hook.example.test/second?access_token=UPDATEDSECRET123456";

        service.updateChannel(id, new NotificationChannelRequest(
            "webhook updated",
            "webhook",
            updatedUrl,
            null,
            true,
            Map.of(
                "team", "secops",
                "endpoint_url", updatedUrl,
                "Access_Token", "UPDATEDSECRET123456",
                "nested", List.of(Map.of("Authorization", "Bearer AUTHSECRET123456"), "keep")
            )
        ));

        var storedConfig = jdbcTemplate.queryForObject(
            "select cast(config_json as varchar) from notification_channels where id = ?",
            String.class,
            id
        );

        assertFalse(storedConfig.contains(updatedUrl));
        assertFalse(storedConfig.contains("UPDATEDSECRET123456"));
        assertFalse(storedConfig.contains("AUTHSECRET123456"));
        assertFalse(storedConfig.contains("endpoint_url"));
        assertFalse(storedConfig.contains("Access_Token"));
        assertEquals(true, storedConfig.contains("secops"));
        assertEquals(true, storedConfig.contains("keep"));
    }

    @Test
    void listDeliveriesRedactsStoredResponseFailureReasonAndPayloadPreview() {
        var alertId = insertAlert("historical delivery alert");
        var endpoint = "https://hook.example.test/webhook?token=DELIVERYSECRET123456&tenant=secops";
        var channelId = insertChannel("webhook", endpoint);
        jdbcTemplate.update("""
            insert into notification_deliveries(
                channel_id, alert_id, title, severity, status, response_code, response_body,
                payload_json, failure_type, failure_reason
            )
            values (?, ?, 'historical leak', 'high', 'failed', 500, ?,
                cast(? as jsonb), 'http_5xx', ?)
            """,
            channelId,
            alertId,
            "response echoed " + endpoint + " Authorization: Bearer BODYSECRET123456",
            "{\"message\":\"payload token=DELIVERYSECRET123456\",\"tenant\":\"secops\",\"Authorization\":\"Bearer PAYLOADSECRET123456\"}",
            "failure access_token=DELIVERYSECRET123456"
        );

        var delivery = service.listDeliveries(50, alertId).get(0);

        assertFalse(String.valueOf(delivery.get("response_body")).contains("DELIVERYSECRET123456"));
        assertFalse(String.valueOf(delivery.get("response_body")).contains("BODYSECRET123456"));
        assertFalse(String.valueOf(delivery.get("failure_reason")).contains("DELIVERYSECRET123456"));
        assertFalse(String.valueOf(delivery.get("payload_json")).contains("DELIVERYSECRET123456"));
        assertTrue(String.valueOf(delivery.get("payload_json")).contains("PAYLOADSECRET123456"));
        assertTrue(String.valueOf(delivery.get("payload_json")).contains("secops"));
        assertFalse(delivery.containsKey("endpoint_url"));
    }

    @Test
    void createWeComChannelRequiresHttpsRobotUrlAndDoesNotDuplicateKeyInConfigOrMaskedEndpoint() {
        var httpUrl = assertThrows(ResponseStatusException.class, () -> service.createChannel(
            new NotificationChannelRequest(
                "bad wecom",
                "wecom",
                "http://qyapi.weixin.qq.com/cgi-bin/webhook/send?key=WESECRET123456",
                null,
                true,
                Map.of()
            )
        ));
        var missingKey = assertThrows(ResponseStatusException.class, () -> service.createChannel(
            new NotificationChannelRequest(
                "bad wecom",
                "wecom",
                "https://qyapi.weixin.qq.com/cgi-bin/webhook/send?not_key=WESECRET123456",
                null,
                true,
                Map.of()
            )
        ));

        var created = service.createChannel(new NotificationChannelRequest(
            "wecom",
            "wecom",
            "https://qyapi.weixin.qq.com/cgi-bin/webhook/send?key=WESECRET123456",
            "wecom robot",
            true,
            Map.of(
                "team", "secops",
                "key", "WESECRET123456",
                "robotKey", "WESECRET123456",
                "webhookUrl", "https://qyapi.weixin.qq.com/cgi-bin/webhook/send?key=WESECRET123456"
            )
        ));
        var id = ((Number) created.get("id")).longValue();
        var channel = service.listChannels().stream()
            .filter(row -> ((Number) row.get("id")).longValue() == id)
            .findFirst()
            .orElseThrow();
        var storedConfig = jdbcTemplate.queryForObject(
            "select cast(config_json as varchar) from notification_channels where id = ?",
            String.class,
            id
        );

        assertEquals(HttpStatus.BAD_REQUEST, httpUrl.getStatusCode());
        assertEquals("invalid_wecom_webhook_url", httpUrl.getReason());
        assertEquals(HttpStatus.BAD_REQUEST, missingKey.getStatusCode());
        assertEquals("invalid_wecom_webhook_url", missingKey.getReason());
        assertEquals("wecom", channel.get("channel_type"));
        assertEquals("https://qyapi.weixin.qq.com/...", channel.get("endpoint_masked"));
        assertFalse(String.valueOf(channel.get("endpoint_masked")).contains("WESECRET123456"));
        assertFalse(storedConfig.contains("WESECRET123456"));
        assertFalse(storedConfig.contains("qyapi.weixin.qq.com"));
        assertFalse(storedConfig.contains("webhookUrl"));
        assertFalse(storedConfig.contains("robotKey"));
        assertEquals(true, storedConfig.contains("secops"));
    }

    @Test
    void createFeishuChannelRequiresOfficialHttpsHookAndDoesNotStoreTokenInConfigOrMaskedEndpoint() {
        var httpUrl = assertThrows(ResponseStatusException.class, () -> service.createChannel(
            new NotificationChannelRequest(
                "bad feishu",
                "feishu",
                "http://open.feishu.cn/open-apis/bot/v2/hook/" + FEISHU_TOKEN,
                null,
                true,
                Map.of()
            )
        ));
        var wrongHost = assertThrows(ResponseStatusException.class, () -> service.createChannel(
            new NotificationChannelRequest(
                "bad feishu",
                "feishu",
                "https://example.test/open-apis/bot/v2/hook/" + FEISHU_TOKEN,
                null,
                true,
                Map.of()
            )
        ));
        var emptyToken = assertThrows(ResponseStatusException.class, () -> service.createChannel(
            new NotificationChannelRequest(
                "bad feishu",
                "feishu",
                "https://open.feishu.cn/open-apis/bot/v2/hook/",
                null,
                true,
                Map.of()
            )
        ));
        var extraPathSegment = assertThrows(ResponseStatusException.class, () -> service.createChannel(
            new NotificationChannelRequest(
                "bad feishu",
                "feishu",
                FEISHU_URL + "/extra",
                null,
                true,
                Map.of()
            )
        ));

        var created = service.createChannel(new NotificationChannelRequest(
            "feishu",
            "feishu",
            FEISHU_URL,
            "feishu robot",
            true,
            Map.of(
                "team", "secops",
                "token", FEISHU_TOKEN,
                "webhookUrl", FEISHU_URL,
                "endpointUrl", FEISHU_URL
            )
        ));
        var id = ((Number) created.get("id")).longValue();
        var channel = service.listChannels().stream()
            .filter(row -> ((Number) row.get("id")).longValue() == id)
            .findFirst()
            .orElseThrow();
        var storedConfig = jdbcTemplate.queryForObject(
            "select cast(config_json as varchar) from notification_channels where id = ?",
            String.class,
            id
        );

        assertEquals(HttpStatus.BAD_REQUEST, httpUrl.getStatusCode());
        assertEquals("invalid_feishu_webhook_url", httpUrl.getReason());
        assertEquals(HttpStatus.BAD_REQUEST, wrongHost.getStatusCode());
        assertEquals("invalid_feishu_webhook_url", wrongHost.getReason());
        assertEquals(HttpStatus.BAD_REQUEST, emptyToken.getStatusCode());
        assertEquals("invalid_feishu_webhook_url", emptyToken.getReason());
        assertEquals(HttpStatus.BAD_REQUEST, extraPathSegment.getStatusCode());
        assertEquals("invalid_feishu_webhook_url", extraPathSegment.getReason());
        assertEquals("feishu", channel.get("channel_type"));
        assertEquals("https://open.feishu.cn/open-apis/bot/v2/hook/...", channel.get("endpoint_masked"));
        assertFalse(String.valueOf(channel.get("endpoint_masked")).contains(FEISHU_TOKEN));
        assertFalse(storedConfig.contains(FEISHU_TOKEN));
        assertFalse(storedConfig.contains(FEISHU_URL));
        assertFalse(storedConfig.contains("webhookUrl"));
        assertFalse(storedConfig.contains("endpointUrl"));
        assertEquals(true, storedConfig.contains("secops"));
    }

    private Long insertAlert(String title) {
        return insertAndReturnId("insert into alerts(title, severity, status) values (?, 'high', 'open')", title);
    }

    private Long insertChannel(String channelType) {
        return insertAndReturnId(
            "insert into notification_channels(name, channel_type) values (?, ?)",
            channelType,
            channelType
        );
    }

    private Long insertChannel(String channelType, String endpointUrl) {
        return insertAndReturnId(
            "insert into notification_channels(name, channel_type, endpoint_url) values (?, ?, ?)",
            channelType,
            channelType,
            endpointUrl
        );
    }

    private void insertDelivery(Long channelId, Long alertId, String title) {
        insertDelivery(channelId, alertId, title, "success");
    }

    private void insertDelivery(Long channelId, Long alertId, String title, String status) {
        jdbcTemplate.update("""
            insert into notification_deliveries(
                channel_id, alert_id, title, severity, status, response_code, response_body, payload_json
            )
            values (?, ?, ?, 'high', ?, 200, 'ok', cast('{}' as jsonb))
            """, channelId, alertId, title, status);
    }

    private Long insertDelivery(
        Long channelId,
        Long alertId,
        String title,
        String status,
        String failureType,
        String failureReason,
        boolean retryable,
        Long retryOfDeliveryId,
        int retryCount
    ) {
        return insertAndReturnId("""
            insert into notification_deliveries(
                channel_id, alert_id, title, severity, status, response_code, response_body,
                payload_json, failure_type, failure_reason, retryable, retry_of_delivery_id, retry_count
            )
            values (?, ?, ?, 'high', ?, 500, 'failed', cast('{}' as jsonb), ?, ?, ?, ?, ?)
            """,
            channelId, alertId, title, status, failureType, failureReason, retryable, retryOfDeliveryId, retryCount);
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
}
