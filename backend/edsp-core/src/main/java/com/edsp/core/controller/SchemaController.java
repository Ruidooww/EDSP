package com.edsp.core.controller;

import com.edsp.common.api.ApiResponse;
import com.edsp.core.dto.FieldMappingRequest;
import com.edsp.core.dto.SchemaFieldRequest;
import com.edsp.core.dto.SchemaTableRequest;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
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
            select st.id, ds.name as data_source_name, st.table_name, st.category,
                   st.confirmation_status, st.created_at, st.updated_at
            from schema_tables st
            join data_sources ds on ds.id = st.data_source_id
            order by st.updated_at desc
            """));
    }

    @PostMapping("/tables")
    public ApiResponse<Map<String, Object>> createTable(@Valid @RequestBody SchemaTableRequest request) {
        var id = jdbcTemplate.queryForObject("""
            insert into schema_tables(data_source_id, table_name, category, confirmation_status)
            values (?, ?, ?, ?)
            returning id
            """, Long.class, request.dataSourceId(), request.tableName(), request.category(), request.confirmationStatus());
        return ApiResponse.ok(Map.of("id", id), "created");
    }

    @GetMapping("/tables/{tableId}/fields")
    public ApiResponse<List<Map<String, Object>>> fields(@PathVariable long tableId) {
        return ApiResponse.ok(jdbcTemplate.queryForList("""
            select id, field_name, field_type, nullable, sample_value, description
            from schema_fields
            where schema_table_id = ?
            order by id
            """, tableId));
    }

    @PostMapping("/tables/{tableId}/fields")
    public ApiResponse<Map<String, Object>> createField(@PathVariable long tableId, @Valid @RequestBody SchemaFieldRequest request) {
        var id = jdbcTemplate.queryForObject("""
            insert into schema_fields(schema_table_id, field_name, field_type, nullable, sample_value, description)
            values (?, ?, ?, ?, ?, ?)
            returning id
            """, Long.class, tableId, request.fieldName(), request.fieldType(), request.nullable(),
            request.sampleValue(), request.description());
        return ApiResponse.ok(Map.of("id", id), "created");
    }

    @GetMapping("/tables/{tableId}/mappings")
    public ApiResponse<List<Map<String, Object>>> mappings(@PathVariable long tableId) {
        return ApiResponse.ok(jdbcTemplate.queryForList("""
            select id, source_field, standard_field, transform_rule, created_at
            from field_mappings
            where schema_table_id = ?
            order by id
            """, tableId));
    }

    @PostMapping("/mappings")
    public ApiResponse<Map<String, Object>> createMapping(@Valid @RequestBody FieldMappingRequest request) {
        var id = jdbcTemplate.queryForObject("""
            insert into field_mappings(schema_table_id, source_field, standard_field, transform_rule)
            values (?, ?, ?, ?)
            returning id
            """, Long.class, request.schemaTableId(), request.sourceField(),
            request.standardField(), request.transformRule());
        return ApiResponse.ok(Map.of("id", id), "created");
    }
}
