package com.edsp.core.controller;

import com.edsp.common.api.ApiResponse;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/core/audit-logs")
public class AuditController {
    private final JdbcTemplate jdbcTemplate;

    public AuditController(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @GetMapping
    public ApiResponse<List<Map<String, Object>>> list(@RequestParam(name = "limit", defaultValue = "80") int limit) {
        int safeLimit = Math.max(1, Math.min(limit, 300));
        return ApiResponse.ok(jdbcTemplate.queryForList("""
            select id, actor, action, target_type, target_id,
                   cast(detail_json as varchar) as detail_json,
                   cast(created_at as varchar) as created_at
            from audit_logs
            order by created_at desc
            limit ?
            """, safeLimit));
    }
}
