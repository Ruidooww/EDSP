package com.edsp.core.service;

import com.edsp.core.dto.RuleEnabledRequest;
import com.edsp.core.dto.RuleRequest;
import com.edsp.core.support.CoreRequestSupport;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Statement;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class RuleService {
    private final JdbcTemplate jdbcTemplate;
    @SuppressWarnings("unused")
    private final ObjectMapper objectMapper;
    private final CoreRequestSupport support;

    public RuleService(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper, CoreRequestSupport support) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
        this.support = support;
    }

    public List<Map<String, Object>> list(int limit) {
        return jdbcTemplate.query("""
            select id, name, event_type, severity, expression, enabled, created_at, updated_at
            from rules
            order by created_at desc, id desc
            limit ?
            """, (rs, rowNum) -> {
                var row = new LinkedHashMap<String, Object>();
                row.put("id", rs.getLong("id"));
                row.put("name", rs.getString("name"));
                row.put("event_type", rs.getString("event_type"));
                row.put("eventType", rs.getString("event_type"));
                row.put("severity", rs.getString("severity"));
                row.put("expression", rs.getString("expression"));
                row.put("enabled", rs.getBoolean("enabled"));
                row.put("created_at", rs.getObject("created_at"));
                row.put("createdAt", rs.getObject("created_at"));
                row.put("updated_at", rs.getObject("updated_at"));
                row.put("updatedAt", rs.getObject("updated_at"));
                return row;
            }, support.safeLimit(limit, 200));
    }

    public Map<String, Object> create(RuleRequest request) {
        if (request == null || support.stringOrNull(request.name()) == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "name is required");
        }
        var eventType = support.stringOrDefault(request.eventType(), "*");
        var severity = support.stringOrDefault(request.severity(), "medium");
        var expression = support.jsonOrDefault(request.expression(), "expression", "{}");
        var enabled = request.enabled() == null || request.enabled();
        var id = insertAndReturnId("""
            insert into rules(name, event_type, severity, expression, enabled)
            values (?, ?, ?, ?, ?)
            """, request.name().trim(), eventType, severity, expression, enabled);
        return get(id);
    }

    public Map<String, Object> setEnabled(long id, RuleEnabledRequest request) {
        var updated = jdbcTemplate.update("""
            update rules
            set enabled = ?, updated_at = now()
            where id = ?
            """, request != null && Boolean.TRUE.equals(request.enabled()), id);
        if (updated == 0) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "rule not found");
        }
        return get(id);
    }

    private Map<String, Object> get(long id) {
        return jdbcTemplate.query("""
            select id, name, event_type, severity, expression, enabled, created_at, updated_at
            from rules
            where id = ?
            """, rs -> {
                if (!rs.next()) {
                    throw new ResponseStatusException(HttpStatus.NOT_FOUND, "rule not found");
                }
                var row = new LinkedHashMap<String, Object>();
                row.put("id", rs.getLong("id"));
                row.put("name", rs.getString("name"));
                row.put("event_type", rs.getString("event_type"));
                row.put("eventType", rs.getString("event_type"));
                row.put("severity", rs.getString("severity"));
                row.put("expression", rs.getString("expression"));
                row.put("enabled", rs.getBoolean("enabled"));
                row.put("created_at", rs.getObject("created_at"));
                row.put("createdAt", rs.getObject("created_at"));
                row.put("updated_at", rs.getObject("updated_at"));
                row.put("updatedAt", rs.getObject("updated_at"));
                return row;
            }, id);
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
