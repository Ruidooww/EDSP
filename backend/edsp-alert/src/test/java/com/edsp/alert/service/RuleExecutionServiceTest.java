package com.edsp.alert.service;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.edsp.alert.dto.IngestAlertRequest;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class RuleExecutionServiceTest {
    private JdbcTemplate jdbcTemplate;
    private RuleExecutionService service;

    @BeforeEach
    void setUp() {
        var dataSource = new JdbcDataSource();
        dataSource.setURL(
            "jdbc:h2:mem:rule_execution_service_test_" + System.nanoTime()
                + ";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;"
                + "DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1;"
                + "INIT=CREATE DOMAIN IF NOT EXISTS JSONB AS JSON\\;"
                + "CREATE DOMAIN IF NOT EXISTS TIMESTAMPTZ AS TIMESTAMP WITH TIME ZONE"
        );
        dataSource.setUser("sa");
        dataSource.setPassword("");

        jdbcTemplate = new JdbcTemplate(dataSource);
        jdbcTemplate.execute("""
            create table rules (
                id bigint primary key,
                name varchar(160) not null,
                event_type varchar(80) not null,
                severity varchar(32) not null,
                expression varchar not null,
                enabled boolean not null
            )
            """);
        jdbcTemplate.execute("""
            create table alerts (
                id bigint primary key,
                rule_id bigint,
                updated_at timestamptz
            )
            """);
        jdbcTemplate.execute("""
            create table audit_logs (
                id bigserial primary key,
                actor varchar(160),
                action varchar(160),
                target_type varchar(80),
                target_id varchar(160),
                detail_json jsonb not null
            )
            """);

        service = new RuleExecutionService(jdbcTemplate, new ObjectMapper());
    }

    @Test
    void automaticNotificationActionsAreRecordedAsSkippedWithoutSending() {
        jdbcTemplate.update("insert into alerts(id, updated_at) values (100, now())");
        jdbcTemplate.update("""
            insert into rules(id, name, event_type, severity, expression, enabled)
            values (10, 'notify rule', 'file_operation', 'high', ?, true)
            """, """
            {
              "action": {
                "notify": true,
                "channelIds": [1, 2]
              }
            }
            """);

        var result = service.execute(100L, request(), "high", Timestamp.from(Instant.now()));

        assertEquals(1, result.get("matched"));
        @SuppressWarnings("unchecked")
        var notifications = (List<Map<String, Object>>) result.get("notifications");
        assertEquals(1, notifications.size());
        assertEquals("skipped", notifications.get(0).get("status"));
        assertEquals("automatic_notification_disabled", notifications.get(0).get("message"));
        assertEquals(1L, jdbcTemplate.queryForObject("select count(*) from audit_logs", Long.class));
    }

    private IngestAlertRequest request() {
        return new IngestAlertRequest(
            "external-system",
            "event-1",
            "file_operation",
            "敏感文件外发",
            "high",
            null,
            "zhangsan",
            "host-1",
            "文件外发策略",
            "file",
            "doc-1",
            "open",
            Map.of()
        );
    }
}
