package com.edsp.report.controller;

import com.edsp.common.api.ApiResponse;
import com.edsp.report.dto.ReportJobRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import java.io.IOException;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/reports")
public class ReportController {
    private final JdbcTemplate jdbcTemplate;

    public ReportController(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @GetMapping("/jobs")
    public ApiResponse<List<Map<String, Object>>> jobs() {
        return ApiResponse.ok(jdbcTemplate.queryForList("""
            select id, report_type, title, status, file_path, created_at, updated_at
            from report_jobs
            order by created_at desc
            """));
    }

    @PostMapping("/jobs")
    public ApiResponse<Map<String, Object>> createJob(@Valid @RequestBody ReportJobRequest request) {
        var keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            var statement = connection.prepareStatement("""
            insert into report_jobs(report_type, title, status, params_json)
            values (?, ?, ?, cast(? as jsonb))
            """, Statement.RETURN_GENERATED_KEYS);
            statement.setString(1, request.reportType());
            statement.setString(2, request.title());
            statement.setString(3, "completed");
            statement.setString(4, request.paramsJson());
            return statement;
        }, keyHolder);
        var keys = keyHolder.getKeys();
        var idValue = keys == null ? keyHolder.getKey() : keys.getOrDefault("id", keys.get("ID"));
        var id = idValue instanceof Number number ? number.longValue() : 0L;

        var filePath = "/api/reports/jobs/" + id + "/export";
        jdbcTemplate.update("""
            update report_jobs
            set file_path = ?, updated_at = now()
            where id = ?
            """, filePath, id);
        jdbcTemplate.update("""
            insert into audit_logs(actor, action, target_type, target_id, detail_json)
            values (?, ?, ?, ?, cast(? as jsonb))
            """, "admin", "创建报表任务", "report_job", String.valueOf(id), request.paramsJson());
        return ApiResponse.ok(Map.of("id", id, "filePath", filePath), "created");
    }

    @GetMapping("/jobs/{id}/export")
    public void exportJob(@PathVariable("id") Long id, HttpServletResponse response) throws IOException {
        var job = jdbcTemplate.queryForMap("""
            select id, report_type, title, status, params_json, created_at
            from report_jobs
            where id = ?
            """, id);

        response.setContentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        response.setHeader("Content-Disposition", "attachment; filename=edsp-report-" + id + ".xlsx");

        try (var workbook = new XSSFWorkbook()) {
            var summary = workbook.createSheet("Summary");
            writeRow(summary, 0, "Item", "Value");
            writeRow(summary, 1, "Report ID", job.get("id"));
            writeRow(summary, 2, "Title", job.get("title"));
            writeRow(summary, 3, "Type", job.get("report_type"));
            writeRow(summary, 4, "Created At", job.get("created_at"));
            writeRow(summary, 6, "Data Sources", count("select count(*) from data_sources"));
            writeRow(summary, 7, "Open Alerts", count("""
                select count(*) from alerts
                where lower(status) not in ('closed', 'resolved', 'done', 'archived')
                """));
            writeRow(summary, 8, "Rules", count("select count(*) from rules"));
            writeRow(summary, 9, "Notification Channels", count("select count(*) from notification_channels"));

            var alerts = workbook.createSheet("Recent Alerts");
            writeRow(alerts, 0, "Title", "Severity", "Status", "Actor", "Asset", "Policy", "Created At");
            var rows = jdbcTemplate.queryForList("""
                select title, severity, status, actor, asset_ref, policy_name, created_at
                from alerts
                order by created_at desc
                limit 100
                """);
            for (int i = 0; i < rows.size(); i++) {
                var row = rows.get(i);
                writeRow(alerts, i + 1,
                    row.get("title"),
                    row.get("severity"),
                    row.get("status"),
                    row.get("actor"),
                    row.get("asset_ref"),
                    row.get("policy_name"),
                    row.get("created_at"));
            }

            for (var sheet : List.of(summary, alerts)) {
                for (int i = 0; i < 8; i++) {
                    sheet.autoSizeColumn(i);
                }
            }
            workbook.write(response.getOutputStream());
        }
    }

    @GetMapping("/exports/empty-template")
    public void emptyTemplate(HttpServletResponse response) throws IOException {
        response.setContentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        response.setHeader("Content-Disposition", "attachment; filename=edsp-empty-report.xlsx");

        try (var workbook = new XSSFWorkbook()) {
            var sheet = workbook.createSheet("Report");
            var header = sheet.createRow(0);
            header.createCell(0).setCellValue("Section");
            header.createCell(1).setCellValue("Message");
            var row = sheet.createRow(1);
            row.createCell(0).setCellValue("Data");
            row.createCell(1).setCellValue("No data is available yet.");
            workbook.write(response.getOutputStream());
        }
    }

    private long count(String sql) {
        Long value = jdbcTemplate.queryForObject(sql, Long.class);
        return value == null ? 0 : value;
    }

    private void writeRow(org.apache.poi.ss.usermodel.Sheet sheet, int rowIndex, Object... values) {
        var row = sheet.createRow(rowIndex);
        for (int i = 0; i < values.length; i++) {
            var cell = row.createCell(i);
            var value = values[i];
            if (value instanceof Number number) {
                cell.setCellValue(number.doubleValue());
            } else {
                cell.setCellValue(value == null ? "" : value.toString());
            }
        }
    }
}
