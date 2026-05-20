package com.edsp.core.controller;

import com.edsp.common.api.ApiResponse;
import com.edsp.core.dto.SchemaScanFinishRequest;
import com.edsp.core.dto.SchemaScanRunRequest;
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
@RequestMapping("/api/core/schema-scans")
public class SchemaScanController {
    private final JdbcTemplate jdbcTemplate;

    public SchemaScanController(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @GetMapping("/runs")
    public ApiResponse<List<Map<String, Object>>> runs(
        @RequestParam(name = "limit", defaultValue = "100") int limit
    ) {
        return ApiResponse.ok(jdbcTemplate.queryForList("""
            select ssr.id, ssr.data_source_id, ds.name as data_source_name,
                   ssr.scan_type, ssr.status, ssr.started_at, ssr.finished_at,
                   ssr.total_databases, ssr.scanned_databases, ssr.failed_databases,
                   ssr.total_tables, ssr.scanned_tables, ssr.failed_tables,
                   ssr.total_fields, ssr.scanned_fields, ssr.error_message
            from schema_scan_runs ssr
            join data_sources ds on ds.id = ssr.data_source_id
            order by ssr.started_at desc
            limit ?
            """, limit));
    }

    @PostMapping("/runs")
    public ApiResponse<Map<String, Object>> createRun(@RequestBody SchemaScanRunRequest request) {
        var id = jdbcTemplate.queryForObject("""
            insert into schema_scan_runs(data_source_id, scan_type, status, result_json)
            values (?, ?, ?, cast(? as jsonb))
            returning id
            """, Long.class, request.dataSourceId(), request.scanType(), request.status(), request.resultJson());
        return ApiResponse.ok(Map.of("id", id), "created");
    }

    @PutMapping("/runs/{id}/finish")
    public ApiResponse<Map<String, Object>> finishRun(
        @PathVariable long id,
        @RequestBody SchemaScanFinishRequest request
    ) {
        jdbcTemplate.update("""
            update schema_scan_runs
            set status = ?, finished_at = now(),
                total_databases = ?, scanned_databases = ?, failed_databases = ?,
                total_tables = ?, scanned_tables = ?, failed_tables = ?,
                total_fields = ?, scanned_fields = ?,
                error_message = ?, result_json = cast(? as jsonb)
            where id = ?
            """, request.status(), request.totalDatabases(), request.scannedDatabases(),
            request.failedDatabases(), request.totalTables(), request.scannedTables(),
            request.failedTables(), request.totalFields(), request.scannedFields(),
            request.errorMessage(), request.resultJson(), id);
        return ApiResponse.ok(Map.of("id", id), "finished");
    }

    @GetMapping("/runs/{id}/tables")
    public ApiResponse<List<Map<String, Object>>> tables(@PathVariable long id) {
        return ApiResponse.ok(jdbcTemplate.queryForList("""
            select st.id, st.data_source_id, ds.name as data_source_name,
                   st.schema_name, st.table_name, st.table_type, st.category,
                   st.row_count, st.confirmation_status, st.source_updated_at,
                   st.created_at, st.updated_at
            from schema_tables st
            join data_sources ds on ds.id = st.data_source_id
            where st.scan_run_id = ?
            order by st.schema_name, st.table_name
            """, id));
    }
}
