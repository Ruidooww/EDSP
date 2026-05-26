package com.edsp.core.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.flywaydb.core.Flyway;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

class NotificationSecretBackfillAuditMigrationTest {
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void setUp() {
        var dataSource = new JdbcDataSource();
        dataSource.setURL(
            "jdbc:h2:mem:notification_secret_backfill_audit_migration_" + System.nanoTime()
                + ";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;"
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
    }

    @Test
    void v17CreatesBackfillAuditTablesWithoutRawSecretColumns() {
        var runColumns = columns("notification_secret_backfill_runs");
        var itemColumns = columns("notification_secret_backfill_items");

        assertTrue(runColumns.contains("id"));
        assertTrue(runColumns.contains("mode"));
        assertTrue(runColumns.contains("status"));
        assertTrue(runColumns.contains("confirmation_accepted"));
        assertTrue(runColumns.contains("requested_by"));
        assertTrue(runColumns.contains("total_requested"));
        assertTrue(itemColumns.contains("run_id"));
        assertTrue(itemColumns.contains("channel_id"));
        assertTrue(itemColumns.contains("endpoint_masked"));
        assertTrue(itemColumns.contains("item_status"));
        assertEquals(false, runColumns.contains("confirmation"));
        assertEquals(false, itemColumns.contains("endpoint_url"));
        assertEquals(false, itemColumns.contains("endpoint_secret_ciphertext"));
        assertEquals(false, itemColumns.contains("endpoint_secret_key_version"));

        var channelColumns = columns("notification_channels");
        assertTrue(channelColumns.contains("endpoint_url"));
        assertTrue(channelColumns.contains("endpoint_secret_ciphertext"));
        assertTrue(channelColumns.contains("endpoint_secret_key_version"));
        assertTrue(channelColumns.contains("secret_storage_status"));
    }

    @Test
    void v17ConstrainsRunModeRunStatusAndItemStatus() {
        jdbcTemplate.update("""
            insert into notification_secret_backfill_runs(mode, status, confirmation_accepted, requested_by)
            values ('manual_channel_ids', 'running', true, 'manual')
            """);
        var runId = jdbcTemplate.queryForObject(
            "select id from notification_secret_backfill_runs",
            Long.class
        );
        jdbcTemplate.update("""
            insert into notification_secret_backfill_items(run_id, channel_id, item_status)
            values (?, 100, 'skipped')
            """, runId);

        assertThrows(
            DataIntegrityViolationException.class,
            () -> jdbcTemplate.update("""
                insert into notification_secret_backfill_runs(mode, status)
                values ('execute_all', 'running')
                """)
        );
        assertThrows(
            DataIntegrityViolationException.class,
            () -> jdbcTemplate.update("""
                insert into notification_secret_backfill_runs(mode, status)
                values ('manual_channel_ids', 'unknown')
                """)
        );
        assertThrows(
            DataIntegrityViolationException.class,
            () -> jdbcTemplate.update("""
                insert into notification_secret_backfill_items(run_id, channel_id, item_status)
                values (?, 101, 'unknown')
                """, runId)
        );
    }

    private java.util.List<String> columns(String tableName) {
        return jdbcTemplate.queryForList("""
            select column_name
            from information_schema.columns
            where table_name = ?
            order by column_name
            """, String.class, tableName);
    }
}
