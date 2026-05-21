package com.edsp.core.service;

import com.edsp.core.support.CoreRequestSupport;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class StandardEventDedupService {
    private final JdbcTemplate jdbcTemplate;
    private final CoreRequestSupport support;

    public StandardEventDedupService(JdbcTemplate jdbcTemplate, CoreRequestSupport support) {
        this.jdbcTemplate = jdbcTemplate;
        this.support = support;
    }

    public Long findExistingStandardEventId(String dedupKey, String sourceSystem, String externalId) {
        var rows = dedupKey == null || dedupKey.isBlank()
            ? List.<Map<String, Object>>of()
            : jdbcTemplate.queryForList("""
                select id
                from standard_events
                where dedup_key = ?
                limit 1
                """, dedupKey);
        if (rows.isEmpty() && externalId != null) {
            rows = jdbcTemplate.queryForList("""
                select id
                from standard_events
                where source_system = ? and external_id = ?
                limit 1
                """, sourceSystem, externalId);
        }
        if (rows.isEmpty()) {
            return null;
        }
        return support.number(rows.get(0).get("id"));
    }
}
