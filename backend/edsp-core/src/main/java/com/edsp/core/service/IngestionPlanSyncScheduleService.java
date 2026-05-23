package com.edsp.core.service;

import com.edsp.core.dto.IngestionPlanSyncScheduleRequest;
import com.edsp.core.support.CoreRequestSupport;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Statement;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class IngestionPlanSyncScheduleService {
    private static final int DEFAULT_INTERVAL_SECONDS = 300;
    private static final int MIN_INTERVAL_SECONDS = 60;
    private static final int MAX_INTERVAL_SECONDS = 86400;
    private static final int DEFAULT_SAMPLE_LIMIT = 50;
    private static final int MAX_SAMPLE_LIMIT = 100;
    private static final List<String> PLAN_SYNC_STATUSES = List.of("approved", "shadow_ready");

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final CoreRequestSupport support;
    private final IngestionPlanSyncOnceService syncOnceService;

    public IngestionPlanSyncScheduleService(
        JdbcTemplate jdbcTemplate,
        ObjectMapper objectMapper,
        CoreRequestSupport support,
        IngestionPlanSyncOnceService syncOnceService
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
        this.support = support;
        this.syncOnceService = syncOnceService;
    }

    @Transactional
    public Map<String, Object> createSchedule(long activationId, IngestionPlanSyncScheduleRequest request) {
        var activation = loadActivation(activationId);
        requireActiveActivation(activation);
        ensureNoSchedule(activationId);

        var planId = support.number(activation.get("ingestion_plan_id"));
        var dataSourceId = support.number(activation.get("data_source_id"));
        var shadowRunId = support.number(activation.get("shadow_run_id"));
        var plan = loadPlan(planId);
        validatePlanAndShadowRun(planId, dataSourceId, shadowRunId, support.stringOrDefault(plan.get("status"), ""));

        var intervalSeconds = intervalSeconds(request);
        var sampleLimit = sampleLimit(request);
        var now = OffsetDateTime.now(ZoneOffset.UTC);
        var config = new LinkedHashMap<String, Object>();
        config.put("mode", "scheduled_sync");
        config.put("boundary", "writes raw_events and standard_events only; no alerts or notifications");
        var scheduleId = insertSchedule(
            planId,
            activationId,
            dataSourceId,
            intervalSeconds,
            sampleLimit,
            now.plusSeconds(intervalSeconds),
            request == null ? null : support.stringOrNull(request.operatorName()),
            config
        );
        return scheduleRow(scheduleId);
    }

    public List<Map<String, Object>> listByPlan(long planId, int limit) {
        ensurePlanExists(planId);
        return jdbcTemplate.queryForList("""
            select id, ingestion_plan_id, activation_id, data_source_id, status, interval_seconds,
                   sample_limit, next_run_at, last_run_at, last_sync_run_id, last_status,
                   last_error_message, consecutive_failures, locked_at, lock_owner,
                   created_by, updated_by, config_json, created_at, updated_at
            from ingestion_plan_sync_schedules
            where ingestion_plan_id = ?
            order by created_at desc, id desc
            limit ?
            """, planId, support.safeLimit(limit, 50)).stream()
            .map(this::scheduleRow)
            .toList();
    }

    @Transactional
    public Map<String, Object> update(long scheduleId, IngestionPlanSyncScheduleRequest request) {
        var current = loadSchedule(scheduleId);
        requireScheduleActivationActive(current);
        var intervalSeconds = intervalSeconds(request, intValue(current.get("interval_seconds")));
        var sampleLimit = sampleLimit(request, intValue(current.get("sample_limit")));
        jdbcTemplate.update("""
            update ingestion_plan_sync_schedules
            set interval_seconds = ?,
                sample_limit = ?,
                updated_by = ?,
                updated_at = now()
            where id = ?
            """, intervalSeconds, sampleLimit, request == null ? null : support.stringOrNull(request.operatorName()),
            scheduleId);
        return scheduleRow(scheduleId);
    }

    @Transactional
    public Map<String, Object> pause(long scheduleId, IngestionPlanSyncScheduleRequest request) {
        var current = loadSchedule(scheduleId);
        requireScheduleActivationActive(current);
        jdbcTemplate.update("""
            update ingestion_plan_sync_schedules
            set status = 'paused',
                updated_by = ?,
                updated_at = now()
            where id = ?
            """, request == null ? null : support.stringOrNull(request.operatorName()), scheduleId);
        return scheduleRow(scheduleId);
    }

    @Transactional
    public Map<String, Object> resume(long scheduleId, IngestionPlanSyncScheduleRequest request) {
        var current = loadSchedule(scheduleId);
        requireScheduleActivationActive(current);
        jdbcTemplate.update("""
            update ingestion_plan_sync_schedules
            set status = 'enabled',
                next_run_at = ?,
                updated_by = ?,
                updated_at = now()
            where id = ?
            """, OffsetDateTime.now(ZoneOffset.UTC),
            request == null ? null : support.stringOrNull(request.operatorName()), scheduleId);
        return scheduleRow(scheduleId);
    }

    @Transactional
    public List<Map<String, Object>> runDueSchedules(String lockOwner, int limit) {
        var due = jdbcTemplate.queryForList("""
            select s.id, s.ingestion_plan_id, s.activation_id, s.interval_seconds, s.sample_limit
            from ingestion_plan_sync_schedules s
            join ingestion_plan_activations a on a.id = s.activation_id
            where s.status = 'enabled'
              and s.locked_at is null
              and s.next_run_at <= now()
              and a.status = 'active'
            order by s.next_run_at asc, s.id asc
            limit ?
            """, support.safeLimit(limit, 50));
        var results = new ArrayList<Map<String, Object>>();
        for (var schedule : due) {
            var scheduleId = support.number(schedule.get("id"));
            if (scheduleId == null || !claimSchedule(scheduleId, lockOwner)) {
                continue;
            }
            var activationId = support.number(schedule.get("activation_id"));
            var intervalSeconds = intValue(schedule.get("interval_seconds"));
            var sampleLimit = intValue(schedule.get("sample_limit"));
            try {
                var run = syncOnceService.syncScheduled(activationId, scheduleId, sampleLimit);
                updateAfterRun(scheduleId, intervalSeconds, run, null);
                results.add(run);
            } catch (ResponseStatusException ex) {
                updateAfterFailure(scheduleId, intervalSeconds, "failed", ex.getReason());
            } catch (RuntimeException ex) {
                updateAfterFailure(scheduleId, intervalSeconds, "failed", ex.getMessage());
            }
        }
        return results;
    }

    private boolean claimSchedule(Long scheduleId, String lockOwner) {
        return jdbcTemplate.update("""
            update ingestion_plan_sync_schedules
            set locked_at = now(),
                lock_owner = ?,
                updated_at = now()
            where id = ?
              and status = 'enabled'
              and locked_at is null
              and next_run_at <= now()
              and exists (
                  select 1
                  from ingestion_plan_activations a
                  where a.id = ingestion_plan_sync_schedules.activation_id
                    and a.status = 'active'
              )
            """, support.stringOrDefault(lockOwner, "scheduled-sync"), scheduleId) == 1;
    }

    private void updateAfterRun(Long scheduleId, int intervalSeconds, Map<String, Object> run, String errorMessage) {
        var status = support.stringOrDefault(run.get("status"), "failed");
        var nextRunAt = OffsetDateTime.now(ZoneOffset.UTC).plusSeconds(intervalSeconds);
        var failureCountSql = switch (status) {
            case "passed", "warning" -> "0";
            default -> "consecutive_failures + 1";
        };
        jdbcTemplate.update("""
            update ingestion_plan_sync_schedules
            set last_run_at = now(),
                next_run_at = ?,
                last_sync_run_id = ?,
                last_status = ?,
                last_error_message = ?,
                consecutive_failures = %s,
                locked_at = null,
                lock_owner = null,
                updated_at = now()
            where id = ?
            """.formatted(failureCountSql), nextRunAt, run.get("id"), status, errorMessage, scheduleId);
    }

    private void updateAfterFailure(Long scheduleId, int intervalSeconds, String status, String message) {
        var nextRunAt = OffsetDateTime.now(ZoneOffset.UTC).plusSeconds(intervalSeconds);
        jdbcTemplate.update("""
            update ingestion_plan_sync_schedules
            set last_run_at = now(),
                next_run_at = ?,
                last_status = ?,
                last_error_message = ?,
                consecutive_failures = consecutive_failures + 1,
                locked_at = null,
                lock_owner = null,
                updated_at = now()
            where id = ?
            """, nextRunAt, status, message, scheduleId);
    }

    private Long insertSchedule(
        Long planId,
        long activationId,
        Long dataSourceId,
        int intervalSeconds,
        int sampleLimit,
        OffsetDateTime nextRunAt,
        String operatorName,
        Map<String, Object> config
    ) {
        var keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            var statement = connection.prepareStatement("""
                insert into ingestion_plan_sync_schedules(
                    ingestion_plan_id, activation_id, data_source_id, status, interval_seconds,
                    sample_limit, next_run_at, created_by, updated_by, config_json
                )
                values (?, ?, ?, 'enabled', ?, ?, ?, ?, ?, cast(? as jsonb))
                """, Statement.RETURN_GENERATED_KEYS);
            statement.setObject(1, planId);
            statement.setLong(2, activationId);
            statement.setObject(3, dataSourceId);
            statement.setInt(4, intervalSeconds);
            statement.setInt(5, sampleLimit);
            statement.setObject(6, nextRunAt);
            statement.setString(7, operatorName);
            statement.setString(8, operatorName);
            statement.setString(9, toJson(config));
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

    private void requireActiveActivation(Map<String, Object> activation) {
        var status = support.stringOrDefault(activation.get("status"), "");
        if (!"active".equals(status)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Only active activation can create sync schedule");
        }
    }

    private void requireScheduleActivationActive(Map<String, Object> schedule) {
        var activationId = support.number(schedule.get("activation_id"));
        if (activationId == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Sync schedule has no activation");
        }
        var activation = loadActivation(activationId);
        var status = support.stringOrDefault(activation.get("status"), "");
        if (!"active".equals(status)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Only active activation can update or resume sync schedule");
        }
    }

    private void ensureNoSchedule(long activationId) {
        var count = jdbcTemplate.queryForObject(
            "select count(*) from ingestion_plan_sync_schedules where activation_id = ?",
            Long.class,
            activationId
        );
        if (count != null && count > 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Activation already has a sync schedule: " + activationId);
        }
    }

    private void validatePlanAndShadowRun(Long planId, Long dataSourceId, Long shadowRunId, String planStatus) {
        if (!PLAN_SYNC_STATUSES.contains(planStatus)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Ingestion plan status is not schedulable: " + planStatus);
        }
        var latest = jdbcTemplate.queryForList("""
            select id, data_source_id, status
            from ingestion_plan_shadow_runs
            where ingestion_plan_id = ?
            order by created_at desc, id desc
            limit 1
            """, planId);
        if (latest.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Ingestion plan has no Shadow Run: " + planId);
        }
        var latestRun = latest.get(0);
        var latestRunId = support.number(latestRun.get("id"));
        if (latestRunId == null || shadowRunId == null || latestRunId.longValue() != shadowRunId) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Active activation must reference the latest Shadow Run");
        }
        if (!"passed".equals(support.stringOrDefault(latestRun.get("status"), ""))) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Latest Shadow Run must be passed before scheduling");
        }
        var latestDataSourceId = support.number(latestRun.get("data_source_id"));
        if (dataSourceId == null || latestDataSourceId == null || !dataSourceId.equals(latestDataSourceId)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Latest Shadow Run data source does not match active activation");
        }
    }

    private Map<String, Object> loadActivation(long activationId) {
        var rows = jdbcTemplate.queryForList("""
            select id, ingestion_plan_id, data_source_id, shadow_run_id, status
            from ingestion_plan_activations
            where id = ?
            """, activationId);
        if (rows.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Ingestion plan activation not found: " + activationId);
        }
        return rows.get(0);
    }

    private Map<String, Object> loadPlan(Long planId) {
        var rows = jdbcTemplate.queryForList("""
            select id, data_source_id, status
            from ingestion_plans
            where id = ?
            """, planId);
        if (rows.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Ingestion plan not found: " + planId);
        }
        return rows.get(0);
    }

    private Map<String, Object> loadSchedule(long scheduleId) {
        var rows = jdbcTemplate.queryForList("""
            select id, ingestion_plan_id, activation_id, data_source_id, status, interval_seconds,
                   sample_limit, next_run_at, last_run_at, last_sync_run_id, last_status,
                   last_error_message, consecutive_failures, locked_at, lock_owner,
                   created_by, updated_by, config_json, created_at, updated_at
            from ingestion_plan_sync_schedules
            where id = ?
            """, scheduleId);
        if (rows.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Ingestion plan sync schedule not found: " + scheduleId);
        }
        return rows.get(0);
    }

    private Map<String, Object> scheduleRow(Long scheduleId) {
        return scheduleRow(loadSchedule(scheduleId));
    }

    private Map<String, Object> scheduleRow(Map<String, Object> row) {
        var result = new LinkedHashMap<String, Object>();
        result.put("id", row.get("id"));
        result.put("ingestionPlanId", row.get("ingestion_plan_id"));
        result.put("activationId", row.get("activation_id"));
        result.put("dataSourceId", row.get("data_source_id"));
        result.put("status", row.get("status"));
        result.put("intervalSeconds", row.get("interval_seconds"));
        result.put("sampleLimit", row.get("sample_limit"));
        result.put("nextRunAt", row.get("next_run_at"));
        result.put("lastRunAt", row.get("last_run_at"));
        result.put("lastSyncRunId", row.get("last_sync_run_id"));
        result.put("lastStatus", row.get("last_status"));
        result.put("lastErrorMessage", row.get("last_error_message"));
        result.put("consecutiveFailures", row.get("consecutive_failures"));
        result.put("lockedAt", row.get("locked_at"));
        result.put("lockOwner", row.get("lock_owner"));
        result.put("createdBy", row.get("created_by"));
        result.put("updatedBy", row.get("updated_by"));
        result.put("config", parseJson(row.get("config_json")));
        result.put("createdAt", row.get("created_at"));
        result.put("updatedAt", row.get("updated_at"));
        return result;
    }

    private void ensurePlanExists(long planId) {
        var count = jdbcTemplate.queryForObject("select count(*) from ingestion_plans where id = ?", Long.class, planId);
        if (count == null || count == 0) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Ingestion plan not found: " + planId);
        }
    }

    private int intervalSeconds(IngestionPlanSyncScheduleRequest request) {
        return intervalSeconds(request, DEFAULT_INTERVAL_SECONDS);
    }

    private int intervalSeconds(IngestionPlanSyncScheduleRequest request, int fallback) {
        var requested = request == null || request.intervalSeconds() == null ? fallback : request.intervalSeconds();
        if (requested < MIN_INTERVAL_SECONDS || requested > MAX_INTERVAL_SECONDS) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "intervalSeconds must be between 60 and 86400");
        }
        return requested;
    }

    private int sampleLimit(IngestionPlanSyncScheduleRequest request) {
        return sampleLimit(request, DEFAULT_SAMPLE_LIMIT);
    }

    private int sampleLimit(IngestionPlanSyncScheduleRequest request, int fallback) {
        var requested = request == null || request.sampleLimit() == null ? fallback : request.sampleLimit();
        return support.safeLimit(requested, MAX_SAMPLE_LIMIT);
    }

    private int intValue(Object value) {
        return value instanceof Number number ? number.intValue() : 0;
    }

    private Map<String, Object> parseJson(Object value) {
        if (value == null) {
            return Map.of();
        }
        if (value instanceof Map<?, ?> map) {
            return objectMapper.convertValue(map, new TypeReference<>() {});
        }
        try {
            return objectMapper.convertValue(objectMapper.readTree(String.valueOf(value)), new TypeReference<>() {});
        } catch (JsonProcessingException ex) {
            return Map.of();
        }
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Unable to serialize sync schedule config", ex);
        }
    }
}
