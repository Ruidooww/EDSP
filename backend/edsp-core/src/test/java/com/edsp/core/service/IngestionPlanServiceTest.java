package com.edsp.core.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertIterableEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.edsp.core.dto.IngestionPlanGenerateRequest;
import com.edsp.core.dto.IngestionPlanShadowValidationRequest;
import com.edsp.core.dto.IngestionPlanStatusRequest;
import com.edsp.core.support.CoreRequestSupport;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.flywaydb.core.Flyway;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.server.ResponseStatusException;

class IngestionPlanServiceTest {
    private JdbcTemplate jdbcTemplate;
    private ObjectMapper objectMapper;
    private IngestionPlanService service;

    @BeforeEach
    void setUp() {
        var dataSource = new JdbcDataSource();
        dataSource.setURL(
            "jdbc:h2:mem:ingestion_plan_test;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;"
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
        objectMapper = new ObjectMapper();
        var support = new CoreRequestSupport(objectMapper);
        var profiler = new SemanticProfilerService(jdbcTemplate, support);
        var matcher = new TemplateMatcherService();
        service = new IngestionPlanService(jdbcTemplate, objectMapper, support, profiler, matcher);
    }

    @Test
    void generateCreatesSuggestedPlanForAlertTableWithoutChangingManualMappingMetadata() throws Exception {
        var dataSourceId = insertDataSource();
        var scanRunId = insertCompleteScan(dataSourceId);
        var alertTableId = insertTable(dataSourceId, scanRunId, "SEC_ALERT_EVENT", "alert_table");
        insertField(alertTableId, scanRunId, "ID", "varchar", "ALERT-1", 1, null, 72);
        insertField(alertTableId, scanRunId, "CREATE_TIME", "timestamp", "2026-05-20 10:30:00", 2, null, 80);
        insertField(alertTableId, scanRunId, "USER_ACCOUNT", "varchar", "zhangsan", 3, "manual_actor", 95);
        insertField(alertTableId, scanRunId, "HOST_NAME", "varchar", "WIN-01", 4, null, 70);
        insertField(alertTableId, scanRunId, "SEVERITY", "varchar", "high", 5, null, 65);
        insertMapping(alertTableId, "USER_ACCOUNT", "actor", "trim(USER_ACCOUNT)");

        var generated = service.generate(new IngestionPlanGenerateRequest(dataSourceId, scanRunId));

        assertEquals(1, generated.size());
        assertEquals("database_polling", planJsonObject(generated.get(0)).get("mode"));
        assertEquals(1L, count("ingestion_plans"));
        assertEquals("suggested", jdbcTemplate.queryForObject("select status from ingestion_plans", String.class));
        var listed = service.list(dataSourceId, null);
        assertEquals(1, listed.size());
        assertEquals("database_polling", planJsonObject(listed.get(0)).get("mode"));

        var plan = onlyPlanJson();
        assertEquals("ingestion-plan-v1", plan.path("version").asText());
        assertEquals("database-intelligence-mvp", plan.path("generatedBy").asText());
        assertEquals("database_polling", plan.path("mode").asText());
        assertEquals("SEC_ALERT_EVENT", plan.path("mainTable").asText());
        assertEquals(alertTableId.longValue(), plan.path("schemaTableId").asLong());
        assertEquals("CREATE_TIME", plan.path("cursorField").asText());
        assertEquals("ID", plan.path("idField").asText());
        assertEquals("shadow_validate", plan.path("recommendedAction").asText());
        assertEquals("alert_table", plan.path("templateMatch").path("templateKey").asText());
        assertEquals("USER_ACCOUNT", plan.path("fieldEvidence").path("USER_ACCOUNT").path("sourceField").asText());
        assertEquals("actor", plan.path("fieldEvidence").path("USER_ACCOUNT").path("existingMapping").asText());
        assertEquals("existing_mapping", plan.path("fieldEvidence").path("USER_ACCOUNT").path("source").asText());
        assertEquals("trim(USER_ACCOUNT)", plan.path("fieldEvidence").path("USER_ACCOUNT").path("transformRule").asText());
        assertEquals("trim(USER_ACCOUNT)", fieldMappingBySource(plan, "USER_ACCOUNT").path("transformRule").asText());

        var dedupFields = plan.path("dedupStrategy").path("fields");
        assertEquals("external_id", plan.path("dedupStrategy").path("type").asText());
        assertIterableEquals(List.of("ID"), jsonTextList(dedupFields));
        assertFalse(plan.path("dedupStrategy").toString().contains("externalId"));
        assertFalse(plan.path("dedupStrategy").toString().contains("occurredAt"));

        assertEquals("manual_actor", jdbcTemplate.queryForObject("""
            select semantic_type from schema_fields
            where schema_table_id = ? and field_name = 'USER_ACCOUNT'
            """, String.class, alertTableId));
        assertEquals(95, jdbcTemplate.queryForObject("""
            select confidence from schema_fields
            where schema_table_id = ? and field_name = 'USER_ACCOUNT'
            """, Integer.class, alertTableId));
    }

    @Test
    void generateSkipsAuxiliaryTemplatesAsMainPlans() {
        var dataSourceId = insertDataSource();
        var scanRunId = insertCompleteScan(dataSourceId);
        var userTableId = insertTable(dataSourceId, scanRunId, "SYS_USERS", "user_table");
        insertField(userTableId, scanRunId, "USER_ID", "varchar", "U001", 1, null, 60);
        insertField(userTableId, scanRunId, "USER_NAME", "varchar", "zhangsan", 2, null, 60);
        var assetTableId = insertTable(dataSourceId, scanRunId, "CMDB_ASSETS", "asset_table");
        insertField(assetTableId, scanRunId, "HOST_NAME", "varchar", "WIN-01", 1, null, 60);
        insertField(assetTableId, scanRunId, "IP_ADDRESS", "varchar", "10.0.0.1", 2, null, 60);

        var generated = service.generate(new IngestionPlanGenerateRequest(dataSourceId, scanRunId));

        assertTrue(generated.isEmpty());
        assertEquals(0L, count("ingestion_plans"));
    }

    @Test
    void generateCanUseHeuristicAlertOrLogMatchWithoutCreatingAuxiliaryMainPlans() throws Exception {
        var dataSourceId = insertDataSource();
        var scanRunId = insertCompleteScan(dataSourceId);
        var logTableId = insertTable(dataSourceId, scanRunId, "EVENT_AUDIT_LOG", "unknown");
        insertField(logTableId, scanRunId, "EVENT_TIME", "timestamp", "2026-05-20 10:30:00", 1, null, 60);
        insertField(logTableId, scanRunId, "EVENT_NAME", "varchar", "login", 2, null, 60);
        insertField(logTableId, scanRunId, "USER_ACCOUNT", "varchar", "zhangsan", 3, null, 60);
        insertField(logTableId, scanRunId, "HOST_NAME", "varchar", "WIN-01", 4, null, 60);

        var generated = service.generate(new IngestionPlanGenerateRequest(dataSourceId, scanRunId));

        assertEquals(1, generated.size());
        var plan = onlyPlanJson();
        assertEquals("database_polling", plan.path("mode").asText());
        assertEquals("log_table", plan.path("templateMatch").path("templateKey").asText());
        assertTrue(plan.path("templateMatch").path("mainPlanCandidate").asBoolean());
    }

    @Test
    void generateDoesNotTreatLoginUserProfileAsLogPlan() {
        var dataSourceId = insertDataSource();
        var scanRunId = insertCompleteScan(dataSourceId);
        var userTableId = insertTable(dataSourceId, scanRunId, "SYS_USER_PROFILE", "unknown");
        insertField(userTableId, scanRunId, "USER_ID", "varchar", "U001", 1, null, 60);
        insertField(userTableId, scanRunId, "LOGIN_NAME", "varchar", "zhangsan", 2, null, 60);
        insertField(userTableId, scanRunId, "DISPLAY_NAME", "varchar", "张三", 3, null, 60);

        var generated = service.generate(new IngestionPlanGenerateRequest(dataSourceId, scanRunId));

        assertTrue(generated.isEmpty());
        assertEquals(0L, count("ingestion_plans"));
    }

    @Test
    void generateMarksLimitedScanAsInsufficientCoverage() throws Exception {
        var dataSourceId = insertDataSource();
        var scanRunId = insertLimitedScan(dataSourceId);
        var logTableId = insertTable(dataSourceId, scanRunId, "AUDIT_LOGS", "log_table");
        insertField(logTableId, scanRunId, "LOG_ID", "bigint", "1001", 1, null, 60);
        insertField(logTableId, scanRunId, "MESSAGE", "text", "login failed", 2, null, 60);

        service.generate(new IngestionPlanGenerateRequest(dataSourceId, scanRunId));

        assertEquals("review_required", jdbcTemplate.queryForObject("select status from ingestion_plans", String.class));
        var plan = onlyPlanJson();
        assertEquals("insufficient_coverage", plan.path("recommendedAction").asText());
        assertTrue(jsonTextList(plan.path("risks")).contains("limited_scan"));
    }

    @Test
    void generateUsesCompositeDedupWhenExternalIdIsMissing() throws Exception {
        var dataSourceId = insertDataSource();
        var scanRunId = insertCompleteScan(dataSourceId);
        var logTableId = insertTable(dataSourceId, scanRunId, "FILE_OPERATION_LOG", "log_table");
        insertField(logTableId, scanRunId, "OPERATION_TYPE", "varchar", "copy_to_usb", 1, null, 60);
        insertField(logTableId, scanRunId, "EVENT_TIME", "timestamp", "2026-05-20 10:30:00", 2, null, 60);
        insertField(logTableId, scanRunId, "USER_ACCOUNT", "varchar", "zhangsan", 3, null, 60);
        insertField(logTableId, scanRunId, "HOST_NAME", "varchar", "WIN-01", 4, null, 60);

        service.generate(new IngestionPlanGenerateRequest(dataSourceId, scanRunId));

        var plan = onlyPlanJson();
        assertEquals("database_polling", plan.path("mode").asText());
        assertEquals("composite", plan.path("dedupStrategy").path("type").asText());
        assertIterableEquals(
            List.of("OPERATION_TYPE", "EVENT_TIME", "USER_ACCOUNT", "HOST_NAME"),
            jsonTextList(plan.path("dedupStrategy").path("fields"))
        );
        assertTrue(plan.path("dedupStrategy").path("stable").asBoolean());
        assertFalse(jsonTextList(plan.path("requiredFieldsMissing")).contains("external_id"));
        assertFalse(jsonTextList(plan.path("requiredFieldsMissing")).contains("dedup_key_source_insufficient"));
    }

    @Test
    void generateDoesNotUseSubjectIdAsExternalIdDedupKey() throws Exception {
        var dataSourceId = insertDataSource();
        var scanRunId = insertCompleteScan(dataSourceId);
        var logTableId = insertTable(dataSourceId, scanRunId, "FILE_OPERATION_LOG", "log_table");
        insertField(logTableId, scanRunId, "USER_ID", "varchar", "U001", 1, null, 60);
        insertField(logTableId, scanRunId, "EVENT_TIME", "timestamp", "2026-05-20 10:30:00", 2, null, 60);
        insertField(logTableId, scanRunId, "OPERATION_TYPE", "varchar", "copy_to_usb", 3, null, 60);
        insertField(logTableId, scanRunId, "HOST_NAME", "varchar", "WIN-01", 4, null, 60);

        service.generate(new IngestionPlanGenerateRequest(dataSourceId, scanRunId));

        var plan = onlyPlanJson();
        assertEquals("actor", plan.path("fieldMappings").path("USER_ID").asText());
        assertEquals("composite", plan.path("dedupStrategy").path("type").asText());
        assertIterableEquals(
            List.of("OPERATION_TYPE", "EVENT_TIME", "USER_ID", "HOST_NAME"),
            jsonTextList(plan.path("dedupStrategy").path("fields"))
        );
    }

    @Test
    void generateUpdatesMutablePlanButDoesNotOverwriteApprovedPlan() throws Exception {
        var dataSourceId = insertDataSource();
        var scanRunId = insertCompleteScan(dataSourceId);
        var alertTableId = insertTable(dataSourceId, scanRunId, "SEC_ALERT_EVENT", "alert_table");
        insertField(alertTableId, scanRunId, "ID", "varchar", "ALERT-1", 1, null, 72);
        insertField(alertTableId, scanRunId, "CREATE_TIME", "timestamp", "2026-05-20 10:30:00", 2, null, 80);
        insertField(alertTableId, scanRunId, "SEVERITY", "varchar", "high", 3, null, 65);
        insertField(alertTableId, scanRunId, "EVENT_NAME", "varchar", "Sensitive file export", 4, null, 65);

        service.generate(new IngestionPlanGenerateRequest(dataSourceId, scanRunId));
        var firstPlanId = lastId("ingestion_plans");
        service.generate(new IngestionPlanGenerateRequest(dataSourceId, scanRunId));

        assertEquals(1L, count("ingestion_plans"));
        assertEquals(firstPlanId, lastId("ingestion_plans"));

        service.updateStatus(firstPlanId, new IngestionPlanStatusRequest("approved"));
        service.generate(new IngestionPlanGenerateRequest(dataSourceId, scanRunId));

        assertEquals(2L, count("ingestion_plans"));
        assertEquals(1L, jdbcTemplate.queryForObject(
            "select count(*) from ingestion_plans where status = 'approved'",
            Long.class
        ));
        assertEquals(1L, jdbcTemplate.queryForObject(
            "select count(*) from ingestion_plans where status = 'review_required'",
            Long.class
        ));
        var latestPlan = latestPlanJson();
        assertTrue(jsonTextList(latestPlan.path("risks")).contains("existing_approved_plan"));
    }

    @Test
    void generateDoesNotOverwriteRejectedPlan() throws Exception {
        var dataSourceId = insertDataSource();
        var scanRunId = insertCompleteScan(dataSourceId);
        var alertTableId = insertTable(dataSourceId, scanRunId, "SEC_ALERT_EVENT", "alert_table");
        insertField(alertTableId, scanRunId, "ID", "varchar", "ALERT-1", 1, null, 72);
        insertField(alertTableId, scanRunId, "CREATE_TIME", "timestamp", "2026-05-20 10:30:00", 2, null, 80);
        insertField(alertTableId, scanRunId, "SEVERITY", "varchar", "high", 3, null, 65);
        insertField(alertTableId, scanRunId, "EVENT_NAME", "varchar", "Sensitive file export", 4, null, 65);

        service.generate(new IngestionPlanGenerateRequest(dataSourceId, scanRunId));
        var rejectedPlanId = lastId("ingestion_plans");
        service.updateStatus(rejectedPlanId, new IngestionPlanStatusRequest("rejected"));
        var generated = service.generate(new IngestionPlanGenerateRequest(dataSourceId, scanRunId));

        assertEquals(1, generated.size());
        assertEquals("rejected", generated.get(0).get("status"));
        assertEquals(rejectedPlanId, ((Number) generated.get(0).get("id")).longValue());
        assertEquals(1L, count("ingestion_plans"));
        assertEquals("rejected", planStatus(rejectedPlanId));
        assertEquals(0L, jdbcTemplate.queryForObject(
            "select count(*) from ingestion_plans where status = 'suggested'",
            Long.class
        ));

        insertField(alertTableId, scanRunId, "HOST_NAME", "varchar", "WIN-01", 5, null, 65);
        service.updateStatus(rejectedPlanId, new IngestionPlanStatusRequest("suggested"));
        generated = service.generate(new IngestionPlanGenerateRequest(dataSourceId, scanRunId));

        assertEquals(1, generated.size());
        assertEquals(rejectedPlanId, ((Number) generated.get(0).get("id")).longValue());
        assertEquals(1L, count("ingestion_plans"));
        assertEquals("suggested", planStatus(rejectedPlanId));
        var updatedPlan = onlyPlanJson();
        assertEquals("assetRef", updatedPlan.path("fieldMappings").path("HOST_NAME").asText());
    }

    @Test
    void updateStatusAllowsOnlyWhitelistedForwardTransitions() {
        var dataSourceId = insertDataSource();
        var planId = insertPlan(dataSourceId, "suggested");

        service.updateStatus(planId, new IngestionPlanStatusRequest("approved"));
        assertEquals("approved", planStatus(planId));

        service.updateStatus(planId, new IngestionPlanStatusRequest("shadow_ready"));
        assertEquals("shadow_ready", planStatus(planId));

        var ex = assertThrows(
            ResponseStatusException.class,
            () -> service.updateStatus(planId, new IngestionPlanStatusRequest("approved"))
        );
        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatusCode());
        assertEquals("Invalid ingestion plan status transition: shadow_ready -> approved", ex.getReason());

        service.updateStatus(planId, new IngestionPlanStatusRequest("rejected"));
        assertEquals("rejected", planStatus(planId));

        service.updateStatus(planId, new IngestionPlanStatusRequest("suggested"));
        assertEquals("suggested", planStatus(planId));
    }

    @Test
    void shadowValidateRejectsPlanBeforeManualApproval() {
        var dataSourceId = insertDataSource();
        var scanRunId = insertCompleteScan(dataSourceId);
        var alertTableId = insertTable(dataSourceId, scanRunId, "SEC_ALERT_EVENT", "alert_table");
        insertField(alertTableId, scanRunId, "ID", "varchar", "ALERT-1", 1, null, 72);
        insertField(alertTableId, scanRunId, "CREATE_TIME", "timestamp", "2026-05-20 10:30:00", 2, null, 80);
        insertField(alertTableId, scanRunId, "SEVERITY", "varchar", "high", 3, null, 65);
        service.generate(new IngestionPlanGenerateRequest(dataSourceId, scanRunId));

        var ex = assertThrows(
            ResponseStatusException.class,
            () -> service.shadowValidate(lastId("ingestion_plans"), new IngestionPlanShadowValidationRequest(20))
        );

        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatusCode());
        assertEquals("Ingestion plan must be approved or shadow_ready before shadow validation: suggested", ex.getReason());
    }

    @Test
    void shadowValidateReturnsDryRunReportForApprovedPlanWithoutWritingEvents() {
        var dataSourceId = insertDataSource();
        var scanRunId = insertCompleteScan(dataSourceId);
        var alertTableId = insertTable(dataSourceId, scanRunId, "SEC_ALERT_EVENT", "alert_table");
        insertField(alertTableId, scanRunId, "ID", "varchar", "ALERT-1", 1, null, 72);
        insertField(alertTableId, scanRunId, "CREATE_TIME", "timestamp", "2026-05-20 10:30:00", 2, null, 80);
        insertField(alertTableId, scanRunId, "EVENT_NAME", "varchar", "Sensitive file export", 3, null, 65);
        insertField(alertTableId, scanRunId, "USER_ACCOUNT", "varchar", "zhangsan", 4, null, 65);
        service.generate(new IngestionPlanGenerateRequest(dataSourceId, scanRunId));
        var planId = lastId("ingestion_plans");
        service.updateStatus(planId, new IngestionPlanStatusRequest("approved"));

        var report = service.shadowValidate(planId, new IngestionPlanShadowValidationRequest(20));

        assertEquals(planId, ((Number) report.get("planId")).longValue());
        assertEquals("passed", report.get("result"));
        assertEquals("shadow_ready", report.get("statusRecommendation"));
        assertEquals(20, ((Number) report.get("sampleLimit")).intValue());
        assertEquals("approved", planStatus(planId));
        assertEquals(0L, count("raw_events"));
        assertEquals(0L, count("standard_events"));

        var preview = objectValue(report.get("standardEventPreview"));
        assertEquals("ALERT-1", preview.get("externalId"));
        assertEquals("2026-05-20 10:30:00", preview.get("occurredAt"));
        assertEquals("Sensitive file export", preview.get("title"));

        var checks = objectList(report.get("checks"));
        assertTrue(hasCheck(checks, "plan_status", "passed"));
        assertTrue(hasCheck(checks, "source_fields", "passed"));
        assertTrue(hasCheck(checks, "sync_guard", "passed"));
    }

    @Test
    void shadowValidateBlocksApprovedPlanWhenRequiredFieldsAreMissing() {
        var dataSourceId = insertDataSource();
        var scanRunId = insertCompleteScan(dataSourceId);
        var logTableId = insertTable(dataSourceId, scanRunId, "AUDIT_LOGS", "log_table");
        insertField(logTableId, scanRunId, "LOG_ID", "varchar", "LOG-1", 1, null, 60);
        insertField(logTableId, scanRunId, "MESSAGE", "text", "login failed", 2, null, 60);
        service.generate(new IngestionPlanGenerateRequest(dataSourceId, scanRunId));
        var planId = lastId("ingestion_plans");
        service.updateStatus(planId, new IngestionPlanStatusRequest("approved"));

        var report = service.shadowValidate(planId, new IngestionPlanShadowValidationRequest(5000));

        assertEquals("blocked", report.get("result"));
        assertEquals("manual_review", report.get("statusRecommendation"));
        assertEquals(100, ((Number) report.get("sampleLimit")).intValue());
        assertTrue(objectList(report.get("checks")).stream().anyMatch(check ->
            "required_fields".equals(check.get("code"))
                && "failed".equals(check.get("result"))
                && List.of("missing_occurred_at").equals(check.get("blockers"))
        ));
        assertEquals(0L, count("raw_events"));
        assertEquals(0L, count("standard_events"));
    }

    @Test
    void listFiltersByDataSourceAndStatus() {
        var firstDataSourceId = insertDataSource("First Alert DB");
        var secondDataSourceId = insertDataSource("Second Alert DB");
        insertPlan(firstDataSourceId, "suggested");
        insertPlan(firstDataSourceId, "approved");
        insertPlan(secondDataSourceId, "suggested");

        var suggested = service.list(firstDataSourceId, "suggested");

        assertEquals(1, suggested.size());
        assertEquals(firstDataSourceId, ((Number) suggested.get(0).get("data_source_id")).longValue());
        assertEquals("suggested", suggested.get(0).get("status"));
    }

    private Long insertDataSource() {
        return insertDataSource("DLP Alert DB");
    }

    private Long insertDataSource(String name) {
        jdbcTemplate.update("""
            insert into data_sources(name, source_type, connection_kind, config_json, status, enabled)
            values (?, 'security_platform', 'database', cast('{}' as jsonb), 'active', true)
            """, name);
        return lastId("data_sources");
    }

    private Long insertCompleteScan(Long dataSourceId) {
        jdbcTemplate.update("""
            insert into schema_scan_runs(
                data_source_id, status, total_tables, scanned_tables, failed_tables, total_fields, scanned_fields
            )
            values (?, 'success', 1, 1, 0, 6, 6)
            """, dataSourceId);
        return lastId("schema_scan_runs");
    }

    private Long insertLimitedScan(Long dataSourceId) {
        jdbcTemplate.update("""
            insert into schema_scan_runs(
                data_source_id, status, total_tables, scanned_tables, failed_tables, total_fields, scanned_fields
            )
            values (?, 'success', 4, 2, 1, 20, 9)
            """, dataSourceId);
        return lastId("schema_scan_runs");
    }

    private Long insertTable(Long dataSourceId, Long scanRunId, String tableName, String category) {
        jdbcTemplate.update("""
            insert into schema_tables(
                data_source_id, scan_run_id, table_name, category, confirmation_status, lifecycle_status
            )
            values (?, ?, ?, ?, 'confirmed', 'active')
            """, dataSourceId, scanRunId, tableName, category);
        return lastId("schema_tables");
    }

    private void insertField(
        Long schemaTableId,
        Long scanRunId,
        String fieldName,
        String fieldType,
        String sampleValue,
        int ordinalPosition,
        String semanticType,
        int confidence
    ) {
        jdbcTemplate.update("""
            insert into schema_fields(
                schema_table_id, scan_run_id, field_name, field_type, sample_value,
                ordinal_position, semantic_type, confidence, lifecycle_status
            )
            values (?, ?, ?, ?, ?, ?, ?, ?, 'active')
            """, schemaTableId, scanRunId, fieldName, fieldType, sampleValue,
            ordinalPosition, semanticType, confidence);
    }

    private void insertMapping(Long schemaTableId, String sourceField, String standardField) {
        insertMapping(schemaTableId, sourceField, standardField, null);
    }

    private void insertMapping(Long schemaTableId, String sourceField, String standardField, String transformRule) {
        jdbcTemplate.update("""
            insert into field_mappings(schema_table_id, source_field, standard_field, transform_rule)
            values (?, ?, ?, ?)
            """, schemaTableId, sourceField, standardField, transformRule);
    }

    private Long insertPlan(Long dataSourceId, String status) {
        jdbcTemplate.update("""
            insert into ingestion_plans(data_source_id, name, status, plan_json)
            values (?, 'Generated plan', ?, cast('{}' as jsonb))
            """, dataSourceId, status);
        return lastId("ingestion_plans");
    }

    private String planStatus(Long planId) {
        return jdbcTemplate.queryForObject(
            "select status from ingestion_plans where id = ?",
            String.class,
            planId
        );
    }

    private JsonNode onlyPlanJson() throws Exception {
        Object value = jdbcTemplate.queryForObject("select plan_json from ingestion_plans", Object.class);
        return readJson(value);
    }

    private JsonNode latestPlanJson() throws Exception {
        Object value = jdbcTemplate.queryForObject("""
            select plan_json
            from ingestion_plans
            order by id desc
            limit 1
            """, Object.class);
        return readJson(value);
    }

    private JsonNode readJson(Object value) throws Exception {
        JsonNode node;
        if (value instanceof byte[] bytes) {
            node = objectMapper.readTree(new String(bytes, StandardCharsets.UTF_8));
        } else {
            node = objectMapper.readTree(String.valueOf(value));
        }
        if (node.isTextual()) {
            return objectMapper.readTree(node.asText());
        }
        return node;
    }

    private List<String> jsonTextList(JsonNode node) {
        return objectMapper.convertValue(node, objectMapper.getTypeFactory().constructCollectionType(List.class, String.class));
    }

    private JsonNode fieldMappingBySource(JsonNode plan, String sourceField) {
        for (var item : plan.path("fieldMappingDetails")) {
            if (sourceField.equals(item.path("sourceField").asText())) {
                return item;
            }
        }
        throw new AssertionError("Field mapping not found: " + sourceField);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> planJsonObject(Map<String, Object> row) {
        assertTrue(row.get("plan_json") instanceof Map<?, ?>, "plan_json should be returned as a structured object");
        return (Map<String, Object>) row.get("plan_json");
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> objectValue(Object value) {
        assertTrue(value instanceof Map<?, ?>, "value should be an object");
        return (Map<String, Object>) value;
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> objectList(Object value) {
        assertTrue(value instanceof List<?>, "value should be a list");
        return ((List<?>) value).stream()
            .map(item -> (Map<String, Object>) item)
            .toList();
    }

    private boolean hasCheck(List<Map<String, Object>> checks, String code, String result) {
        return checks.stream().anyMatch(check -> code.equals(check.get("code")) && result.equals(check.get("result")));
    }

    private Long count(String tableName) {
        return jdbcTemplate.queryForObject("select count(*) from " + tableName, Long.class);
    }

    private Long lastId(String tableName) {
        return jdbcTemplate.queryForObject("select max(id) from " + tableName, Long.class);
    }
}
