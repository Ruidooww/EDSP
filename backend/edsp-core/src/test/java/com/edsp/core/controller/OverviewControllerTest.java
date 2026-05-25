package com.edsp.core.controller;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Method;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.flywaydb.core.Flyway;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;

class OverviewControllerTest {
    private JdbcTemplate jdbcTemplate;
    private OverviewController controller;

    @BeforeEach
    void setUp() {
        var dataSource = new JdbcDataSource();
        dataSource.setURL(
            "jdbc:h2:mem:overview_controller_test;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;"
                + "DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1;"
                + "INIT=CREATE DOMAIN IF NOT EXISTS TIMESTAMPTZ AS TIMESTAMP WITH TIME ZONE\\;"
                + "CREATE DOMAIN IF NOT EXISTS JSONB AS JSON"
        );
        dataSource.setUser("sa");
        dataSource.setPassword("");

        Flyway.configure()
            .dataSource(dataSource)
            .locations("classpath:db/migration")
            .cleanDisabled(false)
            .load()
            .clean();
        Flyway.configure()
            .dataSource(dataSource)
            .locations("classpath:db/migration")
            .load()
            .migrate();

        jdbcTemplate = new JdbcTemplate(dataSource);
        controller = new OverviewController(jdbcTemplate);
    }

    @Test
    void overviewRouteKeepsExistingPathAndReturnsEmptyDashboardGroups() throws Exception {
        var controllerMapping = OverviewController.class.getAnnotation(RequestMapping.class);
        assertArrayEquals(new String[] {"/api/core"}, controllerMapping.value());

        Method overview = OverviewController.class.getMethod("overview");
        assertArrayEquals(new String[] {"/overview"}, overview.getAnnotation(GetMapping.class).value());

        var data = controller.overview().data();

        assertTrue(data.containsKey("requestTime"));
        assertTrue(data.containsKey("dataSources"));
        assertTrue(data.containsKey("schema"));
        assertTrue(data.containsKey("rules"));
        assertTrue(data.containsKey("alerts"));
        assertTrue(data.containsKey("reports"));
        assertTrue(data.containsKey("recentDataSources"));

        var securityOperations = objectMap(data.get("securityOperations"));
        assertEquals(0L, securityOperations.get("totalAlerts"));
        assertEquals(0L, securityOperations.get("openAlerts"));
        assertEquals(0L, securityOperations.get("acknowledgedAlerts"));
        assertEquals(0L, securityOperations.get("closedAlerts"));
        assertEquals(0L, securityOperations.get("highRiskAlerts"));
        assertEquals(0L, securityOperations.get("todayAlerts"));

        var notificationDelivery = objectMap(data.get("notificationDelivery"));
        assertEquals(0L, notificationDelivery.get("todayTotal"));
        assertEquals(0L, notificationDelivery.get("todaySuccess"));
        assertEquals(0L, notificationDelivery.get("todayFailed"));
        assertEquals(0, notificationDelivery.get("todaySuccessRate"));
        assertEquals(0L, notificationDelivery.get("retryableFailed"));
        assertEquals(Map.of(), notificationDelivery.get("byFailureType"));
        assertEquals(List.of(), notificationDelivery.get("recentFailed"));

        var notificationChannels = objectMap(data.get("notificationChannels"));
        assertEquals(0L, notificationChannels.get("total"));
        assertEquals(0L, notificationChannels.get("enabled"));
        assertEquals(0L, notificationChannels.get("disabled"));
        assertEquals(Map.of(), notificationChannels.get("byType"));
        assertEquals(List.of(), data.get("recentLifecycleEvents"));
    }

    @Test
    void overviewAggregatesSecurityOperationsAndOmitsSensitiveDeliveryFields() {
        var today = LocalDate.now(ZoneId.systemDefault()).atStartOfDay();
        var yesterday = today.minusDays(1);

        var openAlertId = insertAlert("Open alert", "critical", "open", null, today.plusHours(1));
        var acknowledgedAlertId = insertAlert("Acknowledged alert", "high", "acknowledged", "zhangsan", today.plusHours(2));
        insertAlert("Closed alert", "low", "closed", null, yesterday.plusHours(3));

        insertLifecycleEvent(openAlertId, "assigned", "open", "open", "ops", "lisi", "assign", today.plusHours(4));
        insertLifecycleEvent(acknowledgedAlertId, "acknowledged", "open", "acknowledged", "ops", null, "ack", today.plusHours(5));

        var webhookChannelId = insertChannel(
            "Webhook",
            "webhook",
            true,
            "ready",
            "https://hook.example.test/webhook?token=OVERVIEWSECRET123456"
        );
        var feishuChannelId = insertChannel(
            "Feishu",
            "feishu",
            false,
            "disabled",
            "https://open.feishu.cn/open-apis/bot/v2/hook/FEISHUTOKEN1234567890"
        );

        insertDelivery(webhookChannelId, openAlertId, "Timeout 1", "failed", 504, "body", "timeout", "Timed out", true, 1, null, today.plusHours(6));
        var firstFailureId = insertDelivery(webhookChannelId, openAlertId, "Timeout 2", "failed", 504, "body", "timeout", "Timed out", false, 0, null, today.plusHours(7));
        insertDelivery(webhookChannelId, openAlertId, "Timeout 3", "failed", 504, "body", "timeout", "Timed out", false, 2, firstFailureId, yesterday.plusHours(8));
        insertDelivery(feishuChannelId, acknowledgedAlertId, "HTTP 1", "failed", 500, "body", "http_500", "Server error", false, 0, null, today.plusHours(8));
        insertDelivery(feishuChannelId, acknowledgedAlertId, "HTTP 2", "failed", 500, "body", "http_500", "Server error", false, 0, null, today.plusHours(9));
        insertDelivery(feishuChannelId, acknowledgedAlertId, "DNS", "failed", null, "body", "dns", "DNS error", false, 0, null, today.plusHours(10));
        insertDelivery(feishuChannelId, acknowledgedAlertId, "Auth", "failed", 401, "body", "auth", "Unauthorized", false, 0, null, today.plusHours(11));
        insertDelivery(feishuChannelId, acknowledgedAlertId, "SSL", "failed", 495, "body", "ssl",
            "SSL error FEISHUTOKEN1234567890", false, 0, null, today.plusHours(12));
        insertDelivery(
            webhookChannelId,
            acknowledgedAlertId,
            "Rate",
            "failed",
            429,
            "body",
            "rate_limited",
            "Rate limited OVERVIEWSECRET123456 Authorization: Bearer OVERVIEWBEARER123456",
            false,
            0,
            null,
            today.plusHours(13)
        );
        insertDelivery(feishuChannelId, acknowledgedAlertId, "Unknown", "failed", null, "body", null, "Unknown", false, 0, null, today.plusHours(14));
        insertDelivery(webhookChannelId, openAlertId, "Success", "success", 200, "ok", null, null, false, 0, null, today.plusHours(15));

        var data = controller.overview().data();

        var securityOperations = objectMap(data.get("securityOperations"));
        assertEquals(3L, securityOperations.get("totalAlerts"));
        assertEquals(1L, securityOperations.get("openAlerts"));
        assertEquals(1L, securityOperations.get("acknowledgedAlerts"));
        assertEquals(1L, securityOperations.get("closedAlerts"));
        assertEquals(2L, securityOperations.get("highRiskAlerts"));
        assertEquals(2L, securityOperations.get("todayAlerts"));

        var notificationChannels = objectMap(data.get("notificationChannels"));
        assertEquals(2L, notificationChannels.get("total"));
        assertEquals(1L, notificationChannels.get("enabled"));
        assertEquals(1L, notificationChannels.get("disabled"));
        assertEquals(1L, objectMap(notificationChannels.get("byType")).get("webhook"));
        assertEquals(1L, objectMap(notificationChannels.get("byType")).get("feishu"));

        var notificationDelivery = objectMap(data.get("notificationDelivery"));
        assertEquals(10L, notificationDelivery.get("todayTotal"));
        assertEquals(1L, notificationDelivery.get("todaySuccess"));
        assertEquals(9L, notificationDelivery.get("todayFailed"));
        assertEquals(1L, notificationDelivery.get("retryableFailed"));
        assertEquals(10, notificationDelivery.get("todaySuccessRate"));

        var byFailureType = objectMap(notificationDelivery.get("byFailureType"));
        assertEquals(5, byFailureType.size());
        assertEquals("timeout", byFailureType.keySet().iterator().next());
        assertEquals(3L, byFailureType.get("timeout"));
        assertEquals(2L, byFailureType.get("http_500"));
        assertFalse(byFailureType.containsKey(null));

        var recentFailed = objectList(notificationDelivery.get("recentFailed"));
        assertEquals(5, recentFailed.size());
        assertEquals(Set.of(
            "id", "channel_id", "channel_name", "channel_type",
            "alert_id", "alert_title", "title", "status", "response_code",
            "failure_type", "failure_reason", "retryable", "retry_count",
            "retry_of_delivery_id", "created_at"
        ), recentFailed.get(0).keySet());
        assertFalse(recentFailed.get(0).containsKey("payload_json"));
        assertFalse(recentFailed.get(0).containsKey("response_body"));
        assertTrue(recentFailed.stream()
            .noneMatch(row -> String.valueOf(row.get("failure_reason")).contains("OVERVIEWSECRET123456")));
        assertTrue(recentFailed.stream()
            .noneMatch(row -> String.valueOf(row.get("failure_reason")).contains("OVERVIEWBEARER123456")));
        assertTrue(recentFailed.stream()
            .noneMatch(row -> String.valueOf(row.get("failure_reason")).contains("FEISHUTOKEN1234567890")));

        var recentLifecycleEvents = objectList(data.get("recentLifecycleEvents"));
        assertEquals(2, recentLifecycleEvents.size());
        assertEquals("Acknowledged alert", recentLifecycleEvents.get(0).get("alert_title"));
        assertEquals("acknowledged", recentLifecycleEvents.get(0).get("event_type"));
        assertEquals(Set.of(
            "id", "alert_id", "alert_title", "event_type", "operator_name", "assignee", "created_at"
        ), recentLifecycleEvents.get(0).keySet());
    }

    @Test
    void overviewIsReadOnlyAndDoesNotMutateOperationalTables() {
        var now = LocalDate.now(ZoneId.systemDefault()).atStartOfDay().plusHours(1);
        var alertId = insertAlert("Read only alert", "high", "open", null, now);
        var channelId = insertChannel("Webhook", "webhook", true, "ready");
        insertLifecycleEvent(alertId, "assigned", "open", "open", "ops", "lisi", "assign", now.plusMinutes(1));
        insertDelivery(channelId, alertId, "Failed notification", "failed", 500, "server error",
            "http_5xx", "Server error", true, 0, null, now.plusMinutes(2));

        var alertCount = countRows("alerts");
        var lifecycleCount = countRows("alert_lifecycle_events");
        var deliveryCount = countRows("notification_deliveries");
        var originalStatus = stringCell("select status from alerts where id = ?", alertId);

        controller.overview();

        assertEquals(alertCount, countRows("alerts"));
        assertEquals(lifecycleCount, countRows("alert_lifecycle_events"));
        assertEquals(deliveryCount, countRows("notification_deliveries"));
        assertEquals(originalStatus, stringCell("select status from alerts where id = ?", alertId));
    }

    private Long insertAlert(String title, String severity, String status, String assignedTo, LocalDateTime createdAt) {
        return insertAndReturnId("""
            insert into alerts(title, severity, status, assigned_to, detail_json, created_at, updated_at)
            values (?, ?, ?, ?, cast('{}' as jsonb), ?, ?)
            """, title, severity, status, assignedTo, timestamp(createdAt), timestamp(createdAt));
    }

    private void insertLifecycleEvent(
        Long alertId,
        String eventType,
        String fromStatus,
        String toStatus,
        String operatorName,
        String assignee,
        String note,
        LocalDateTime createdAt
    ) {
        jdbcTemplate.update("""
            insert into alert_lifecycle_events(
                alert_id, event_type, from_status, to_status, operator_name,
                assignee, note, detail_json, created_at
            )
            values (?, ?, ?, ?, ?, ?, ?, cast('{}' as jsonb), ?)
            """, alertId, eventType, fromStatus, toStatus, operatorName, assignee, note, timestamp(createdAt));
    }

    private Long insertChannel(String name, String channelType, boolean enabled, String status) {
        return insertChannel(name, channelType, enabled, status, "https://example.test/hook");
    }

    private Long insertChannel(String name, String channelType, boolean enabled, String status, String endpointUrl) {
        return insertAndReturnId("""
            insert into notification_channels(name, channel_type, endpoint_url, config_json, enabled, status)
            values (?, ?, ?, cast('{}' as jsonb), ?, ?)
            """, name, channelType, endpointUrl, enabled, status);
    }

    private Long insertDelivery(
        Long channelId,
        Long alertId,
        String title,
        String status,
        Integer responseCode,
        String responseBody,
        String failureType,
        String failureReason,
        boolean retryable,
        int retryCount,
        Long retryOfDeliveryId,
        LocalDateTime createdAt
    ) {
        return insertAndReturnId("""
            insert into notification_deliveries(
                channel_id, alert_id, title, severity, status, response_code,
                response_body, payload_json, failure_type, failure_reason,
                retryable, retry_count, retry_of_delivery_id, created_at
            )
            values (?, ?, ?, 'high', ?, ?, ?, cast('{"secret":"hidden"}' as jsonb), ?, ?, ?, ?, ?, ?)
            """,
            channelId, alertId, title, status, responseCode, responseBody,
            failureType, failureReason, retryable, retryCount, retryOfDeliveryId, timestamp(createdAt));
    }

    private Long insertAndReturnId(String sql, Object... args) {
        var keyHolder = new org.springframework.jdbc.support.GeneratedKeyHolder();
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

    private Long countRows(String tableName) {
        return jdbcTemplate.queryForObject("select count(*) from " + tableName, Long.class);
    }

    private String stringCell(String sql, Object... args) {
        return jdbcTemplate.queryForObject(sql, String.class, args);
    }

    private Timestamp timestamp(LocalDateTime value) {
        return Timestamp.valueOf(value);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> objectMap(Object value) {
        assertInstanceOf(Map.class, value);
        return (Map<String, Object>) value;
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> objectList(Object value) {
        assertInstanceOf(List.class, value);
        return (List<Map<String, Object>>) value;
    }
}
