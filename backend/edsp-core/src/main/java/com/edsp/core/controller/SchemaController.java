package com.edsp.core.controller;

import com.edsp.common.api.ApiResponse;
import com.edsp.core.dto.FieldMappingRequest;
import com.edsp.core.dto.SchemaFieldRequest;
import com.edsp.core.dto.SchemaTableRequest;
import jakarta.validation.Valid;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/core/schema")
public class SchemaController {
    private final JdbcTemplate jdbcTemplate;

    public SchemaController(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @GetMapping("/tables")
    public ApiResponse<List<Map<String, Object>>> tables() {
        return ApiResponse.ok(jdbcTemplate.queryForList("""
            select st.id, ds.name as data_source_name, st.data_source_id, st.scan_run_id,
                   st.schema_name, st.table_name, st.table_type, st.category, st.row_count,
                   st.confirmation_status, st.lifecycle_status,
                   st.source_updated_at, st.last_seen_at, st.source_removed_at,
                   st.created_at, st.updated_at
            from schema_tables st
            join data_sources ds on ds.id = st.data_source_id
            order by st.updated_at desc
            """));
    }

    @PostMapping("/tables")
    public ApiResponse<Map<String, Object>> createTable(@Valid @RequestBody SchemaTableRequest request) {
        var id = insertAndReturnId("""
            insert into schema_tables(data_source_id, table_name, category, confirmation_status)
            values (?, ?, ?, ?)
            """, request.dataSourceId(), request.tableName(), request.category(), request.confirmationStatus());
        return ApiResponse.ok(Map.of("id", id), "created");
    }

    @GetMapping("/tables/{tableId}/fields")
    public ApiResponse<List<Map<String, Object>>> fields(@PathVariable("tableId") long tableId) {
        return ApiResponse.ok(jdbcTemplate.queryForList("""
            select id, scan_run_id, field_name, field_type, nullable, sample_value, description,
                   ordinal_position, semantic_type, confidence, is_candidate_key,
                   is_time_candidate, lifecycle_status, last_seen_at, source_removed_at
            from schema_fields
            where schema_table_id = ?
            order by ordinal_position nulls last, id
            """, tableId));
    }

    @PostMapping("/tables/{tableId}/fields")
    public ApiResponse<Map<String, Object>> createField(
        @PathVariable("tableId") long tableId,
        @Valid @RequestBody SchemaFieldRequest request
    ) {
        var id = insertAndReturnId("""
            insert into schema_fields(schema_table_id, field_name, field_type, nullable, sample_value, description)
            values (?, ?, ?, ?, ?, ?)
            """, tableId, request.fieldName(), request.fieldType(), request.nullable(),
            request.sampleValue(), request.description());
        return ApiResponse.ok(Map.of("id", id), "created");
    }

    @GetMapping("/tables/{tableId}/mappings")
    public ApiResponse<List<Map<String, Object>>> mappings(@PathVariable("tableId") long tableId) {
        return ApiResponse.ok(jdbcTemplate.queryForList("""
            select id, source_field, standard_field, transform_rule, created_at
            from field_mappings
            where schema_table_id = ?
            order by id
            """, tableId));
    }

    @PostMapping("/mappings")
    public ApiResponse<Map<String, Object>> createMapping(@Valid @RequestBody FieldMappingRequest request) {
        var id = insertAndReturnId("""
            insert into field_mappings(schema_table_id, source_field, standard_field, transform_rule)
            values (?, ?, ?, ?)
            """, request.schemaTableId(), request.sourceField(),
            request.standardField(), request.transformRule());
        return ApiResponse.ok(Map.of("id", id), "created");
    }

    private Long insertAndReturnId(String sql, Object... args) {
        var keyHolder = new GeneratedKeyHolder();
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
}
