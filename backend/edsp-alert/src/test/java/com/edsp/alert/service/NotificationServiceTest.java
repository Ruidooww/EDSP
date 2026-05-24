package com.edsp.alert.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.edsp.alert.dto.NotificationChannelRequest;
import com.edsp.alert.dto.NotificationSendRequest;
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
                created_at timestamptz not null default now()
            )
            """);
        service = new NotificationService(jdbcTemplate, new ObjectMapper());
    }

    @Test
    void listDeliveriesCanFilterByAlertIdWithoutChangingDefaultLimitBehavior() {
        var firstAlertId = insertAlert("first alert");
        var secondAlertId = insertAlert("second alert");
        var channelId = insertChannel();
        insertDelivery(channelId, firstAlertId, "first");
        insertDelivery(channelId, secondAlertId, "second");

        var all = service.listDeliveries(50, null);
        var filtered = service.listDeliveries(50, firstAlertId);

        assertEquals(2, all.size());
        assertEquals(1, filtered.size());
        assertEquals(firstAlertId, ((Number) filtered.get(0).get("alert_id")).longValue());
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
        var unsupported = assertThrows(ResponseStatusException.class, () -> service.createChannel(
            new NotificationChannelRequest("email", "email", "https://mail.example.test/send", null, true, Map.of())
        ));
        var invalidUrl = assertThrows(ResponseStatusException.class, () -> service.createChannel(
            new NotificationChannelRequest("bad webhook", "webhook", "/relative/path", null, true, Map.of())
        ));

        var created = service.createChannel(new NotificationChannelRequest(
            "webhook",
            "webhook",
            "https://hook.example.test/robot/PATHSECRET123456/send?token=QUERYSECRET123456",
            "webhook only",
            true,
            Map.of()
        ));
        var channel = service.listChannels().stream()
            .filter(row -> ((Number) row.get("id")).longValue() == ((Number) created.get("id")).longValue())
            .findFirst()
            .orElseThrow();

        assertEquals(HttpStatus.BAD_REQUEST, unsupported.getStatusCode());
        assertEquals("unsupported_channel", unsupported.getReason());
        assertEquals(HttpStatus.BAD_REQUEST, invalidUrl.getStatusCode());
        assertEquals("invalid_webhook_url", invalidUrl.getReason());
        assertEquals("webhook", channel.get("channel_type"));
        assertEquals("https://hook.example.test/...", channel.get("endpoint_masked"));
        assertFalse(String.valueOf(channel.get("endpoint_masked")).contains("PATHSECRET123456"));
        assertFalse(String.valueOf(channel.get("endpoint_masked")).contains("QUERYSECRET123456"));
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

    private Long insertChannel() {
        return insertAndReturnId("insert into notification_channels(name, channel_type) values ('webhook', 'webhook')");
    }

    private void insertDelivery(Long channelId, Long alertId, String title) {
        jdbcTemplate.update("""
            insert into notification_deliveries(
                channel_id, alert_id, title, severity, status, response_code, response_body, payload_json
            )
            values (?, ?, ?, 'high', 'success', 200, 'ok', cast('{}' as jsonb))
            """, channelId, alertId, title);
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
