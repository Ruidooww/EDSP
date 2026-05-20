package com.edsp.core.controller;

import com.edsp.common.api.ApiResponse;
import com.edsp.core.dto.DataSourceRequest;
import com.edsp.core.service.SqlServerMetadataService;
import jakarta.validation.Valid;
import java.sql.Statement;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/core/data-sources")
public class DataSourceController {
    private final JdbcTemplate jdbcTemplate;
    private final SqlServerMetadataService sqlServerMetadataService;

    public DataSourceController(JdbcTemplate jdbcTemplate, SqlServerMetadataService sqlServerMetadataService) {
        this.jdbcTemplate = jdbcTemplate;
        this.sqlServerMetadataService = sqlServerMetadataService;
    }

    @GetMapping
    public ApiResponse<List<Map<String, Object>>> list() {
        var rows = jdbcTemplate.queryForList("""
            select id, name, source_type, connection_kind, description, status, enabled, created_at, updated_at
            from data_sources
            order by updated_at desc
            """);
        return ApiResponse.ok(rows);
    }

    @PostMapping
    public ApiResponse<Map<String, Object>> create(@Valid @RequestBody DataSourceRequest request) {
        var keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            var statement = connection.prepareStatement("""
            insert into data_sources(name, source_type, connection_kind, description, config_json, status, enabled)
            values (?, ?, ?, ?, cast(? as jsonb), ?, ?)
            """, Statement.RETURN_GENERATED_KEYS);
            statement.setString(1, request.name());
            statement.setString(2, request.sourceType());
            statement.setString(3, request.connectionKind());
            statement.setString(4, request.description());
            statement.setString(5, request.configJson());
            statement.setString(6, initialStatus(request));
            statement.setBoolean(7, request.enabled());
            return statement;
        }, keyHolder);
        var idValue = keyHolder.getKeys() == null ? null : keyHolder.getKeys().get("id");
        var id = idValue instanceof Number number ? number.longValue() : 0;
        return ApiResponse.ok(Map.of("id", id), "created");
    }

    @PostMapping("/test")
    public ApiResponse<Map<String, Object>> testUnsaved(@Valid @RequestBody DataSourceRequest request) {
        if (!isSqlServer(request.sourceType())) {
            return ApiResponse.ok(configuredResult(request.sourceType(), request.connectionKind()));
        }
        return ApiResponse.ok(sqlServerMetadataService.test(request));
    }

    @PutMapping("/{id}")
    public ApiResponse<Map<String, Object>> update(@PathVariable("id") long id, @Valid @RequestBody DataSourceRequest request) {
        jdbcTemplate.update("""
            update data_sources
            set name = ?, source_type = ?, connection_kind = ?, description = ?,
                config_json = cast(? as jsonb), status = ?, enabled = ?, updated_at = now()
            where id = ?
            """, request.name(), request.sourceType(), request.connectionKind(), request.description(),
            request.configJson(), initialStatus(request), request.enabled(), id);
        return ApiResponse.ok(Map.of("id", id), "updated");
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Map<String, Object>> delete(@PathVariable("id") long id) {
        jdbcTemplate.update("delete from data_sources where id = ?", id);
        return ApiResponse.ok(Map.of("id", id), "deleted");
    }

    @PostMapping("/{id}/test")
    public ApiResponse<Map<String, Object>> testConnection(@PathVariable("id") long id) {
        var row = jdbcTemplate.queryForMap("""
            select id, source_type, connection_kind, config_json
            from data_sources
            where id = ?
            """, id);
        var sourceType = row.get("source_type").toString();
        var connectionKind = row.get("connection_kind").toString();
        var result = isSqlServer(sourceType)
            ? new LinkedHashMap<>(sqlServerMetadataService.test(sourceType, row.get("config_json")))
            : configuredResult(sourceType, connectionKind);
        var status = switch (String.valueOf(result.get("status"))) {
            case "active" -> "active";
            case "configured" -> "configured";
            default -> "error";
        };
        jdbcTemplate.update("update data_sources set status = ?, updated_at = now() where id = ?", status, id);
        result.put("id", id);
        return ApiResponse.ok(result);
    }

    @GetMapping("/{id}/tables")
    public ApiResponse<Map<String, Object>> tables(
        @PathVariable("id") long id,
        @RequestParam(name = "database", defaultValue = "") String database,
        @RequestParam(name = "keyword", defaultValue = "") String keyword,
        @RequestParam(name = "limit", defaultValue = "100") int limit
    ) {
        var row = sourceRow(id);
        return ApiResponse.ok(sqlServerMetadataService.tables(
            row.get("source_type").toString(), row.get("config_json"), database, keyword, limit));
    }

    @GetMapping("/{id}/columns")
    public ApiResponse<Map<String, Object>> columns(
        @PathVariable("id") long id,
        @RequestParam("database") String database,
        @RequestParam("schema") String schema,
        @RequestParam("table") String table
    ) {
        var row = sourceRow(id);
        return ApiResponse.ok(sqlServerMetadataService.columns(
            row.get("source_type").toString(), row.get("config_json"), database, schema, table));
    }

    @GetMapping("/{id}/sample")
    public ApiResponse<Map<String, Object>> sample(
        @PathVariable("id") long id,
        @RequestParam("database") String database,
        @RequestParam("schema") String schema,
        @RequestParam("table") String table,
        @RequestParam(name = "limit", defaultValue = "10") int limit
    ) {
        var row = sourceRow(id);
        return ApiResponse.ok(sqlServerMetadataService.sample(
            row.get("source_type").toString(), row.get("config_json"), database, schema, table, limit));
    }

    private Map<String, Object> sourceRow(long id) {
        return jdbcTemplate.queryForMap("""
            select id, source_type, config_json
            from data_sources
            where id = ?
            """, id);
    }

    private boolean isSqlServer(String sourceType) {
        return "sqlserver".equalsIgnoreCase(sourceType) || "mssql".equalsIgnoreCase(sourceType);
    }

    private String initialStatus(DataSourceRequest request) {
        return isSqlServer(request.sourceType()) ? "draft" : "configured";
    }

    private LinkedHashMap<String, Object> configuredResult(String sourceType, String connectionKind) {
        var result = new LinkedHashMap<String, Object>();
        result.put("status", "configured");
        result.put("message", "接入配置已保存，等待对应采集适配器启用后开始采集预警。");
        result.put("sourceType", sourceType);
        result.put("connectionKind", connectionKind);
        return result;
    }
}
