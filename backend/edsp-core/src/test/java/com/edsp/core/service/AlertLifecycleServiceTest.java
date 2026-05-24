package com.edsp.core.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.edsp.core.dto.AlertLifecycleRequest;
import com.edsp.core.support.CoreRequestSupport;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Statement;
import java.sql.Timestamp;
import java.util.Map;
import org.flywaydb.core.Flyway;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.server.ResponseStatusException;

class AlertLifecycleServiceTest {
    private JdbcTemplate jdbcTemplate;
    private ObjectMapper objectMapper;
    private CoreRequestSupport support;
    private AlertLifecycleService service;

    @BeforeEach
    void setUp() {
        var dataSource = new JdbcDataSource();
        dataSource.setURL(
            "jdbc:h2:mem:alert_lifecycle_test;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;"
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
        objectMapper = new ObjectMapper();
        support = new CoreRequestSupport(objectMapper);
        var repository = new AlertLifecycleRepository(jdbcTemplate, objectMapper, support);
        service = new AlertLifecycleService(repository, support);
    }

    @Test
    void acknowledgeOpenAlertChangesStatusAndWritesTimeline() {
        var alertId = insertAlert("open");
        markOldUpdatedAt(alertId);

        var result = service.acknowledge(alertId, new AlertLifecycleRequest("ops-user", null, "已确认"));

        assertEquals(alertId, number(result.get("id")).longValue());
        assertEquals("acknowledged", stringCell("select status from alerts where id = ?", alertId));
        assertNotNull(timestampCell("select acknowledged_at from alerts where id = ?", alertId));
        assertTrue(timestampCell("select updated_at from alerts where id = ?", alertId).after(Timestamp.valueOf("2026-01-01 00:00:00")));
        assertEquals(1L, count("alert_lifecycle_events"));
        assertEquals("acknowledged", stringCell("select event_type from alert_lifecycle_events where alert_id = ?", alertId));
        assertEquals("open", stringCell("select from_status from alert_lifecycle_events where alert_id = ?", alertId));
        assertEquals("acknowledged", stringCell("select to_status from alert_lifecycle_events where alert_id = ?", alertId));
        assertEquals("ops-user", stringCell("select operator_name from alert_lifecycle_events where alert_id = ?", alertId));
        assertEquals("已确认", stringCell("select note from alert_lifecycle_events where alert_id = ?", alertId));
        assertEquals(0L, count("notification_deliveries"));
    }

    @Test
    void assignDoesNotChangeStatusAndWritesAssigneeTimeline() {
        var openAlertId = insertAlert("open");
        var acknowledgedAlertId = insertAlert("acknowledged");

        service.assign(openAlertId, new AlertLifecycleRequest("ops-user", "zhangsan", "转派"));
        service.assign(acknowledgedAlertId, new AlertLifecycleRequest("ops-user", "lisi", null));

        assertEquals("open", stringCell("select status from alerts where id = ?", openAlertId));
        assertEquals("acknowledged", stringCell("select status from alerts where id = ?", acknowledgedAlertId));
        assertEquals("zhangsan", stringCell("select assigned_to from alerts where id = ?", openAlertId));
        assertEquals("lisi", stringCell("select assigned_to from alerts where id = ?", acknowledgedAlertId));
        assertEquals(2L, count("alert_lifecycle_events"));
        assertEquals(2L, jdbcTemplate.queryForObject(
            "select count(*) from alert_lifecycle_events where event_type = 'assigned' and from_status = to_status",
            Long.class
        ));
        assertEquals(0L, count("notification_deliveries"));
    }

    @Test
    void closeRequiresNoteAndClosesOpenOrAcknowledgedAlert() {
        var missingNoteAlertId = insertAlert("open");
        var openAlertId = insertAlert("open");
        var acknowledgedAlertId = insertAlert("acknowledged");

        var missingNote = assertThrows(
            ResponseStatusException.class,
            () -> service.close(missingNoteAlertId, new AlertLifecycleRequest("ops-user", null, " "))
        );
        service.close(openAlertId, new AlertLifecycleRequest("ops-user", null, "误报关闭"));
        service.close(acknowledgedAlertId, new AlertLifecycleRequest("ops-user", null, "处置完成"));

        assertEquals(HttpStatus.BAD_REQUEST, missingNote.getStatusCode());
        assertEquals("open", stringCell("select status from alerts where id = ?", missingNoteAlertId));
        assertEquals("closed", stringCell("select status from alerts where id = ?", openAlertId));
        assertEquals("closed", stringCell("select status from alerts where id = ?", acknowledgedAlertId));
        assertNotNull(timestampCell("select closed_at from alerts where id = ?", openAlertId));
        assertEquals(2L, jdbcTemplate.queryForObject(
            "select count(*) from alert_lifecycle_events where event_type = 'closed'",
            Long.class
        ));
        assertEquals(0L, count("notification_deliveries"));
    }

    @Test
    void closedAlertIsTerminalAndMissingAlertReturnsNotFound() {
        var closedAlertId = insertAlert("closed");

        var acknowledgeError = assertThrows(
            ResponseStatusException.class,
            () -> service.acknowledge(closedAlertId, new AlertLifecycleRequest("ops-user", null, null))
        );
        var assignError = assertThrows(
            ResponseStatusException.class,
            () -> service.assign(closedAlertId, new AlertLifecycleRequest("ops-user", "zhangsan", null))
        );
        var closeError = assertThrows(
            ResponseStatusException.class,
            () -> service.close(closedAlertId, new AlertLifecycleRequest("ops-user", null, "already closed"))
        );
        var missingError = assertThrows(
            ResponseStatusException.class,
            () -> service.acknowledge(999999L, new AlertLifecycleRequest("ops-user", null, null))
        );

        assertEquals(HttpStatus.BAD_REQUEST, acknowledgeError.getStatusCode());
        assertEquals(HttpStatus.BAD_REQUEST, assignError.getStatusCode());
        assertEquals(HttpStatus.BAD_REQUEST, closeError.getStatusCode());
        assertEquals(HttpStatus.NOT_FOUND, missingError.getStatusCode());
        assertEquals(0L, count("alert_lifecycle_events"));
    }

    @Test
    void staleStatusUpdateCannotReopenClosedAlertOrWriteTimeline() {
        var alertId = insertAlert("open");
        var racingRepository = new AlertLifecycleRepository(jdbcTemplate, objectMapper, support) {
            @Override
            public Map<String, Object> acknowledge(long alertId, String expectedStatus, String operatorName, String note) {
                jdbcTemplate.update("update alerts set status = 'closed' where id = ?", alertId);
                return super.acknowledge(alertId, expectedStatus, operatorName, note);
            }
        };
        var racingService = new AlertLifecycleService(racingRepository, support);

        var error = assertThrows(
            ResponseStatusException.class,
            () -> racingService.acknowledge(alertId, new AlertLifecycleRequest("ops-user", null, "stale"))
        );

        assertEquals(HttpStatus.CONFLICT, error.getStatusCode());
        assertEquals("closed", stringCell("select status from alerts where id = ?", alertId));
        assertEquals(0L, count("alert_lifecycle_events"));
    }

    @Test
    void acknowledgeRequiresOpenAndTimelineIsForOneAlertOnly() {
        var acknowledgedAlertId = insertAlert("acknowledged");
        var openAlertId = insertAlert("open");
        service.assign(openAlertId, new AlertLifecycleRequest("ops-user", "zhangsan", "转派"));

        var error = assertThrows(
            ResponseStatusException.class,
            () -> service.acknowledge(acknowledgedAlertId, new AlertLifecycleRequest("ops-user", null, null))
        );
        var timeline = service.timeline(openAlertId);

        assertEquals(HttpStatus.BAD_REQUEST, error.getStatusCode());
        assertEquals(1, timeline.size());
        assertEquals("assigned", timeline.get(0).get("eventType"));
    }

    private Long insertAlert(String status) {
        return insertAndReturnId("""
            insert into alerts(title, severity, status, detail_json)
            values (?, 'high', ?, cast('{}' as jsonb))
            """, "Alert " + System.nanoTime(), status);
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

    private void markOldUpdatedAt(long alertId) {
        jdbcTemplate.update("update alerts set updated_at = timestamp '2026-01-01 00:00:00' where id = ?", alertId);
    }

    private long count(String table) {
        return jdbcTemplate.queryForObject("select count(*) from " + table, Long.class);
    }

    private String stringCell(String sql, Object... args) {
        return jdbcTemplate.queryForObject(sql, String.class, args);
    }

    private Timestamp timestampCell(String sql, Object... args) {
        return jdbcTemplate.queryForObject(sql, Timestamp.class, args);
    }

    private Number number(Object value) {
        return (Number) value;
    }
}
