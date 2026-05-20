package com.edsp.core.controller;

import com.edsp.common.api.ApiResponse;
import com.edsp.core.dto.CollectionTaskRequest;
import com.edsp.core.dto.IngestionRunFinishRequest;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/core/collection-tasks")
public class CollectionTaskController {
    private final JdbcTemplate jdbcTemplate;

    public CollectionTaskController(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @GetMapping
    public ApiResponse<List<Map<String, Object>>> list(
        @RequestParam(name = "limit", defaultValue = "100") int limit
    ) {
        return ApiResponse.ok(jdbcTemplate.queryForList("""
            select ct.id, ct.name, ct.task_type, ct.schedule_mode, ct.interval_seconds,
                   ct.status, ct.enabled, ct.last_run_at, ct.next_run_at,
                   ds.name as data_source_name, ds.source_type,
                   ca.name as adapter_name, ct.created_at, ct.updated_at
            from collection_tasks ct
            join data_sources ds on ds.id = ct.data_source_id
            left join collector_adapters ca on ca.id = ct.adapter_id
            order by ct.updated_at desc
            limit ?
            """, limit));
    }

    @PostMapping
    public ApiResponse<Map<String, Object>> create(@Valid @RequestBody CollectionTaskRequest request) {
        var id = jdbcTemplate.queryForObject("""
            insert into collection_tasks(
                data_source_id, adapter_id, name, task_type, schedule_mode,
                interval_seconds, status, enabled, config_json
            )
            values (?, ?, ?, ?, ?, ?, ?, ?, cast(? as jsonb))
            returning id
            """, Long.class, request.dataSourceId(), request.adapterId(), request.name(),
            request.taskType(), request.scheduleMode(), request.intervalSeconds(), request.status(),
            request.enabled(), request.configJson());
        return ApiResponse.ok(Map.of("id", id), "created");
    }

    @PutMapping("/{id}")
    public ApiResponse<Map<String, Object>> update(
        @PathVariable long id,
        @Valid @RequestBody CollectionTaskRequest request
    ) {
        jdbcTemplate.update("""
            update collection_tasks
            set data_source_id = ?, adapter_id = ?, name = ?, task_type = ?,
                schedule_mode = ?, interval_seconds = ?, status = ?, enabled = ?,
                config_json = cast(? as jsonb), updated_at = now()
            where id = ?
            """, request.dataSourceId(), request.adapterId(), request.name(), request.taskType(),
            request.scheduleMode(), request.intervalSeconds(), request.status(), request.enabled(),
            request.configJson(), id);
        return ApiResponse.ok(Map.of("id", id), "updated");
    }

    @PostMapping("/{id}/runs")
    public ApiResponse<Map<String, Object>> startRun(
        @PathVariable long id,
        @RequestParam(name = "runType", defaultValue = "manual") String runType
    ) {
        var dataSourceId = jdbcTemplate.queryForObject(
            "select data_source_id from collection_tasks where id = ?",
            Long.class,
            id
        );
        var runId = jdbcTemplate.queryForObject("""
            insert into ingestion_runs(task_id, data_source_id, run_type, status)
            values (?, ?, ?, 'running')
            returning id
            """, Long.class, id, dataSourceId, runType);
        jdbcTemplate.update("""
            update collection_tasks
            set status = 'running', last_run_at = now(), updated_at = now()
            where id = ?
            """, id);
        return ApiResponse.ok(Map.of("id", runId, "taskId", id), "started");
    }

    @GetMapping("/runs")
    public ApiResponse<List<Map<String, Object>>> runs(
        @RequestParam(name = "limit", defaultValue = "100") int limit
    ) {
        return ApiResponse.ok(jdbcTemplate.queryForList("""
            select ir.id, ir.task_id, ct.name as task_name, ds.name as data_source_name,
                   ir.run_type, ir.status, ir.started_at, ir.finished_at,
                   ir.read_count, ir.success_count, ir.failed_count, ir.skipped_count,
                   ir.error_message
            from ingestion_runs ir
            left join collection_tasks ct on ct.id = ir.task_id
            left join data_sources ds on ds.id = ir.data_source_id
            order by ir.started_at desc
            limit ?
            """, limit));
    }

    @PutMapping("/runs/{runId}/finish")
    public ApiResponse<Map<String, Object>> finishRun(
        @PathVariable long runId,
        @RequestBody IngestionRunFinishRequest request
    ) {
        jdbcTemplate.update("""
            update ingestion_runs
            set status = ?, finished_at = now(), cursor_after = ?,
                read_count = ?, success_count = ?, failed_count = ?, skipped_count = ?,
                error_message = ?, quality_report_json = cast(? as jsonb)
            where id = ?
            """, request.status(), request.cursorAfter(), request.readCount(), request.successCount(),
            request.failedCount(), request.skippedCount(), request.errorMessage(),
            request.qualityReportJson(), runId);
        jdbcTemplate.update("""
            update collection_tasks
            set status = ?, updated_at = now()
            where id = (select task_id from ingestion_runs where id = ?)
            """, taskStatus(request.status()), runId);
        updateCursor(runId, request.cursorAfter());
        return ApiResponse.ok(Map.of("id", runId), "finished");
    }

    private void updateCursor(long runId, String cursorAfter) {
        if (cursorAfter == null || cursorAfter.isBlank()) {
            return;
        }
        var taskId = jdbcTemplate.queryForObject(
            "select task_id from ingestion_runs where id = ?",
            Long.class,
            runId
        );
        var updated = jdbcTemplate.update("""
            update ingestion_cursors
            set cursor_value = ?, updated_at = now()
            where task_id = ? and cursor_key = 'default'
            """, cursorAfter, taskId);
        if (updated == 0) {
            jdbcTemplate.update("""
                insert into ingestion_cursors(task_id, cursor_key, cursor_value)
                values (?, 'default', ?)
                """, taskId, cursorAfter);
        }
    }

    private String taskStatus(String runStatus) {
        return switch (runStatus) {
            case "success" -> "idle";
            case "failed" -> "failed";
            default -> runStatus;
        };
    }
}
