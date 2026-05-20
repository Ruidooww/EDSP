package com.edsp.alert.service;

import com.edsp.alert.dto.AlertNoteRequest;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Service;

@Service
public class AlertDispositionService {
    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public AlertDispositionService(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    public List<Map<String, Object>> listNotes(long alertId) {
        return jdbcTemplate.queryForList("""
            select id, alert_id, operator_name, note, created_at
            from alert_notes
            where alert_id = ?
            order by created_at desc
            """, alertId);
    }

    public Map<String, Object> addNote(long alertId, AlertNoteRequest request) {
        var keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            var statement = connection.prepareStatement("""
                insert into alert_notes(alert_id, operator_name, note)
                values (?, ?, ?)
                """, new String[] {"id"});
            statement.setLong(1, alertId);
            statement.setString(2, request.operatorName());
            statement.setString(3, request.note());
            return statement;
        }, keyHolder);

        var status = normalizeStatus(request.status());
        if (status != null) {
            jdbcTemplate.update("update alerts set status = ?, updated_at = now() where id = ?", status, alertId);
        }

        var idValue = keyHolder.getKey();
        var id = idValue == null ? 0 : idValue.longValue();
        saveAudit(alertId, id, request, status);

        var result = new LinkedHashMap<String, Object>();
        result.put("id", id);
        result.put("alertId", alertId);
        result.put("operatorName", request.operatorName());
        if (status != null) {
            result.put("status", status);
        }
        return result;
    }

    private void saveAudit(long alertId, long noteId, AlertNoteRequest request, String status) {
        var detail = new LinkedHashMap<String, Object>();
        detail.put("alertId", alertId);
        detail.put("noteId", noteId);
        detail.put("operatorName", request.operatorName());
        detail.put("note", request.note());
        detail.put("status", status);

        jdbcTemplate.update("""
            insert into audit_logs(actor, action, target_type, target_id, detail_json)
            values (?, ?, ?, ?, cast(? as jsonb))
            """, request.operatorName(), "alert.disposition.added", "alert", String.valueOf(alertId), toJson(detail));
    }

    private String normalizeStatus(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return switch (value.trim().toLowerCase()) {
            case "open", "processing", "resolved", "closed" -> value.trim().toLowerCase();
            case "未处理" -> "open";
            case "处理中" -> "processing";
            case "已确认", "已恢复" -> "resolved";
            case "已关闭", "关闭" -> "closed";
            default -> value.trim().toLowerCase();
        };
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException ex) {
            return "{}";
        }
    }
}
