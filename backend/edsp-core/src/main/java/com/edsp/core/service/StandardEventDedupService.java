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
        return findExistingStandardEventId(dedupKey, null, sourceSystem, externalId);
    }

    public Long findExistingStandardEventId(String dedupKey, Long dataSourceId, String sourceSystem, String externalId) {
        var rows = dedupKey == null || dedupKey.isBlank()
            ? List.<Map<String, Object>>of()
            : dedupRows(dedupKey, dataSourceId);
        if (rows.isEmpty() && externalId != null) {
            rows = externalRows(dataSourceId, sourceSystem, externalId);
        }
        if (rows.isEmpty()) {
            return null;
        }
        return support.number(rows.get(0).get("id"));
    }

    private List<Map<String, Object>> dedupRows(String dedupKey, Long dataSourceId) {
        if (dataSourceId == null) {
            return jdbcTemplate.queryForList("""
                select id
                from standard_events
                where dedup_key = ?
                limit 1
                """, dedupKey);
        }
        return jdbcTemplate.queryForList("""
            select id
            from standard_events
            where data_source_id = ? and dedup_key = ?
            limit 1
            """, dataSourceId, dedupKey);
    }

    private List<Map<String, Object>> externalRows(Long dataSourceId, String sourceSystem, String externalId) {
        if (dataSourceId == null) {
            return jdbcTemplate.queryForList("""
                select id
                from standard_events
                where source_system = ? and external_id = ?
                limit 1
                """, sourceSystem, externalId);
        }
        return jdbcTemplate.queryForList("""
            select id
            from standard_events
            where data_source_id = ? and source_system = ? and external_id = ?
            limit 1
            """, dataSourceId, sourceSystem, externalId);
    }
}
