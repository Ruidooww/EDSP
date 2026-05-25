package com.edsp.core.service;

import java.sql.Timestamp;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class DemoDataSeeder implements ApplicationRunner {
    private final JdbcTemplate jdbcTemplate;
    private final boolean demoEnabled;

    public DemoDataSeeder(
        JdbcTemplate jdbcTemplate,
        @Value("${edsp.demo.enabled:${EDSP_DEMO_ENABLED:false}}") boolean demoEnabled
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.demoEnabled = demoEnabled;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!demoEnabled) {
            return;
        }

        seedUsers();
        seedDataSources();
        seedSchemaSnapshot();
        seedAlerts();
        seedNotifications();
        seedRules();
        seedReports();
        seedAlertNotes();
        seedAuditLogs();
    }

    private void seedUsers() {
        if (exists("select count(*) from app_users where username = ?", "admin")) {
            jdbcTemplate.update("""
                update app_users
                set display_name = ?, status = 'active', updated_at = now()
                where username = ?
                """, "平台管理员", "admin");
            return;
        }
        jdbcTemplate.update("""
            insert into app_users(username, display_name, status)
            values (?, ?, ?)
            """, "admin", "平台管理员", "active");
    }

    private void seedDataSources() {
        seedDataSource(
            "终端安全系统",
            "sqlserver",
            "database",
            "本地化终端安全系统数据库，提供终端、外设、文件操作类预警。",
            """
            {"host":"10.10.20.15","port":1433,"database":"terminal_security","username":"readonly","encrypt":false,"trustServerCertificate":true}
            """,
            "active",
            true);
        seedDataSource(
            "DLP 告警 API",
            "http_api",
            "api",
            "DLP 平台开放 API，按分钟拉取外发、上传、打印等敏感数据行为。",
            """
            {"endpointUrl":"https://dlp.demo.local/openapi/alerts","method":"GET","authType":"bearer","pollingInterval":60}
            """,
            "configured",
            true);
        seedDataSource(
            "OA 审计库",
            "postgresql",
            "database",
            "OA 系统审计数据库，提供登录、审批、下载、导出等行为日志。",
            """
            {"host":"10.10.30.22","port":5432,"database":"oa_audit","username":"audit_reader"}
            """,
            "active",
            true);
        seedDataSource(
            "邮件网关 Webhook",
            "webhook",
            "webhook",
            "邮件网关主动推送外发拦截、附件风险、收件人异常等事件。",
            """
            {"endpointUrl":"/api/alerts/ingest","authType":"signature","headerName":"X-Alert-Signature"}
            """,
            "configured",
            true);
        seedDataSource(
            "UEBA 风险平台",
            "security_platform",
            "security_platform",
            "UEBA 平台风险事件接入，包含账号异常、横向移动、异常访问画像。",
            """
            {"vendor":"ueba","product":"UEBA Risk Center","baseUrl":"https://ueba.demo.local","pollingInterval":120}
            """,
            "active",
            true);
        seedDataSource(
            "离线日志导入",
            "file_import",
            "file",
            "用于导入历史审计、离线日志、客户演示数据文件。",
            """
            {"fileFormat":"csv","schedule":"manual","watchPath":"/data/security-alert/imports"}
            """,
            "configured",
            true);
    }

    private void seedSchemaSnapshot() {
        var terminalSourceId = id("select id from data_sources where name = ?", "终端安全系统");
        var dlpSourceId = id("select id from data_sources where name = ?", "DLP 告警 API");
        var oaSourceId = id("select id from data_sources where name = ?", "OA 审计库");
        var mailSourceId = id("select id from data_sources where name = ?", "邮件网关 Webhook");
        var uebaSourceId = id("select id from data_sources where name = ?", "UEBA 风险平台");
        var importSourceId = id("select id from data_sources where name = ?", "离线日志导入");

        var alertTableId = seedSchemaTable(terminalSourceId, "security_alert_event", "终端告警事件", "confirmed");
        seedField(alertTableId, "event_id", "varchar", false, "EVT-20260520-001", "外部告警唯一编号");
        seedField(alertTableId, "event_name", "varchar", false, "疑似敏感文件外发", "告警名称");
        seedField(alertTableId, "risk_level", "varchar", false, "high", "风险等级");
        seedField(alertTableId, "event_time", "timestamp", false, "2026-05-20 09:42:00", "发生时间");
        seedField(alertTableId, "user_name", "varchar", true, "张三", "涉及用户");
        seedField(alertTableId, "asset_name", "varchar", true, "DESKTOP-8K2A", "终端资产");
        seedMapping(alertTableId, "event_id", "externalId", "直接映射");
        seedMapping(alertTableId, "event_name", "title", "直接映射");
        seedMapping(alertTableId, "risk_level", "severity", "high/medium/low 标准化");
        seedMapping(alertTableId, "event_time", "occurredAt", "时间格式转换");
        seedMapping(alertTableId, "user_name", "actor", "直接映射");

        var deviceTableId = seedSchemaTable(terminalSourceId, "device_operation_log", "外设操作", "confirmed");
        seedField(deviceTableId, "device_id", "varchar", false, "USB-9281", "外设编号");
        seedField(deviceTableId, "operation", "varchar", false, "copy", "操作类型");
        seedField(deviceTableId, "file_count", "int", false, "36", "文件数量");
        seedField(deviceTableId, "operator", "varchar", true, "李四", "操作人");
        seedMapping(deviceTableId, "operation", "alertType", "操作类型转换");
        seedMapping(deviceTableId, "file_count", "detail.copiedFiles", "写入详情");

        var dlpTableId = seedSchemaTable(dlpSourceId, "dlp_incidents", "DLP 事件", "confirmed");
        seedField(dlpTableId, "incident_no", "varchar", false, "DLP-20260520-008", "DLP 事件编号");
        seedField(dlpTableId, "channel", "varchar", false, "email", "外发通道");
        seedField(dlpTableId, "sensitive_type", "varchar", true, "客户信息", "敏感数据类型");
        seedField(dlpTableId, "recipient", "varchar", true, "external@example.com", "接收方");
        seedMapping(dlpTableId, "incident_no", "externalId", "直接映射");
        seedMapping(dlpTableId, "sensitive_type", "detail.sensitiveType", "写入详情");

        var oaTableId = seedSchemaTable(oaSourceId, "oa_download_audit", "OA 下载审计", "confirmed");
        seedField(oaTableId, "log_id", "bigint", false, "900182", "日志 ID");
        seedField(oaTableId, "employee_no", "varchar", false, "E1024", "员工编号");
        seedField(oaTableId, "document_name", "varchar", true, "供应商报价汇总.xlsx", "文档名称");
        seedField(oaTableId, "download_count", "int", false, "42", "下载次数");
        seedMapping(oaTableId, "document_name", "assetRef", "文档资产");
        seedMapping(oaTableId, "download_count", "detail.downloadCount", "写入详情");

        var mailTableId = seedSchemaTable(mailSourceId, "mail_gateway_event", "邮件网关事件", "confirmed");
        seedField(mailTableId, "message_id", "varchar", false, "MSG-882019", "邮件编号");
        seedField(mailTableId, "sender", "varchar", false, "sales01@example.com", "发件人");
        seedField(mailTableId, "attachment_names", "text", true, "合同清单.zip", "附件名称");
        seedMapping(mailTableId, "message_id", "externalId", "直接映射");
        seedMapping(mailTableId, "sender", "actor", "发件人转操作人");

        var uebaTableId = seedSchemaTable(uebaSourceId, "ueba_risk_event", "UEBA 风险事件", "confirmed");
        seedField(uebaTableId, "risk_id", "varchar", false, "UEBA-88421", "画像风险编号");
        seedField(uebaTableId, "risk_score", "int", false, "92", "风险分");
        seedField(uebaTableId, "behavior", "varchar", false, "abnormal_login", "异常行为");
        seedMapping(uebaTableId, "risk_score", "detail.riskScore", "写入详情");
        seedMapping(uebaTableId, "behavior", "alertType", "行为类型映射");

        var importTableId = seedSchemaTable(importSourceId, "imported_audit_csv", "离线导入文件", "pending");
        seedField(importTableId, "source_file", "varchar", false, "2026-05-audit.csv", "导入文件名");
        seedField(importTableId, "row_hash", "varchar", false, "b3c7e9", "行指纹");
        seedField(importTableId, "raw_payload", "json", true, "{}", "原始内容");
    }

    private void seedNotifications() {
        seedChannel("安全运营 Webhook", "webhook", "https://demo.mizuumi.top/mock/security-webhook",
            "统一推送到安全运营工作台，用于演示标准 Webhook 投递。", "{\"mode\":\"demo\",\"owner\":\"SOC\"}", true, "ready", "success", "演示通道测试成功");
        seedChannel("企业微信值班群", "wecom", null,
            "高危告警推送到企业微信安全值班群。", "{\"robot\":\"security-duty\",\"mention\":\"@all\"}", false, "disabled", "unsupported", "后续扩展");
        seedChannel("飞书安全群", "feishu", null,
            "中高危告警推送到飞书安全群。", "{\"robot\":\"security-alert\"}", false, "disabled", "unsupported", "后续扩展");
        seedChannel("短信告警", "sms", "https://sms.demo.local/send",
            "严重风险通过短信通知值班负责人。", "{\"provider\":\"demo-sms\",\"template\":\"risk-alert\"}", false, "disabled", "unsupported", "后续扩展");
        seedChannel("邮件审计归档", "email", "https://mail.demo.local/api/send",
            "处置结果、日报和审计材料发送到安全邮箱。", "{\"mailbox\":\"security-archive@example.com\"}", false, "disabled", "unsupported", "后续扩展");

        seedNotificationDelivery("安全运营 Webhook", "terminal-security", "DEMO-ALERT-001", "疑似敏感文件外发", "high", "success", 200, "webhook accepted");
    }

    private void seedRules() {
        var channelIds = channelIdsJson();
        seedRule("非工作时间大文件外发", "file_transfer", "high",
            ruleExpression("large_file_transfer", "after_hours", "file_size_mb", "文件大小", 100, "MB", "all_users", "document_assets", channelIds),
            true);
        seedRule("移动存储批量拷贝", "device_operation", "medium",
            ruleExpression("removable_storage", "all_day", "copied_files", "拷贝文件数", 20, "个", "all_users", "endpoint_assets", channelIds),
            true);
        seedRule("敏感数据高频访问", "data_access", "high",
            ruleExpression("sensitive_data_access", "business_hours", "access_count", "敏感对象访问次数", 50, "次", "privileged_users", "database_assets", channelIds),
            true);
        seedRule("异常登录风险", "account_behavior", "medium",
            ruleExpression("abnormal_login", "all_day", "risk_score", "UEBA 风险分", 80, "分", "all_users", "account_assets", channelIds),
            true);
        seedRule("邮件附件外发", "mail_transfer", "medium",
            ruleExpression("mail_attachment_leak", "all_day", "attachment_count", "附件数量", 3, "个", "all_users", "mail_assets", channelIds),
            true);
        seedRule("低危提示归档", "audit_notice", "low",
            ruleExpression("audit_notice", "all_day", "event_count", "事件数量", 1, "条", "all_users", "all_assets", "[]"),
            false);
    }

    private void seedAlerts() {
        var now = OffsetDateTime.now();
        seedAlert("terminal-security", "DEMO-ALERT-001", "file_transfer", "疑似敏感文件外发", "high", "open",
            "张三", "DESKTOP-8K2A / 10.8.12.25", "非工作时间大文件外发", "file", "客户名单.xlsx",
            now.minusMinutes(28), "{\"fileName\":\"客户名单.xlsx\",\"fileSizeMb\":128,\"channel\":\"外接存储\",\"department\":\"销售一部\"}");
        seedAlert("terminal-security", "DEMO-ALERT-002", "device_operation", "移动存储批量拷贝", "medium", "processing",
            "李四", "LAPTOP-FIN-03 / 10.8.20.31", "移动存储批量拷贝", "device", "USB-Storage",
            now.minusHours(2), "{\"device\":\"Kingston USB\",\"copiedFiles\":36,\"department\":\"财务部\"}");
        seedAlert("dlp-api", "DLP-20260520-008", "mail_transfer", "邮件外发包含敏感附件", "critical", "open",
            "赵六", "mail-gateway-01", "邮件附件外发", "mail", "external@example.com",
            now.minusHours(4), "{\"attachment\":\"合同清单.zip\",\"recipient\":\"external@example.com\",\"sensitiveType\":\"合同/报价\"}");
        seedAlert("oa-audit", "OA-900182", "data_export", "OA 文档批量下载", "medium", "open",
            "王五", "OA-DOC-供应商报价汇总.xlsx", "敏感数据高频访问", "document", "供应商报价汇总.xlsx",
            now.minusHours(7), "{\"downloadCount\":42,\"department\":\"采购部\",\"businessSystem\":\"OA\"}");
        seedAlert("ueba-risk", "UEBA-88421", "account_behavior", "异地登录后访问核心系统", "high", "processing",
            "孙七", "VPN / 203.0.113.18", "异常登录风险", "account", "sunqi",
            now.minusDays(1).minusHours(1), "{\"riskScore\":92,\"location\":\"异地\",\"targetSystem\":\"财务系统\"}");
        seedAlert("terminal-security", "DEMO-ALERT-006", "print_operation", "敏感报表打印", "low", "acknowledged",
            "陈八", "PRINTER-03", "低危提示归档", "printer", "客户回访清单.pdf",
            now.minusDays(1).minusHours(5), "{\"pages\":18,\"printer\":\"PRINTER-03\",\"department\":\"客服部\"}");
        seedAlert("dlp-api", "DLP-20260518-031", "cloud_upload", "云盘上传敏感压缩包", "high", "resolved",
            "周九", "browser-upload", "非工作时间大文件外发", "cloud", "供应商资料.zip",
            now.minusDays(2).minusHours(3), "{\"fileSizeMb\":256,\"cloud\":\"个人网盘\",\"action\":\"blocked\"}");
        seedAlert("mail-gateway", "MAIL-882019", "mail_transfer", "外部收件人数量异常", "medium", "closed",
            "钱十", "mail-gateway-01", "邮件附件外发", "mail", "group-mail",
            now.minusDays(3).minusHours(6), "{\"recipientCount\":24,\"externalDomains\":5}");
        seedAlert("offline-import", "CSV-20260516-004", "audit_notice", "离线日志导入发现异常下载", "info", "open",
            "系统导入", "2026-05-audit.csv", "低危提示归档", "import", "row#8842",
            now.minusDays(4).minusHours(2), "{\"sourceFile\":\"2026-05-audit.csv\",\"row\":8842}");
        seedAlert("terminal-security", "DEMO-ALERT-010", "data_access", "客户手机号字段高频访问", "high", "resolved",
            "吴十一", "CRM-DB / customer.phone", "敏感数据高频访问", "database", "customer.phone",
            now.minusDays(5).minusHours(4), "{\"table\":\"customer\",\"field\":\"phone\",\"accessCount\":83}");
        seedAlert("ueba-risk", "UEBA-77120", "account_behavior", "管理员账号夜间登录", "medium", "closed",
            "admin-audit", "堡垒机 / 10.8.99.10", "异常登录风险", "account", "admin-audit",
            now.minusDays(6).minusHours(2), "{\"riskScore\":81,\"loginHour\":2}");
    }

    private void seedReports() {
        seedReport("每日风险汇总", "risk_summary", "completed", "/api/reports/jobs/demo-risk-summary/export",
            "{\"period\":\"today\",\"scope\":\"all\",\"includeRaw\":true}");
        seedReport("告警处置跟踪周报", "incident", "completed", "/api/reports/jobs/demo-incident-weekly/export",
            "{\"period\":\"last_7_days\",\"scope\":\"open_alerts\",\"includeRaw\":true}");
        seedReport("外部接入巡检报告", "integration", "running", null,
            "{\"period\":\"today\",\"scope\":\"external\",\"includeRaw\":false}");
        seedReport("通知投递统计", "delivery", "completed", "/api/reports/jobs/demo-delivery/export",
            "{\"period\":\"last_7_days\",\"scope\":\"all\",\"includeRaw\":true}");
        seedReport("合规审计材料", "audit", "pending", null,
            "{\"period\":\"last_30_days\",\"scope\":\"all\",\"includeRaw\":true}");
    }

    private void seedAlertNotes() {
        seedNote("terminal-security", "DEMO-ALERT-001", "admin", "已联系销售一部负责人核实，先冻结外接存储策略并保留原始日志。");
        seedNote("ueba-risk", "UEBA-88421", "security-ops", "确认账号本人出差登录，要求补充审批记录，风险保持处理中。");
        seedNote("dlp-api", "DLP-20260518-031", "admin", "DLP 已成功拦截外发，文件已加入敏感资料管控清单。");
    }

    private void seedAuditLogs() {
        seedAudit("admin", "执行数据库心跳检测", "data_source", "终端安全系统",
            "{\"result\":\"success\",\"latency\":\"18ms\",\"source\":\"终端安全系统\"}");
        seedAudit("system", "采集外部告警", "alert", "DLP 告警 API",
            "{\"created\":3,\"updated\":8,\"source\":\"DLP 告警 API\"}");
        seedAudit("admin", "创建规则", "rule", "非工作时间大文件外发",
            "{\"severity\":\"high\",\"notify\":true}");
        seedAudit("system", "标记通知通道为后续扩展", "notification_channel", "企业微信值班群",
            "{\"channel\":\"企业微信值班群\",\"result\":\"unsupported\"}");
        seedAudit("security-ops", "添加告警处置记录", "alert", "疑似敏感文件外发",
            "{\"status\":\"processing\",\"note\":\"冻结外接存储策略\"}");
        seedAudit("admin", "创建报表任务", "report_job", "每日风险汇总",
            "{\"period\":\"today\",\"scope\":\"all\",\"includeRaw\":true}");
        seedAudit("system", "同步元数据快照", "schema_table", "security_alert_event",
            "{\"fields\":6,\"mappings\":5}");
        seedAudit("admin", "配置 Webhook 通道", "notification_channel", "安全运营 Webhook",
            "{\"channel\":\"安全运营 Webhook\",\"result\":\"ready\"}");
    }

    private Long seedDataSource(String name, String sourceType, String connectionKind, String description, String configJson, String status, boolean enabled) {
        if (exists("select count(*) from data_sources where name = ?", name)) {
            jdbcTemplate.update("""
                update data_sources
                set source_type = ?, connection_kind = ?, description = ?, config_json = cast(? as jsonb),
                    status = ?, enabled = ?, updated_at = now()
                where name = ?
                """, sourceType, connectionKind, description, configJson, status, enabled, name);
        } else {
            jdbcTemplate.update("""
                insert into data_sources(name, source_type, connection_kind, description, config_json, status, enabled)
                values (?, ?, ?, ?, cast(? as jsonb), ?, ?)
                """, name, sourceType, connectionKind, description, configJson, status, enabled);
        }
        return id("select id from data_sources where name = ?", name);
    }

    private Long seedSchemaTable(Long sourceId, String tableName, String category, String status) {
        if (sourceId == null) {
            return null;
        }
        if (exists("select count(*) from schema_tables where data_source_id = ? and table_name = ?", sourceId, tableName)) {
            jdbcTemplate.update("""
                update schema_tables
                set category = ?, confirmation_status = ?, updated_at = now()
                where data_source_id = ? and table_name = ?
                """, category, status, sourceId, tableName);
        } else {
            jdbcTemplate.update("""
                insert into schema_tables(data_source_id, table_name, category, confirmation_status)
                values (?, ?, ?, ?)
                """, sourceId, tableName, category, status);
        }
        return id("select id from schema_tables where data_source_id = ? and table_name = ?", sourceId, tableName);
    }

    private void seedField(Long tableId, String name, String type, boolean nullable, String sample, String description) {
        if (tableId == null) {
            return;
        }
        if (exists("select count(*) from schema_fields where schema_table_id = ? and field_name = ?", tableId, name)) {
            jdbcTemplate.update("""
                update schema_fields
                set field_type = ?, nullable = ?, sample_value = ?, description = ?
                where schema_table_id = ? and field_name = ?
                """, type, nullable, sample, description, tableId, name);
            return;
        }
        jdbcTemplate.update("""
            insert into schema_fields(schema_table_id, field_name, field_type, nullable, sample_value, description)
            values (?, ?, ?, ?, ?, ?)
            """, tableId, name, type, nullable, sample, description);
    }

    private void seedMapping(Long tableId, String sourceField, String standardField, String rule) {
        if (tableId == null) {
            return;
        }
        if (exists("""
            select count(*) from field_mappings
            where schema_table_id = ? and source_field = ? and standard_field = ?
            """, tableId, sourceField, standardField)) {
            jdbcTemplate.update("""
                update field_mappings
                set transform_rule = ?
                where schema_table_id = ? and source_field = ? and standard_field = ?
                """, rule, tableId, sourceField, standardField);
            return;
        }
        jdbcTemplate.update("""
            insert into field_mappings(schema_table_id, source_field, standard_field, transform_rule)
            values (?, ?, ?, ?)
            """, tableId, sourceField, standardField, rule);
    }

    private void seedRule(String name, String eventType, String severity, String expression, boolean enabled) {
        if (exists("select count(*) from rules where name = ?", name)) {
            jdbcTemplate.update("""
                update rules
                set event_type = ?, severity = ?, expression = ?, enabled = ?, updated_at = now()
                where name = ?
                """, eventType, severity, expression, enabled, name);
            return;
        }
        jdbcTemplate.update("""
            insert into rules(name, event_type, severity, expression, enabled)
            values (?, ?, ?, ?, ?)
            """, name, eventType, severity, expression, enabled);
    }

    private String ruleExpression(
        String scenario,
        String timeWindow,
        String metric,
        String label,
        int value,
        String unit,
        String subject,
        String asset,
        String channelIds
    ) {
        return """
            {"version":1,"mode":"template","scenario":"%s","timeWindow":"%s","threshold":{"metric":"%s","label":"%s","value":%d,"unit":"%s"},"scope":{"subject":"%s","asset":"%s"},"action":{"createAlert":true,"notify":true,"channelIds":%s}}
            """.formatted(scenario, timeWindow, metric, label, value, unit, subject, asset, channelIds).trim();
    }

    private void seedAlert(
        String sourceSystem,
        String externalId,
        String alertType,
        String title,
        String severity,
        String status,
        String actor,
        String asset,
        String policy,
        String subjectType,
        String subjectRef,
        OffsetDateTime occurredAt,
        String detail
    ) {
        var timestamp = Timestamp.from(occurredAt.toInstant());
        if (exists("select count(*) from alerts where source_system = ? and external_id = ?", sourceSystem, externalId)) {
            jdbcTemplate.update("""
                update alerts
                set title = ?, severity = ?, status = ?, alert_type = ?, occurred_at = ?, actor = ?, asset_ref = ?,
                    policy_name = ?, subject_type = ?, subject_ref = ?, detail_json = cast(? as jsonb),
                    created_at = ?, updated_at = now()
                where source_system = ? and external_id = ?
                """,
                title, severity, status, alertType, timestamp, actor, asset, policy,
                subjectType, subjectRef, detail, timestamp, sourceSystem, externalId);
            return;
        }

        jdbcTemplate.update("""
            insert into alerts(title, severity, status, subject_type, subject_ref, detail_json,
                               source_system, external_id, alert_type, occurred_at, actor, asset_ref,
                               policy_name, created_at, updated_at)
            values (?, ?, ?, ?, ?, cast(? as jsonb), ?, ?, ?, ?, ?, ?, ?, ?, now())
            """,
            title, severity, status, subjectType, subjectRef, detail, sourceSystem, externalId, alertType,
            timestamp, actor, asset, policy, timestamp);
    }

    private void seedChannel(
        String name,
        String type,
        String endpoint,
        String description,
        String config,
        boolean enabled,
        String status,
        String testStatus,
        String testMessage
    ) {
        if (exists("select count(*) from notification_channels where name = ?", name)) {
            jdbcTemplate.update("""
                update notification_channels
                set channel_type = ?, endpoint_url = ?, endpoint_masked = ?, secret_storage_status = ?,
                    description = ?, config_json = cast(? as jsonb),
                    enabled = ?, status = ?, last_test_status = ?, last_test_message = ?, last_test_at = now(),
                    updated_at = now()
                where name = ?
                """, type, endpoint, endpointMasked(endpoint), secretStorageStatus(endpoint),
                description, config, enabled, status, testStatus, testMessage, name);
            return;
        }
        jdbcTemplate.update("""
            insert into notification_channels(name, channel_type, endpoint_url, endpoint_masked,
                                              secret_storage_status, description, config_json,
                                              enabled, status, last_test_status, last_test_message, last_test_at)
            values (?, ?, ?, ?, ?, ?, cast(? as jsonb), ?, ?, ?, ?, now())
            """, name, type, endpoint, endpointMasked(endpoint), secretStorageStatus(endpoint),
            description, config, enabled, status, testStatus, testMessage);
    }

    private String endpointMasked(String endpoint) {
        return endpoint == null || endpoint.isBlank() ? "demo://not-configured" : endpoint;
    }

    private String secretStorageStatus(String endpoint) {
        return endpoint == null || endpoint.isBlank() ? "missing" : "legacy_plaintext";
    }

    private void seedReport(String title, String type, String status, String filePath, String paramsJson) {
        if (exists("select count(*) from report_jobs where title = ?", title)) {
            jdbcTemplate.update("""
                update report_jobs
                set report_type = ?, status = ?, file_path = ?, params_json = cast(? as jsonb), updated_at = now()
                where title = ?
                """, type, status, filePath, paramsJson, title);
            return;
        }
        jdbcTemplate.update("""
            insert into report_jobs(report_type, title, status, file_path, params_json)
            values (?, ?, ?, ?, cast(? as jsonb))
            """, type, title, status, filePath, paramsJson);
    }

    private void seedNotificationDelivery(String channelName, String sourceSystem, String externalId, String title, String severity, String status, int responseCode, String body) {
        var channelId = id("select id from notification_channels where name = ?", channelName);
        var alertId = id("select id from alerts where source_system = ? and external_id = ?", sourceSystem, externalId);
        if (channelId == null || alertId == null) {
            return;
        }
        if (exists("""
            select count(*) from notification_deliveries
            where channel_id = ? and alert_id = ? and title = ?
            """, channelId, alertId, title)) {
            return;
        }
        jdbcTemplate.update("""
            insert into notification_deliveries(channel_id, alert_id, title, severity, status,
                                                response_code, response_body, payload_json)
            values (?, ?, ?, ?, ?, ?, ?, cast(? as jsonb))
            """,
            channelId,
            alertId,
            title,
            severity,
            status,
            responseCode,
            body,
            "{\"mode\":\"demo\",\"delivery\":\"standard-alert\"}");
    }

    private void seedNote(String sourceSystem, String externalId, String operator, String note) {
        var alertId = id("select id from alerts where source_system = ? and external_id = ?", sourceSystem, externalId);
        if (alertId == null || exists("select count(*) from alert_notes where alert_id = ? and note = ?", alertId, note)) {
            return;
        }
        jdbcTemplate.update("""
            insert into alert_notes(alert_id, operator_name, note)
            values (?, ?, ?)
            """, alertId, operator, note);
    }

    private void seedAudit(String actor, String action, String targetType, String targetId, String detail) {
        if (exists("""
            select count(*) from audit_logs
            where actor = ? and action = ? and target_type = ? and target_id = ?
            """, actor, action, targetType, targetId)) {
            return;
        }
        jdbcTemplate.update("""
            insert into audit_logs(actor, action, target_type, target_id, detail_json)
            values (?, ?, ?, ?, cast(? as jsonb))
            """, actor, action, targetType, targetId, detail);
    }

    private String channelIdsJson() {
        var ids = jdbcTemplate.queryForList("""
            select id from notification_channels
            where enabled = true
            order by id
            limit 3
            """, Long.class);
        if (ids.isEmpty()) {
            return "[]";
        }
        var values = new ArrayList<String>();
        for (Long id : ids) {
            values.add(String.valueOf(id));
        }
        return "[" + String.join(",", values) + "]";
    }

    private boolean exists(String sql, Object... args) {
        Long value = jdbcTemplate.queryForObject(sql, Long.class, args);
        return value != null && value > 0;
    }

    private Long id(String sql, Object... args) {
        List<Long> ids = jdbcTemplate.query(sql, (rs, rowNum) -> rs.getLong(1), args);
        return ids.isEmpty() ? null : ids.get(0);
    }
}
