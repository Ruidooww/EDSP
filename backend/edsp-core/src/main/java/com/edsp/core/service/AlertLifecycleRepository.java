package com.edsp.core.service;

import com.edsp.core.support.CoreRequestSupport;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Repository;
import org.springframework.web.server.ResponseStatusException;

@Repository
public class AlertLifecycleRepository {
    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final CoreRequestSupport support;

    public AlertLifecycleRepository(
        JdbcTemplate jdbcTemplate,
        ObjectMapper objectMapper,
        CoreRequestSupport support
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
        this.support = support;
    }

    public Map<String, Object> findAlert(long alertId) {
        var rows = alertRows("""
            select id, title, severity, status, assigned_to, acknowledged_at, closed_at,
                   created_at, updated_at
            from alerts
            where id = ?
            limit 1
            """, alertId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public Map<String, Object> acknowledge(long alertId, String expectedStatus, String operatorName, String note) {
        var updated = jdbcTemplate.update("""
            update alerts
            set status = 'acknowledged',
                acknowledged_at = now(),
                updated_at = now()
            where id = ? and status = ?
            """, alertId, expectedStatus);
        requireUpdated(updated);
        insertEvent(alertId, "acknowledged", "open", "acknowledged", operatorName, null, note);
        return findAlert(alertId);
    }

    public Map<String, Object> assign(long alertId, String currentStatus, String operatorName, String assignee, String note) {
        var updated = jdbcTemplate.update("""
            update alerts
            set assigned_to = ?,
                updated_at = now()
            where id = ? and status = ?
            """, assignee, alertId, currentStatus);
        requireUpdated(updated);
        insertEvent(alertId, "assigned", currentStatus, currentStatus, operatorName, assignee, note);
        return findAlert(alertId);
    }

    public Map<String, Object> close(long alertId, String currentStatus, String operatorName, String note) {
        var updated = jdbcTemplate.update("""
            update alerts
            set status = 'closed',
                closed_at = now(),
                updated_at = now()
            where id = ? and status = ?
            """, alertId, currentStatus);
        requireUpdated(updated);
        insertEvent(alertId, "closed", currentStatus, "closed", operatorName, null, note);
        return findAlert(alertId);
    }

    public List<Map<String, Object>> timeline(long alertId) {
        return jdbcTemplate.query("""
            select id, alert_id, event_type, from_status, to_status, operator_name,
                   assignee, note, cast(detail_json as varchar) as detail_json, created_at
            from alert_lifecycle_events
            where alert_id = ?
            order by created_at desc, id desc
            """, (rs, rowNum) -> {
            var row = new LinkedHashMap<String, Object>();
            row.put("id", rs.getLong("id"));
            row.put("alertId", rs.getLong("alert_id"));
            row.put("eventType", rs.getString("event_type"));
            row.put("fromStatus", rs.getString("from_status"));
            row.put("toStatus", rs.getString("to_status"));
            row.put("operatorName", rs.getString("operator_name"));
            row.put("assignee", rs.getString("assignee"));
            row.put("assignedTo", rs.getString("assignee"));
            row.put("note", rs.getString("note"));
            row.put("detail", parseJson(rs.getObject("detail_json")));
            row.put("createdAt", rs.getObject("created_at"));
            return row;
        }, alertId);
    }

    private void insertEvent(
        long alertId,
        String eventType,
        String fromStatus,
        String toStatus,
        String operatorName,
        String assignee,
        String note
    ) {
        jdbcTemplate.update("""
            insert into alert_lifecycle_events(
                alert_id, event_type, from_status, to_status, operator_name,
                assignee, note, detail_json
            )
            values (?, ?, ?, ?, ?, ?, ?, cast(? as jsonb))
            """,
            alertId,
            eventType,
            fromStatus,
            toStatus,
            operatorName,
            assignee,
            note,
            toJson(Map.of("source", "core_alert_lifecycle"))
        );
    }

    private void requireUpdated(int updated) {
        if (updated == 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "alert_status_changed");
        }
    }

    private List<Map<String, Object>> alertRows(String sql, Object... args) {
        return jdbcTemplate.query(sql, (rs, rowNum) -> {
            var row = new LinkedHashMap<String, Object>();
            row.put("id", rs.getLong("id"));
            row.put("title", rs.getString("title"));
            row.put("severity", rs.getString("severity"));
            row.put("status", rs.getString("status"));
            row.put("assignedTo", rs.getString("assigned_to"));
            row.put("acknowledgedAt", rs.getObject("acknowledged_at"));
            row.put("closedAt", rs.getObject("closed_at"));
            row.put("createdAt", rs.getObject("created_at"));
            row.put("updatedAt", rs.getObject("updated_at"));
            return row;
        }, args);
    }

    private Map<String, Object> parseJson(Object value) {
        if (value == null) {
            return Map.of();
        }
        if (value instanceof Map<?, ?> map) {
            return objectMapper.convertValue(map, new TypeReference<>() {});
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

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Unable to serialize alert lifecycle detail", ex);
        }
    }
}
