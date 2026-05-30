package com.edsp.core.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.edsp.core.dto.IngestionPlanShadowRunRequest;
import com.edsp.core.support.CoreRequestSupport;
import com.edsp.core.transform.TransformPlanSupport;
import com.edsp.core.transform.runtime.LocalTransformRuntimeClient;
import com.edsp.core.transform.runtime.TransformBatchResult;
import com.edsp.core.transform.runtime.TransformRuntimeClient;
import com.edsp.core.transform.runtime.TransformRuntimeException;
import com.edsp.core.transform.runtime.TransformRuntimeReport;
import com.edsp.transform.contract.BatchTransformRequest;
import com.edsp.transform.contract.TransformDraftDto;
import com.edsp.transform.contract.TransformResponse;
import com.edsp.transform.standardevent.StandardEventTransformService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.sql.DriverManager;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import org.flywaydb.core.Flyway;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.server.ResponseStatusException;

class IngestionPlanShadowRunServiceTest {
    private static final String SOURCE_URL =
        "jdbc:h2:mem:shadow_source_test;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1";

    private JdbcTemplate jdbcTemplate;
    private ObjectMapper objectMapper;
    private IngestionPlanShadowRunService service;
    private RecordingTransformRuntimeClient transformRuntimeClient;

    @BeforeEach
    void setUp() throws Exception {
        var dataSource = new JdbcDataSource();
        dataSource.setURL(
            "jdbc:h2:mem:ingestion_plan_shadow_run_test;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;"
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
        var support = new CoreRequestSupport(objectMapper);
        var planFingerprintSupport = new PlanFingerprintSupport(objectMapper);
        var precheckService = new IngestionPlanPrecheckService(jdbcTemplate, objectMapper, support);
        var sampleService = new JdbcShadowSampleService(objectMapper);
        var planSupport = new TransformPlanSupport(objectMapper, support);
        transformRuntimeClient = new RecordingTransformRuntimeClient(
            new LocalTransformRuntimeClient(new StandardEventTransformService())
        );
        service = new IngestionPlanShadowRunService(
            jdbcTemplate,
            objectMapper,
            support,
            precheckService,
            sampleService,
            planSupport,
            planFingerprintSupport,
            transformRuntimeClient
        );
        resetSourceDatabase();
    }

    @Test
    void createShadowRunSamplesApprovedPlanAndPersistsSafePreviewReport() throws Exception {
        executeSourceSql("update sec_alert_event set user_account = 'USER_A' where id = 'ALERT-1'");
        var dataSourceId = insertDataSource(SOURCE_URL);
        var scanRunId = insertCompleteScan(dataSourceId);
        var tableId = insertTable(dataSourceId, scanRunId, "sec_alert_event", "alert_table");
        insertField(tableId, scanRunId, "id", "varchar", 1, null);
        insertField(tableId, scanRunId, "create_time", "timestamp", 2, null);
        insertField(tableId, scanRunId, "event_name", "varchar", 3, null);
        insertField(tableId, scanRunId, "user_account", "varchar", 4, null);
        insertField(tableId, scanRunId, "host_name", "varchar", 5, null);
        insertField(tableId, scanRunId, "phone", "varchar", 6, "sensitive_value");
        insertField(tableId, scanRunId, "raw_payload", "json", 7, "detail");
        insertField(tableId, scanRunId, "ignored_secret", "varchar", 8, "sensitive_value");
        var planId = insertPlan(dataSourceId, scanRunId, tableId, "approved");

        var run = service.createShadowRun(planId, new IngestionPlanShadowRunRequest(5000));

        assertEquals(1, transformRuntimeClient.calls);
        assertEquals(2, transformRuntimeClient.lastRequest.rows().size());
        assertEquals("externalId", transformRuntimeClient.lastRequest.mappingPlan().fieldMappings().get("id"));
        assertEquals("occurredAt", transformRuntimeClient.lastRequest.mappingPlan().fieldMappings().get("create_time"));
        assertEquals(List.of("id"), transformRuntimeClient.lastRequest.mappingPlan().dedupFields());
        var mappingDetails = transformRuntimeClient.lastRequest.mappingPlan().fieldMappingDetails();
        assertEquals(1, mappingDetails.size());
        assertEquals("user_account", mappingDetails.get(0).sourceField());
        assertEquals("actor", mappingDetails.get(0).standardField());
        assertEquals("lower", mappingDetails.get(0).transformRule());
        assertEquals("valueMap", mappingDetails.get(0).transformRulePayload().get("type"));
        assertEquals(Map.of("USER_A", "ignored"), mappingDetails.get(0).transformRulePayload().get("values"));
        assertEquals(dataSourceId, transformRuntimeClient.lastRequest.options().dataSourceId());
        assertEquals(tableId, transformRuntimeClient.lastRequest.options().schemaTableId());
        assertEquals("sec_alert_event", transformRuntimeClient.lastRequest.options().sourceTable());
        assertEquals("shadow_run", transformRuntimeClient.lastRequest.options().syncMode());
        assertEquals("passed", run.get("status"));
        assertEquals(100, ((Number) run.get("sampleLimit")).intValue());
        assertEquals(2, ((Number) run.get("readCount")).intValue());
        assertEquals(2, ((Number) run.get("successCount")).intValue());
        assertEquals(0, ((Number) run.get("failedCount")).intValue());
        assertEquals(0, ((Number) run.get("duplicateCount")).intValue());
        assertEquals("approved", planStatus(planId));
        assertEquals(1L, count("ingestion_plan_shadow_runs"));
        assertEquals(0L, count("raw_events"));
        assertEquals(0L, count("standard_events"));
        assertEquals(0L, count("alert_decisions"));
        assertEquals(0L, count("alerts"));

        var report = objectValue(run.get("report"));
        assertPlanFingerprint(report);
        var previewPolicy = objectValue(report.get("previewPolicy"));
        assertEquals("mapped_fields_only", previewPolicy.get("mode"));
        assertEquals(true, previewPolicy.get("maskedSensitiveValues"));
        assertEquals(20, ((Number) previewPolicy.get("maxSamples")).intValue());
        assertEquals(List.of("detail"), previewPolicy.get("excludedSemantics"));

        var samples = objectList(report.get("samples"));
        assertEquals(2, samples.size());
        var firstSample = samples.get(0);
        var sourcePreview = objectValue(firstSample.get("sourcePreview"));
        assertEquals("ALERT-1", sourcePreview.get("id"));
        assertEquals("******", sourcePreview.get("phone"));
        assertFalse(sourcePreview.containsKey("ignored_secret"));
        assertTrue(objectValue(sourcePreview.get("raw_payload")).containsKey("sha256"));
        assertFalse(String.valueOf(sourcePreview.get("raw_payload")).contains("raw secret payload"));
        var standardPreview = objectValue(firstSample.get("standardEventPreview"));
        assertEquals("ALERT-1", standardPreview.get("externalId"));
        assertEquals("2026-05-20T10:30+08:00", standardPreview.get("occurredAt"));
        assertEquals("Sensitive file export", standardPreview.get("title"));
        assertEquals("user_a", standardPreview.get("actor"));
        assertEquals("******", standardPreview.get("subjectRef"));
        assertTrue(objectValue(standardPreview.get("detail")).containsKey("sha256"));
        assertFalse(String.valueOf(standardPreview.get("detail")).contains("raw secret payload"));
        assertEquals(64, String.valueOf(firstSample.get("dedupKeyPreview")).length());

        var recentRuns = service.listShadowRuns(planId, 10);
        assertEquals(1, recentRuns.size());
        assertFalse(recentRuns.get(0).containsKey("report"));
        var detail = service.shadowRunDetail(((Number) run.get("id")).longValue());
        assertEquals(report, detail.get("report"));
    }

    @Test
    void createShadowRunPreviewUsesValueMapThroughRuntimeClient() throws Exception {
        executeSourceSql("update sec_alert_event set user_account = 'USER_A' where id = 'ALERT-1'");
        var dataSourceId = insertDataSource(SOURCE_URL);
        var scanRunId = insertCompleteScan(dataSourceId);
        var tableId = insertTable(dataSourceId, scanRunId, "sec_alert_event", "alert_table");
        insertField(tableId, scanRunId, "id", "varchar", 1, null);
        insertField(tableId, scanRunId, "create_time", "timestamp", 2, null);
        insertField(tableId, scanRunId, "event_name", "varchar", 3, null);
        insertField(tableId, scanRunId, "user_account", "varchar", 4, null);
        insertField(tableId, scanRunId, "host_name", "varchar", 5, null);
        var planId = insertValueMapPlan(dataSourceId, scanRunId, tableId);

        var run = service.createShadowRun(planId, new IngestionPlanShadowRunRequest(5000));

        assertEquals("passed", run.get("status"));
        assertEquals(1, transformRuntimeClient.calls);
        var mappingDetails = transformRuntimeClient.lastRequest.mappingPlan().fieldMappingDetails();
        assertEquals(1, mappingDetails.size());
        assertEquals("valueMap", mappingDetails.get(0).transformRule());
        assertEquals("valueMap", mappingDetails.get(0).transformRulePayload().get("type"));
        var report = objectValue(run.get("report"));
        assertPlanFingerprint(report);
        var samples = objectList(report.get("samples"));
        var firstStandardPreview = objectValue(samples.get(0).get("standardEventPreview"));
        assertEquals("mapped-user", firstStandardPreview.get("actor"));
        assertEquals(0L, count("raw_events"));
        assertEquals(0L, count("standard_events"));
    }

    @Test
    void createShadowRunSummarizesStandardDetailPreviewByTargetFieldName() {
        var dataSourceId = insertDataSource(SOURCE_URL);
        var scanRunId = insertCompleteScan(dataSourceId);
        var tableId = insertTable(dataSourceId, scanRunId, "sec_alert_event", "alert_table");
        insertField(tableId, scanRunId, "id", "varchar", 1, null);
        insertField(tableId, scanRunId, "create_time", "timestamp", 2, null);
        insertField(tableId, scanRunId, "event_name", "varchar", 3, null);
        insertField(tableId, scanRunId, "user_account", "varchar", 4, null);
        insertField(tableId, scanRunId, "host_name", "varchar", 5, null);
        insertField(tableId, scanRunId, "message", "varchar", 6, null);
        var planId = insertTargetDetailPlan(dataSourceId, scanRunId, tableId);

        var run = service.createShadowRun(planId, new IngestionPlanShadowRunRequest(10));

        assertEquals("passed", run.get("status"));
        var samples = objectList(objectValue(run.get("report")).get("samples"));
        var standardPreview = objectValue(samples.get(0).get("standardEventPreview"));
        assertTrue(objectValue(standardPreview.get("detail")).containsKey("sha256"));
        assertFalse(String.valueOf(standardPreview.get("detail")).contains("message with sensitive narrative"));
    }

    @Test
    void createShadowRunWarnsOnInvalidTimeAndSeverityWhileNormalizingValidPreview() throws Exception {
        executeSourceSql("""
            update sec_alert_event
            set risk_level = case id when 'ALERT-1' then 'high' else 'unknown-level' end,
                create_time = case id when 'ALERT-1' then '2026-05-20 10:30:00' else 'not-a-time' end
            """);
        var dataSourceId = insertDataSource(SOURCE_URL);
        var scanRunId = insertCompleteScan(dataSourceId);
        var tableId = insertTable(dataSourceId, scanRunId, "sec_alert_event", "alert_table");
        insertField(tableId, scanRunId, "id", "varchar", 1, null);
        insertField(tableId, scanRunId, "create_time", "timestamp", 2, null);
        insertField(tableId, scanRunId, "event_name", "varchar", 3, null);
        insertField(tableId, scanRunId, "user_account", "varchar", 4, null);
        insertField(tableId, scanRunId, "host_name", "varchar", 5, null);
        insertField(tableId, scanRunId, "risk_level", "varchar", 6, null);
        var planId = insertSeverityPlan(dataSourceId, scanRunId, tableId);

        var run = service.createShadowRun(planId, new IngestionPlanShadowRunRequest(20));

        assertEquals("warning", run.get("status"));
        assertEquals(2, ((Number) run.get("readCount")).intValue());
        assertEquals(1, ((Number) run.get("successCount")).intValue());
        assertEquals(1, ((Number) run.get("failedCount")).intValue());
        var report = objectValue(run.get("report"));
        var errorsByType = objectValue(report.get("errorsByType"));
        assertEquals(1, ((Number) errorsByType.get("invalid_time_format")).intValue());
        assertEquals(1, ((Number) errorsByType.get("severity_unrecognized")).intValue());
        var samples = objectList(report.get("samples"));
        var failedSample = samples.stream()
            .filter(sample -> stringList(sample.get("errors")).contains("invalid_time_format"))
            .findFirst()
            .orElseThrow();
        assertTrue(stringList(failedSample.get("errors")).contains("severity_unrecognized"));
        var failedStandardPreview = objectValue(failedSample.get("standardEventPreview"));
        assertFalse(failedStandardPreview.containsKey("occurredAt"));
        assertEquals("info", failedStandardPreview.get("severity"));
        var validSample = samples.stream()
            .filter(sample -> "ALERT-1".equals(objectValue(sample.get("sourcePreview")).get("id")))
            .findFirst()
            .orElseThrow();
        assertEquals(64, String.valueOf(validSample.get("dedupKeyPreview")).length());
        var standardPreview = objectValue(validSample.get("standardEventPreview"));
        assertEquals("2026-05-20T10:30+08:00", standardPreview.get("occurredAt"));
        assertEquals("high", standardPreview.get("severity"));
    }

    @Test
    void createShadowRunNormalizesSupportedSeverityAliases() throws Exception {
        var dataSourceId = insertDataSource(SOURCE_URL);
        var scanRunId = insertCompleteScan(dataSourceId);
        var tableId = insertTable(dataSourceId, scanRunId, "sec_alert_event", "alert_table");
        insertField(tableId, scanRunId, "id", "varchar", 1, null);
        insertField(tableId, scanRunId, "create_time", "timestamp", 2, null);
        insertField(tableId, scanRunId, "event_name", "varchar", 3, null);
        insertField(tableId, scanRunId, "user_account", "varchar", 4, null);
        insertField(tableId, scanRunId, "host_name", "varchar", 5, null);
        insertField(tableId, scanRunId, "risk_level", "varchar", 6, null);
        var planId = insertSeverityPlan(dataSourceId, scanRunId, tableId);
        for (var entry : Map.of(
            "warning", "medium",
            "1", "critical",
            "2", "high",
            "4", "low"
        ).entrySet()) {
            updateSourceSeverity(entry.getKey());

            var run = service.createShadowRun(planId, new IngestionPlanShadowRunRequest(20));

            assertEquals("passed", run.get("status"));
            var samples = objectList(objectValue(run.get("report")).get("samples"));
            var firstStandardPreview = objectValue(samples.get(0).get("standardEventPreview"));
            assertEquals(entry.getValue(), firstStandardPreview.get("severity"));
        }
    }

    @Test
    void createShadowRunWarnsWhenNoSampleRowsAreRead() throws Exception {
        executeSourceSql("delete from sec_alert_event");
        var dataSourceId = insertDataSource(SOURCE_URL);
        var scanRunId = insertCompleteScan(dataSourceId);
        var tableId = insertTable(dataSourceId, scanRunId, "sec_alert_event", "alert_table");
        insertField(tableId, scanRunId, "id", "varchar", 1, null);
        insertField(tableId, scanRunId, "create_time", "timestamp", 2, null);
        insertField(tableId, scanRunId, "event_name", "varchar", 3, null);
        insertField(tableId, scanRunId, "user_account", "varchar", 4, null);
        insertField(tableId, scanRunId, "host_name", "varchar", 5, null);
        insertField(tableId, scanRunId, "phone", "varchar", 6, "sensitive_value");
        insertField(tableId, scanRunId, "raw_payload", "json", 7, "detail");
        var planId = insertPlan(dataSourceId, scanRunId, tableId, "approved");

        var run = service.createShadowRun(planId, new IngestionPlanShadowRunRequest(20));

        assertEquals("warning", run.get("status"));
        assertEquals(0, ((Number) run.get("readCount")).intValue());
        assertEquals(0, ((Number) run.get("successCount")).intValue());
        assertEquals(0, ((Number) run.get("failedCount")).intValue());
        var report = objectValue(run.get("report"));
        assertPlanFingerprint(report);
        assertTrue(stringList(report.get("warnings")).contains("no_sample_rows"));
        assertEquals(0, transformRuntimeClient.calls);
    }

    @Test
    void createShadowRunUsesRuntimeDraftDedupKeyForDuplicatePreview() {
        transformRuntimeClient.handler = request -> new TransformBatchResult(List.of(
            response("runtime-dedup-key", "ALERT-1", "high", "2026-05-20T10:30+08:00", Map.of()),
            response("runtime-dedup-key", "ALERT-2", "high", "2026-05-20T10:31+08:00", Map.of())
        ), TransformRuntimeReport.disabled());
        var dataSourceId = insertDataSource(SOURCE_URL);
        var scanRunId = insertCompleteScan(dataSourceId);
        var tableId = insertTable(dataSourceId, scanRunId, "sec_alert_event", "alert_table");
        insertField(tableId, scanRunId, "id", "varchar", 1, null);
        insertField(tableId, scanRunId, "create_time", "timestamp", 2, null);
        insertField(tableId, scanRunId, "event_name", "varchar", 3, null);
        insertField(tableId, scanRunId, "user_account", "varchar", 4, null);
        insertField(tableId, scanRunId, "phone", "varchar", 6, "sensitive_value");
        insertField(tableId, scanRunId, "raw_payload", "json", 7, "detail");
        insertField(tableId, scanRunId, "host_name", "varchar", 5, null);
        var planId = insertPlan(dataSourceId, scanRunId, tableId, "approved");

        var run = service.createShadowRun(planId, new IngestionPlanShadowRunRequest(20));

        assertEquals("warning", run.get("status"));
        assertEquals(2, ((Number) run.get("successCount")).intValue());
        assertEquals(1, ((Number) run.get("duplicateCount")).intValue());
        var report = objectValue(run.get("report"));
        assertPlanFingerprint(report);
        assertTrue(stringList(report.get("warnings")).contains("duplicate_in_sample"));
        var duplicateSample = objectList(report.get("samples")).stream()
            .filter(sample -> stringList(sample.get("warnings")).contains("duplicate_in_sample"))
            .findFirst()
            .orElseThrow();
        assertEquals(64, String.valueOf(duplicateSample.get("dedupKeyPreview")).length());
        assertFalse(String.valueOf(duplicateSample.get("dedupKeyPreview")).contains("runtime-dedup-key"));
    }

    @Test
    void createShadowRunRejectsPlanBeforeApprovalWithoutPersistingRun() {
        var dataSourceId = insertDataSource(SOURCE_URL);
        var scanRunId = insertCompleteScan(dataSourceId);
        var tableId = insertTable(dataSourceId, scanRunId, "sec_alert_event", "alert_table");
        insertField(tableId, scanRunId, "id", "varchar", 1, null);
        insertField(tableId, scanRunId, "create_time", "timestamp", 2, null);
        var planId = insertPlan(dataSourceId, scanRunId, tableId, "suggested");

        var ex = assertThrows(
            ResponseStatusException.class,
            () -> service.createShadowRun(planId, new IngestionPlanShadowRunRequest(20))
        );

        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatusCode());
        assertEquals(0L, count("ingestion_plan_shadow_runs"));
    }

    @Test
    void createShadowRunPreservesPrecheckWarningsInRunStatusAndReport() {
        var dataSourceId = insertDataSource(SOURCE_URL);
        var scanRunId = insertCompleteScan(dataSourceId);
        var tableId = insertTable(dataSourceId, scanRunId, "sec_alert_event", "alert_table");
        insertField(tableId, scanRunId, "id", "varchar", 1, null);
        insertField(tableId, scanRunId, "create_time", "timestamp", 2, null);
        insertField(tableId, scanRunId, "event_name", "varchar", 3, null);
        insertField(tableId, scanRunId, "user_account", "varchar", 4, null);
        insertField(tableId, scanRunId, "host_name", "varchar", 5, null);
        insertField(tableId, scanRunId, "phone", "varchar", 6, "sensitive_value");
        insertField(tableId, scanRunId, "raw_payload", "json", 7, "detail");
        var planId = insertWarningPlan(dataSourceId, scanRunId, tableId);

        var run = service.createShadowRun(planId, new IngestionPlanShadowRunRequest(10));

        assertEquals("warning", run.get("status"));
        var report = objectValue(run.get("report"));
        assertPlanFingerprint(report);
        assertEquals("warning", report.get("status"));
        assertTrue(stringList(report.get("warnings")).contains("coverage_unknown"));
    }

    @Test
    void createShadowRunPersistsBlockedReportWhenPrecheckFindsPlanBlocker() {
        var dataSourceId = insertDataSource(SOURCE_URL);
        var scanRunId = insertCompleteScan(dataSourceId);
        var tableId = insertTable(dataSourceId, scanRunId, "sec_alert_event", "alert_table");
        insertField(tableId, scanRunId, "id", "varchar", 1, null);
        var planId = insertBlockedPlan(dataSourceId, scanRunId, tableId);

        var run = service.createShadowRun(planId, new IngestionPlanShadowRunRequest(20));

        assertEquals("blocked", run.get("status"));
        assertEquals(0, transformRuntimeClient.calls);
        assertEquals(0, ((Number) run.get("readCount")).intValue());
        assertEquals(1L, count("ingestion_plan_shadow_runs"));
        var report = objectValue(run.get("report"));
        assertPlanFingerprint(report);
        assertTrue(objectList(report.get("checks")).stream().anyMatch(check ->
            "required_fields".equals(check.get("code")) && "failed".equals(check.get("result"))
        ));
    }

    @Test
    void createShadowRunPersistsBlockedReportWhenPlanReferencesInactiveSourceFieldAfterPrecheck() {
        var dataSourceId = insertDataSource(SOURCE_URL);
        var scanRunId = insertCompleteScan(dataSourceId);
        var tableId = insertTable(dataSourceId, scanRunId, "sec_alert_event", "alert_table");
        insertField(tableId, scanRunId, "id", "varchar", 1, null);
        insertField(tableId, scanRunId, "create_time", "timestamp", 2, null);
        insertField(tableId, scanRunId, "event_name", "varchar", 3, null);
        var planId = insertMissingCursorMetadataPlan(dataSourceId, scanRunId, tableId);

        var run = service.createShadowRun(planId, new IngestionPlanShadowRunRequest(20));

        assertEquals("blocked", run.get("status"));
        assertEquals(0, transformRuntimeClient.calls);
        assertEquals(0, ((Number) run.get("readCount")).intValue());
        assertEquals(1L, count("ingestion_plan_shadow_runs"));
        assertNotNull(run.get("errorMessage"));
        var report = objectValue(run.get("report"));
        assertPlanFingerprint(report);
        assertTrue(stringList(report.get("blockers")).contains("source_fields_missing"));
        assertEquals(1, ((Number) objectValue(report.get("errorsByType")).get("source_fields_missing")).intValue());
        assertTrue(objectList(report.get("checks")).stream().anyMatch(check ->
            "source_fields".equals(check.get("code")) && "failed".equals(check.get("result"))
        ));
    }

    @Test
    void createShadowRunPersistsFailedRunWhenJdbcSamplingFails() {
        var dataSourceId = insertDataSource("jdbc:h2:mem:shadow_missing_source;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE");
        var scanRunId = insertCompleteScan(dataSourceId);
        var tableId = insertTable(dataSourceId, scanRunId, "sec_alert_event", "alert_table");
        insertField(tableId, scanRunId, "id", "varchar", 1, null);
        insertField(tableId, scanRunId, "create_time", "timestamp", 2, null);
        insertField(tableId, scanRunId, "event_name", "varchar", 3, null);
        insertField(tableId, scanRunId, "user_account", "varchar", 4, null);
        insertField(tableId, scanRunId, "host_name", "varchar", 5, null);
        insertField(tableId, scanRunId, "phone", "varchar", 6, "sensitive_value");
        insertField(tableId, scanRunId, "raw_payload", "json", 7, "detail");
        var planId = insertPlan(dataSourceId, scanRunId, tableId, "approved");

        var run = service.createShadowRun(planId, new IngestionPlanShadowRunRequest(20));

        assertEquals("failed", run.get("status"));
        assertNotNull(run.get("errorMessage"));
        assertFalse(String.valueOf(run.get("errorMessage")).contains("super-secret"));
        assertPlanFingerprint(objectValue(run.get("report")));
        assertEquals(1L, count("ingestion_plan_shadow_runs"));
        assertEquals(0L, count("standard_events"));
    }

    @Test
    void createShadowRunPersistsFailedRunWhenRuntimeFailsWithoutWritingBusinessTables() {
        transformRuntimeClient.failure = new TransformRuntimeException(
            "remote_unavailable",
            "remote failed password=super-secret jdbcUrl=" + SOURCE_URL + " raw_payload=raw secret payload",
            TransformRuntimeReport.remoteFailure("remote", "remote_unavailable", false)
        );
        var dataSourceId = insertDataSource(SOURCE_URL);
        var scanRunId = insertCompleteScan(dataSourceId);
        var tableId = insertTable(dataSourceId, scanRunId, "sec_alert_event", "alert_table");
        insertField(tableId, scanRunId, "id", "varchar", 1, null);
        insertField(tableId, scanRunId, "create_time", "timestamp", 2, null);
        insertField(tableId, scanRunId, "event_name", "varchar", 3, null);
        insertField(tableId, scanRunId, "user_account", "varchar", 4, null);
        insertField(tableId, scanRunId, "host_name", "varchar", 5, null);
        insertField(tableId, scanRunId, "phone", "varchar", 6, "sensitive_value");
        insertField(tableId, scanRunId, "raw_payload", "json", 7, "detail");
        var planId = insertPlan(dataSourceId, scanRunId, tableId, "approved");

        var run = service.createShadowRun(planId, new IngestionPlanShadowRunRequest(20));

        assertEquals("failed", run.get("status"));
        assertEquals(1, transformRuntimeClient.calls);
        var errorMessage = String.valueOf(run.get("errorMessage"));
        assertTrue(errorMessage.contains("remote_unavailable"));
        assertFalse(errorMessage.contains("super-secret"));
        assertFalse(errorMessage.contains(SOURCE_URL));
        assertFalse(errorMessage.contains("raw secret payload"));
        assertPlanFingerprint(objectValue(run.get("report")));
        assertEquals("approved", planStatus(planId));
        assertEquals(1L, count("ingestion_plan_shadow_runs"));
        assertEquals(0L, count("raw_events"));
        assertEquals(0L, count("standard_events"));
        assertEquals(0L, count("alert_decisions"));
        assertEquals(0L, count("alerts"));
    }

    private void resetSourceDatabase() throws Exception {
        try (var connection = DriverManager.getConnection(SOURCE_URL, "sa", "super-secret");
             var statement = connection.createStatement()) {
            statement.execute("drop table if exists sec_alert_event");
            statement.execute("""
                create table sec_alert_event(
                    id varchar(40),
                    create_time varchar(40),
                    event_name varchar(200),
                    user_account varchar(80),
                    host_name varchar(80),
                    risk_level varchar(40),
                    phone varchar(40),
                    raw_payload varchar(500),
                    ignored_secret varchar(80)
                )
                """);
            statement.executeUpdate("""
                insert into sec_alert_event(
                    id, create_time, event_name, user_account, host_name, risk_level, phone, raw_payload, ignored_secret
                ) values
                ('ALERT-1', '2026-05-20 10:30:00', 'Sensitive file export', 'zhangsan', 'WIN-01',
                 'high', '13800000000', '{"text":"raw secret payload"}', 'do-not-store'),
                ('ALERT-2', '2026-05-20 10:31:00', 'Suspicious login', 'lisi', 'WIN-02',
                 'medium', '13900000000', '{"text":"another raw secret"}', 'do-not-store-2')
                """);
            statement.execute("alter table sec_alert_event add column if not exists message varchar(500)");
            statement.executeUpdate("""
                update sec_alert_event
                set message = 'message with sensitive narrative'
                where id = 'ALERT-1'
                """);
            statement.executeUpdate("""
                update sec_alert_event
                set message = 'another message with sensitive narrative'
                where id = 'ALERT-2'
                """);
        }
    }

    private void executeSourceSql(String sql) throws Exception {
        try (var connection = DriverManager.getConnection(SOURCE_URL, "sa", "super-secret");
             var statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    private void updateSourceSeverity(String severity) throws Exception {
        try (var connection = DriverManager.getConnection(SOURCE_URL, "sa", "super-secret");
             var statement = connection.prepareStatement("update sec_alert_event set risk_level = ?")) {
            statement.setString(1, severity);
            statement.executeUpdate();
        }
    }

    private Long insertDataSource(String jdbcUrl) {
        jdbcTemplate.update("""
            insert into data_sources(name, source_type, connection_kind, config_json, status, enabled)
            values ('Shadow Source', 'h2', 'database', cast(? as jsonb), 'active', true)
            """, "{\"jdbcUrl\":\"" + jdbcUrl + "\",\"username\":\"sa\",\"password\":\"super-secret\"}");
        return lastId("data_sources");
    }

    private Long insertCompleteScan(Long dataSourceId) {
        jdbcTemplate.update("""
            insert into schema_scan_runs(
                data_source_id, status, total_tables, scanned_tables, failed_tables, total_fields, scanned_fields
            )
            values (?, 'success', 1, 1, 0, 8, 8)
            """, dataSourceId);
        return lastId("schema_scan_runs");
    }

    private Long insertTable(Long dataSourceId, Long scanRunId, String tableName, String category) {
        jdbcTemplate.update("""
            insert into schema_tables(
                data_source_id, scan_run_id, schema_name, table_name, category, confirmation_status, lifecycle_status
            )
            values (?, ?, 'public', ?, ?, 'confirmed', 'active')
            """, dataSourceId, scanRunId, tableName, category);
        return lastId("schema_tables");
    }

    private void insertField(Long tableId, Long scanRunId, String fieldName, String fieldType, int ordinal, String semanticType) {
        jdbcTemplate.update("""
            insert into schema_fields(
                schema_table_id, scan_run_id, field_name, field_type, sample_value,
                ordinal_position, semantic_type, confidence, lifecycle_status
            )
            values (?, ?, ?, ?, ?, ?, ?, 80, 'active')
            """, tableId, scanRunId, fieldName, fieldType, fieldName + "-sample", ordinal, semanticType);
    }

    private Long insertPlan(Long dataSourceId, Long scanRunId, Long tableId, String status) {
        jdbcTemplate.update("""
            insert into ingestion_plans(data_source_id, scan_run_id, name, status, plan_json)
            values (?, ?, 'Shadow plan', ?, cast(? as jsonb))
            """, dataSourceId, scanRunId, status, planJson(tableId));
        return lastId("ingestion_plans");
    }

    private Long insertValueMapPlan(Long dataSourceId, Long scanRunId, Long tableId) {
        jdbcTemplate.update("""
            insert into ingestion_plans(data_source_id, scan_run_id, name, status, plan_json)
            values (?, ?, 'Value map plan', 'approved', cast(? as jsonb))
            """, dataSourceId, scanRunId, valueMapPlanJson(tableId));
        return lastId("ingestion_plans");
    }

    private Long insertSeverityPlan(Long dataSourceId, Long scanRunId, Long tableId) {
        jdbcTemplate.update("""
            insert into ingestion_plans(data_source_id, scan_run_id, name, status, plan_json)
            values (?, ?, 'Severity plan', 'approved', cast(? as jsonb))
            """, dataSourceId, scanRunId, severityPlanJson(tableId));
        return lastId("ingestion_plans");
    }

    private Long insertWarningPlan(Long dataSourceId, Long scanRunId, Long tableId) {
        jdbcTemplate.update("""
            insert into ingestion_plans(data_source_id, scan_run_id, name, status, plan_json)
            values (?, ?, 'Warning plan', 'approved', cast(? as jsonb))
            """, dataSourceId, scanRunId, warningPlanJson(tableId));
        return lastId("ingestion_plans");
    }

    private Long insertTargetDetailPlan(Long dataSourceId, Long scanRunId, Long tableId) {
        jdbcTemplate.update("""
            insert into ingestion_plans(data_source_id, scan_run_id, name, status, plan_json)
            values (?, ?, 'Target detail plan', 'approved', cast(? as jsonb))
            """, dataSourceId, scanRunId, targetDetailPlanJson(tableId));
        return lastId("ingestion_plans");
    }

    private Long insertMissingCursorMetadataPlan(Long dataSourceId, Long scanRunId, Long tableId) {
        jdbcTemplate.update("""
            insert into ingestion_plans(data_source_id, scan_run_id, name, status, plan_json)
            values (?, ?, 'Missing cursor metadata plan', 'approved', cast(? as jsonb))
            """, dataSourceId, scanRunId, missingCursorMetadataPlanJson(tableId));
        return lastId("ingestion_plans");
    }

    private Long insertBlockedPlan(Long dataSourceId, Long scanRunId, Long tableId) {
        jdbcTemplate.update("""
            insert into ingestion_plans(data_source_id, scan_run_id, name, status, plan_json)
            values (?, ?, 'Blocked plan', 'approved', cast(? as jsonb))
            """, dataSourceId, scanRunId, blockedPlanJson(tableId));
        return lastId("ingestion_plans");
    }

    private String planJson(Long tableId) {
        return """
            {
              "version": "ingestion-plan-v1",
              "mode": "database_polling",
              "mainTable": "sec_alert_event",
              "schemaTableId": %d,
              "cursorField": "create_time",
              "fieldMappings": {
                "id": "externalId",
                "create_time": "occurredAt",
                "event_name": "title",
                "user_account": "actor",
                "host_name": "assetRef",
                "phone": "subjectRef",
                "raw_payload": "detail"
              },
              "fieldMappingDetails": [
                {
                  "sourceField": "user_account",
                  "standardField": "actor",
                  "transformRule": "lower",
                  "transformRulePayload": {
                    "type": "valueMap",
                    "values": {
                      "USER_A": "ignored"
                    },
                    "onMissing": "keepOriginal"
                  }
                }
              ],
              "dedupStrategy": {"type": "external_id", "fields": ["id"], "fallback": "composite"},
              "syncStrategy": {"type": "polling", "cursorField": "create_time", "shadowOnly": true, "enabled": false},
              "risks": [],
              "requiredFieldsMissing": []
            }
            """.formatted(tableId);
    }

    private String valueMapPlanJson(Long tableId) {
        return """
            {
              "version": "ingestion-plan-v1",
              "mode": "database_polling",
              "mainTable": "sec_alert_event",
              "schemaTableId": %d,
              "cursorField": "create_time",
              "fieldMappings": {
                "id": "externalId",
                "create_time": "occurredAt",
                "event_name": "title",
                "user_account": "actor",
                "host_name": "assetRef"
              },
              "fieldMappingDetails": [
                {
                  "sourceField": "user_account",
                  "standardField": "actor",
                  "transformRule": "valueMap",
                  "transformRulePayload": {
                    "type": "valueMap",
                    "values": {
                      "USER_A": "mapped-user",
                      "lisi": "lisi"
                    },
                    "onMissing": "keepOriginal"
                  }
                }
              ],
              "dedupStrategy": {"type": "external_id", "fields": ["id"], "fallback": "composite"},
              "syncStrategy": {"type": "polling", "cursorField": "create_time", "shadowOnly": true, "enabled": false},
              "risks": [],
              "requiredFieldsMissing": []
            }
            """.formatted(tableId);
    }

    private String severityPlanJson(Long tableId) {
        return """
            {
              "version": "ingestion-plan-v1",
              "mode": "database_polling",
              "mainTable": "sec_alert_event",
              "schemaTableId": %d,
              "cursorField": "create_time",
              "fieldMappings": {
                "id": "externalId",
                "create_time": "occurredAt",
                "event_name": "title",
                "user_account": "actor",
                "host_name": "assetRef",
                "risk_level": "severity"
              },
              "dedupStrategy": {"type": "external_id", "fields": ["id"], "fallback": "composite"},
              "syncStrategy": {"type": "polling", "cursorField": "create_time", "shadowOnly": true, "enabled": false},
              "risks": [],
              "requiredFieldsMissing": []
            }
            """.formatted(tableId);
    }

    private String warningPlanJson(Long tableId) {
        return """
            {
              "version": "ingestion-plan-v1",
              "mode": "database_polling",
              "mainTable": "sec_alert_event",
              "schemaTableId": %d,
              "cursorField": "create_time",
              "fieldMappings": {
                "id": "externalId",
                "create_time": "occurredAt",
                "event_name": "title",
                "user_account": "actor",
                "host_name": "assetRef",
                "phone": "subjectRef",
                "raw_payload": "detail"
              },
              "dedupStrategy": {"type": "external_id", "fields": ["id"], "fallback": "composite"},
              "syncStrategy": {"type": "polling", "cursorField": "create_time", "shadowOnly": true, "enabled": false},
              "risks": ["coverage_unknown"],
              "requiredFieldsMissing": []
            }
            """.formatted(tableId);
    }

    private String targetDetailPlanJson(Long tableId) {
        return """
            {
              "version": "ingestion-plan-v1",
              "mode": "database_polling",
              "mainTable": "sec_alert_event",
              "schemaTableId": %d,
              "cursorField": "create_time",
              "fieldMappings": {
                "id": "externalId",
                "create_time": "occurredAt",
                "event_name": "title",
                "user_account": "actor",
                "host_name": "assetRef",
                "message": "detail"
              },
              "dedupStrategy": {"type": "external_id", "fields": ["id"], "fallback": "composite"},
              "syncStrategy": {"type": "polling", "cursorField": "create_time", "shadowOnly": true, "enabled": false},
              "risks": [],
              "requiredFieldsMissing": []
            }
            """.formatted(tableId);
    }

    private String missingCursorMetadataPlanJson(Long tableId) {
        return """
            {
              "version": "ingestion-plan-v1",
              "mode": "database_polling",
              "mainTable": "sec_alert_event",
              "schemaTableId": %d,
              "cursorField": "missing_cursor",
              "fieldMappings": {
                "id": "externalId",
                "create_time": "occurredAt",
                "event_name": "title"
              },
              "dedupStrategy": {"type": "external_id", "fields": ["id"], "fallback": "composite"},
              "syncStrategy": {"type": "polling", "cursorField": "missing_cursor", "shadowOnly": true, "enabled": false},
              "risks": [],
              "requiredFieldsMissing": []
            }
            """.formatted(tableId);
    }

    private String blockedPlanJson(Long tableId) {
        return """
            {
              "version": "ingestion-plan-v1",
              "mode": "database_polling",
              "mainTable": "sec_alert_event",
              "schemaTableId": %d,
              "cursorField": null,
              "fieldMappings": {"id": "externalId"},
              "dedupStrategy": {"type": "external_id", "fields": ["id"], "fallback": "composite"},
              "syncStrategy": {"type": "polling", "shadowOnly": true, "enabled": false},
              "risks": [],
              "requiredFieldsMissing": ["occurred_at"]
            }
            """.formatted(tableId);
    }

    private String planStatus(Long planId) {
        return jdbcTemplate.queryForObject(
            "select status from ingestion_plans where id = ?",
            String.class,
            planId
        );
    }

    private Map<String, Object> objectValue(Object value) {
        return objectMapper.convertValue(value, new TypeReference<>() {});
    }

    private List<Map<String, Object>> objectList(Object value) {
        return objectMapper.convertValue(value, new TypeReference<>() {});
    }

    private List<String> stringList(Object value) {
        return objectMapper.convertValue(value, new TypeReference<>() {});
    }

    private Map<String, Object> assertPlanFingerprint(Map<String, Object> report) {
        var fingerprint = objectValue(report.get("planFingerprint"));
        assertEquals("sha256-canonical-json-v1", fingerprint.get("algorithm"));
        assertTrue(String.valueOf(fingerprint.get("hash")).matches("[0-9a-f]{64}"));
        assertFalse(fingerprint.containsKey("planJson"));
        assertFalse(fingerprint.containsKey("plan_json"));
        assertFalse(String.valueOf(fingerprint).contains("fieldMappings"));
        return fingerprint;
    }

    private Long count(String tableName) {
        return jdbcTemplate.queryForObject("select count(*) from " + tableName, Long.class);
    }

    private Long lastId(String tableName) {
        return jdbcTemplate.queryForObject("select max(id) from " + tableName, Long.class);
    }

    private Map<String, Object> readJson(Object value) {
        if (value == null) {
            return Map.of();
        }
        try {
            var node = value instanceof byte[] bytes
                ? objectMapper.readTree(new String(bytes, StandardCharsets.UTF_8))
                : objectMapper.readTree(String.valueOf(value));
            if (node.isTextual()) {
                node = objectMapper.readTree(node.asText());
            }
            return objectMapper.convertValue(node, new TypeReference<>() {});
        } catch (JsonProcessingException ex) {
            return Map.of();
        }
    }

    private TransformResponse response(
        String dedupKey,
        String externalId,
        String severity,
        String occurredAt,
        Map<String, Object> mapped
    ) {
        var normalized = new LinkedHashMap<String, Object>();
        normalized.put("mapped", mapped.isEmpty()
            ? Map.of(
                "externalId", externalId,
                "occurredAt", occurredAt,
                "title", "runtime title",
                "actor", "runtime actor",
                "assetRef", "runtime asset",
                "severity", severity
            )
            : mapped);
        return new TransformResponse(new TransformDraftDto(
            "ds:1:st:1",
            externalId,
            "ingestion_plan_event",
            occurredAt,
            "runtime actor",
            "runtime asset",
            "event",
            "runtime asset",
            null,
            "detected",
            severity,
            80,
            dedupKey,
            normalized,
            Map.of("syncMode", "shadow_run")
        ), List.of(), List.of());
    }

    private static final class RecordingTransformRuntimeClient implements TransformRuntimeClient {
        private final TransformRuntimeClient delegate;
        private int calls;
        private BatchTransformRequest lastRequest;
        private RuntimeException failure;
        private Function<BatchTransformRequest, TransformBatchResult> handler;

        private RecordingTransformRuntimeClient(TransformRuntimeClient delegate) {
            this.delegate = delegate;
        }

        @Override
        public String mode() {
            return delegate.mode();
        }

        @Override
        public TransformBatchResult transform(BatchTransformRequest request) {
            calls++;
            lastRequest = request;
            if (failure != null) {
                throw failure;
            }
            if (handler != null) {
                return handler.apply(request);
            }
            return delegate.transform(request);
        }
    }
}
