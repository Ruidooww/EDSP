package com.edsp.core.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import com.edsp.core.dto.SchemaScanExecuteRequest;
import com.edsp.core.service.JdbcMetadataScanService.MetadataField;
import com.edsp.core.service.JdbcMetadataScanService.MetadataScanResult;
import com.edsp.core.service.JdbcMetadataScanService.MetadataTable;
import com.edsp.core.support.CoreRequestSupport;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.flywaydb.core.Flyway;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

class SchemaScanServiceTest {
    private JdbcTemplate jdbcTemplate;
    private SchemaScanService service;
    private AtomicReference<MetadataScanResult> scanResult;

    @BeforeEach
    void setUp() {
        var dataSource = new JdbcDataSource();
        dataSource.setURL(
            "jdbc:h2:mem:schema_scan_test;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;"
                + "DB_CLOSE_DELAY=-1;INIT=CREATE DOMAIN IF NOT EXISTS TIMESTAMPTZ AS TIMESTAMP WITH TIME ZONE\\;"
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
        var objectMapper = new ObjectMapper();
        scanResult = new AtomicReference<>();
        service = new SchemaScanService(
            jdbcTemplate,
            new FakeMetadataScanService(objectMapper, scanResult),
            new CoreRequestSupport(objectMapper),
            objectMapper,
            new TransactionTemplate(new DataSourceTransactionManager(dataSource))
        );
    }

    @Test
    void limitedScanDoesNotMarkUnseenHistoricalObjectsRemoved() {
        var dataSourceId = insertDataSource();
        var legacyTableId = insertHistoricalTable(dataSourceId, "dbo.legacy_alert");
        insertHistoricalField(legacyTableId, "legacy_user");
        scanResult.set(new MetadataScanResult(
            "demo",
            List.of(new MetadataTable(
                "demo",
                "dbo",
                "current_alert",
                "table",
                10L,
                null,
                List.of(new MetadataField("event_id", "varchar", false, 1))
            )),
            0,
            3,
            true
        ));

        var result = service.execute(new SchemaScanExecuteRequest(dataSourceId, "", 1, 50, false));

        assertEquals("success", result.get("status"));
        assertEquals("active", lifecycleStatus("schema_tables", legacyTableId));
        assertEquals(0L, countRemovedChanges());
        var run = latestRun();
        assertEquals(3, ((Number) run.get("total_tables")).intValue());
        assertEquals(1, ((Number) run.get("scanned_tables")).intValue());
        assertEquals(Boolean.TRUE, run.get("limited"));
        assertEquals(33.3333d, ((Number) run.get("coverage_rate")).doubleValue(), 0.0001d);
    }

    @Test
    void fullScanMarksUnseenHistoricalObjectsRemoved() {
        var dataSourceId = insertDataSource();
        var legacyTableId = insertHistoricalTable(dataSourceId, "dbo.legacy_alert");
        insertHistoricalField(legacyTableId, "legacy_user");
        scanResult.set(new MetadataScanResult(
            "demo",
            List.of(new MetadataTable(
                "demo",
                "dbo",
                "current_alert",
                "table",
                10L,
                null,
                List.of(new MetadataField("event_id", "varchar", false, 1))
            )),
            0,
            1,
            false
        ));

        var result = service.execute(new SchemaScanExecuteRequest(dataSourceId, "", 100, 50, false));

        assertEquals("success", result.get("status"));
        assertEquals("removed", lifecycleStatus("schema_tables", legacyTableId));
        assertFalse(countRemovedChanges() == 0);
        var run = latestRun();
        assertEquals(1, ((Number) run.get("total_tables")).intValue());
        assertEquals(1, ((Number) run.get("scanned_tables")).intValue());
        assertEquals(Boolean.FALSE, run.get("limited"));
        assertEquals(100.0d, ((Number) run.get("coverage_rate")).doubleValue(), 0.0001d);
    }

    private Long insertDataSource() {
        jdbcTemplate.update("""
            insert into data_sources(name, source_type, connection_kind, config_json, status, enabled)
            values ('Metadata Demo', 'h2', 'database', cast('{"jdbcUrl":"jdbc:h2:mem:unused"}' as jsonb), 'active', true)
            """);
        return lastId("data_sources");
    }

    private Long insertHistoricalTable(Long dataSourceId, String tableName) {
        jdbcTemplate.update("""
            insert into schema_tables(data_source_id, table_name, category, confirmation_status, lifecycle_status)
            values (?, ?, 'alert_event', 'confirmed', 'active')
            """, dataSourceId, tableName);
        return lastId("schema_tables");
    }

    private void insertHistoricalField(Long schemaTableId, String fieldName) {
        jdbcTemplate.update("""
            insert into schema_fields(schema_table_id, field_name, field_type, lifecycle_status, confidence)
            values (?, ?, 'varchar', 'active', 80)
            """, schemaTableId, fieldName);
    }

    private String lifecycleStatus(String tableName, Long id) {
        return jdbcTemplate.queryForObject(
            "select lifecycle_status from " + tableName + " where id = ?",
            String.class,
            id
        );
    }

    private Long countRemovedChanges() {
        return jdbcTemplate.queryForObject(
            "select count(*) from schema_change_events where change_type = 'removed'",
            Long.class
        );
    }

    private Map<String, Object> latestRun() {
        return jdbcTemplate.queryForMap("""
            select total_tables, scanned_tables, limited, coverage_rate
            from schema_scan_runs
            order by id desc
            limit 1
            """);
    }

    private Long lastId(String tableName) {
        return jdbcTemplate.queryForObject("select max(id) from " + tableName, Long.class);
    }

    private static class FakeMetadataScanService extends JdbcMetadataScanService {
        private final AtomicReference<MetadataScanResult> scanResult;

        private FakeMetadataScanService(ObjectMapper objectMapper, AtomicReference<MetadataScanResult> scanResult) {
            super(objectMapper);
            this.scanResult = scanResult;
        }

        @Override
        public MetadataScanResult scan(
            String sourceType,
            Object configValue,
            String databaseOverride,
            int tableLimit,
            int fieldLimit,
            boolean includeViews
        ) {
            return scanResult.get();
        }
    }
}
