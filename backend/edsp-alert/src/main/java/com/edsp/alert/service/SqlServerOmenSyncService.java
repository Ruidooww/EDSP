package com.edsp.alert.service;

import com.edsp.alert.dto.IngestAlertRequest;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.regex.Pattern;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class SqlServerOmenSyncService {
    private static final Pattern OME_TABLE_PATTERN = Pattern.compile("OME_[A-Za-z0-9_]+_LOG\\.\\d{8}");

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final AlertIngestService alertIngestService;

    public SqlServerOmenSyncService(
        JdbcTemplate jdbcTemplate,
        ObjectMapper objectMapper,
        AlertIngestService alertIngestService
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
        this.alertIngestService = alertIngestService;
    }

    public Map<String, Object> sync(long dataSourceId, String database, int tableLimit, int rowLimit) {
        var dataSource = jdbcTemplate.queryForMap("""
            select id, name, source_type, config_json
            from data_sources
            where id = ?
            """, dataSourceId);
        var sourceType = String.valueOf(dataSource.get("source_type"));
        if (!"sqlserver".equalsIgnoreCase(sourceType) && !"mssql".equalsIgnoreCase(sourceType)) {
            return Map.of("status", "unsupported", "message", "Only SQL Server OME adapter is supported");
        }

        var dbName = text(database, "OCULAR3_REPORT2");
        var created = 0;
        var updated = 0;
        var skipped = 0;
        var scannedRows = 0;
        var tableResults = new ArrayList<Map<String, Object>>();

        try (var connection = openConnection(dataSource.get("config_json"), dbName)) {
            var configs = loadConfigs(connection);
            var omenTables = loadOmenTables(connection, tableLimit);
            for (var table : omenTables) {
                var tableName = text(table.get("OME_TABLE_NAME"), "");
                var cleanTable = cleanTableName(tableName);
                if (!isAllowedOmenTable(cleanTable)) {
                    skipped++;
                    tableResults.add(Map.of("table", tableName, "status", "skipped", "message", "invalid OME table name"));
                    continue;
                }

                var rows = loadLogRows(connection, cleanTable, rowLimit);
                var tableCreated = 0;
                var tableUpdated = 0;
                for (var row : rows) {
                    scannedRows++;
                    var result = ingestLogRow(dataSource, dbName, cleanTable, row, configs);
                    if ("updated".equals(result.get("action"))) {
                        updated++;
                        tableUpdated++;
                    } else {
                        created++;
                        tableCreated++;
                    }
                }
                var tableResult = new LinkedHashMap<String, Object>();
                tableResult.put("table", cleanTable);
                tableResult.put("rows", rows.size());
                tableResult.put("created", tableCreated);
                tableResult.put("updated", tableUpdated);
                tableResults.add(tableResult);
            }
        } catch (SQLException | IllegalArgumentException ex) {
            var result = new LinkedHashMap<String, Object>();
            result.put("status", "error");
            result.put("message", sanitize(ex.getMessage(), password(dataSource.get("config_json"))));
            result.put("created", created);
            result.put("updated", updated);
            result.put("skipped", skipped);
            result.put("scannedRows", scannedRows);
            result.put("tables", tableResults);
            return result;
        }

        var result = new LinkedHashMap<String, Object>();
        result.put("status", "success");
        result.put("dataSourceId", dataSourceId);
        result.put("database", dbName);
        result.put("created", created);
        result.put("updated", updated);
        result.put("skipped", skipped);
        result.put("scannedRows", scannedRows);
        result.put("tables", tableResults);
        return result;
    }

    public Map<String, Object> restored(long dataSourceId, String database, String mainDatabase, int tableLimit, int rowLimit) {
        var dataSource = jdbcTemplate.queryForMap("""
            select id, name, source_type, config_json
            from data_sources
            where id = ?
            """, dataSourceId);
        var sourceType = String.valueOf(dataSource.get("source_type"));
        if (!"sqlserver".equalsIgnoreCase(sourceType) && !"mssql".equalsIgnoreCase(sourceType)) {
            return Map.of("status", "unsupported", "message", "Only SQL Server OME adapter is supported");
        }

        var reportDb = text(database, "OCULAR3_REPORT2");
        var identityDb = text(mainDatabase, "OCULAR3");
        var restoredRows = new ArrayList<Map<String, Object>>();
        var tableResults = new ArrayList<Map<String, Object>>();
        var skipped = 0;

        try (
            var reportConnection = openConnection(dataSource.get("config_json"), reportDb);
            var identityConnection = openConnection(dataSource.get("config_json"), identityDb)
        ) {
            var configs = loadConfigs(reportConnection);
            var users = loadUsers(identityConnection);
            var agents = loadAgents(identityConnection);
            var omenTables = loadOmenTables(reportConnection, tableLimit);

            for (var table : omenTables) {
                var tableName = text(table.get("OME_TABLE_NAME"), "");
                var cleanTable = cleanTableName(tableName);
                if (!isAllowedOmenTable(cleanTable)) {
                    skipped++;
                    tableResults.add(Map.of("table", tableName, "status", "skipped", "message", "invalid OME table name"));
                    continue;
                }

                var rows = loadLogRows(reportConnection, cleanTable, rowLimit);
                for (var row : rows) {
                    restoredRows.add(restoreLogRow(dataSource, reportDb, cleanTable, row, configs, users, agents));
                }

                var tableResult = new LinkedHashMap<String, Object>();
                tableResult.put("table", cleanTable);
                tableResult.put("rows", rows.size());
                tableResult.put("status", "restored");
                tableResults.add(tableResult);
            }
        } catch (SQLException | IllegalArgumentException ex) {
            var result = new LinkedHashMap<String, Object>();
            result.put("status", "error");
            result.put("message", sanitize(ex.getMessage(), password(dataSource.get("config_json"))));
            result.put("database", reportDb);
            result.put("mainDatabase", identityDb);
            result.put("records", restoredRows);
            result.put("tables", tableResults);
            result.put("skipped", skipped);
            return result;
        }

        var result = new LinkedHashMap<String, Object>();
        result.put("status", "success");
        result.put("dataSourceId", dataSourceId);
        result.put("database", reportDb);
        result.put("mainDatabase", identityDb);
        result.put("records", restoredRows);
        result.put("recordCount", restoredRows.size());
        result.put("tables", tableResults);
        result.put("skipped", skipped);
        return result;
    }

    private Map<String, Object> ingestLogRow(
        Map<String, Object> dataSource,
        String database,
        String tableName,
        Map<String, Object> row,
        Map<String, OmenConfig> configs
    ) {
        var rowId = text(row.get("ID"), "");
        var omeId = text(row.get("OME_ID"), "");
        var typeCode = integer(row.get("OME_TYPE"), 0);
        var tableType = typeFromTable(tableName);
        var config = configs.getOrDefault(omeId.toUpperCase(), OmenConfig.empty());
        var severity = severity(integer(row.get("OME_LEVEL"), 0));
        var label = omenTypeLabel(tableType, typeCode);
        var title = label + "触发预警";
        var policyName = config.policyName(label);
        var occurredAt = occurredAt(row.get("OME_TIME"));

        var detail = new LinkedHashMap<String, Object>();
        detail.put("adapter", "sqlserver-omen");
        detail.put("dataSourceId", dataSource.get("id"));
        detail.put("dataSourceName", dataSource.get("name"));
        detail.put("database", database);
        detail.put("table", tableName);
        detail.put("raw", row);
        detail.put("config", config.asMap());

        return alertIngestService.ingest(new IngestAlertRequest(
            "sqlserver-omen",
            "sqlserver-omen:%s:%s:%s:%s".formatted(dataSource.get("id"), database, tableName, rowId),
            "omen_type_" + (typeCode > 0 ? typeCode : tableType),
            title,
            severity,
            occurredAt,
            "user:" + text(row.get("OME_USR_ID"), "-"),
            "agent:" + text(row.get("OME_AGT_ID"), "-"),
            policyName,
            "external_omen",
            "%s#%s".formatted(tableName, rowId),
            "open",
            detail
        ));
    }

    private Map<String, Object> restoreLogRow(
        Map<String, Object> dataSource,
        String database,
        String tableName,
        Map<String, Object> row,
        Map<String, OmenConfig> configs,
        Map<Integer, Map<String, Object>> users,
        Map<Integer, Map<String, Object>> agents
    ) {
        var restored = new LinkedHashMap<String, Object>();
        var rowId = text(row.get("ID"), "");
        var omeId = text(row.get("OME_ID"), "");
        var typeCode = integer(row.get("OME_TYPE"), 0);
        var tableType = typeFromTable(tableName);
        var label = omenTypeLabel(tableType, typeCode);
        var config = configs.getOrDefault(omeId.toUpperCase(), OmenConfig.empty());
        var userId = integer(row.get("OME_USR_ID"), 0);
        var agentId = integer(row.get("OME_AGT_ID"), 0);
        var user = users.getOrDefault(userId, Map.of());
        var agent = agents.getOrDefault(agentId, Map.of());
        var severity = severity(integer(row.get("OME_LEVEL"), 0));

        restored.put("id", "sqlserver-omen:%s:%s:%s:%s".formatted(dataSource.get("id"), database, tableName, rowId));
        restored.put("sourceSystem", "sqlserver-omen");
        restored.put("database", database);
        restored.put("sourceTable", tableName);
        restored.put("sourceRowId", rowId);
        restored.put("eventTime", occurredAt(row.get("OME_TIME")));
        restored.put("eventDate", text(row.get("OME_DATE"), ""));
        restored.put("eventType", label);
        restored.put("eventTypeCode", typeCode);
        restored.put("title", label + "触发预警");
        restored.put("severity", severity);
        restored.put("severityText", severityLabel(severity));
        restored.put("policyId", omeId);
        restored.put("policyName", config.policyName(label));
        restored.put("policyMemo", config.memo);
        restored.put("timeRange", config.timeRange);
        restored.put("metricCodes", metricCodes(config.xml));
        restored.put("userId", userId > 0 ? userId : null);
        restored.put("userName", firstText(user.get("USR_ALIAS"), user.get("USR_NAME"), userId > 0 ? "user:" + userId : ""));
        restored.put("userGroup", text(user.get("USR_GRP_NAME"), ""));
        restored.put("agentId", agentId > 0 ? agentId : null);
        restored.put("agentName", firstText(agent.get("AGT_ALIAS"), agent.get("AGT_NAME"), agentId > 0 ? "agent:" + agentId : ""));
        restored.put("agentGroup", text(agent.get("AGT_GRP_NAME"), ""));
        restored.put("agentIpMac", text(agent.get("AGT_IP_MAC_STR"), ""));
        restored.put("agentOs", text(agent.get("AGT_OS_STR"), ""));
        restored.put("triggerValue", text(row.get("OME_VALUE"), ""));
        restored.put("triggerDesc", text(row.get("OME_DESC"), ""));
        restored.put("level", integer(row.get("OME_LEVEL"), 0));
        restored.put("raw", row);
        return restored;
    }

    private Map<Integer, Map<String, Object>> loadUsers(Connection connection) {
        try {
            var rows = queryRows(connection, """
                select u.USR_ID, u.USR_ALIAS, u.USR_NAME, u.USR_GRP_ID, g.USR_GRP_NAME
                from dbo.[USER] u
                left join dbo.USER_GROUP g on g.USR_GRP_ID = u.USR_GRP_ID
                """);
            var users = new LinkedHashMap<Integer, Map<String, Object>>();
            for (var row : rows) {
                users.put(integer(row.get("USR_ID"), 0), row);
            }
            return users;
        } catch (SQLException ex) {
            return Map.of();
        }
    }

    private Map<Integer, Map<String, Object>> loadAgents(Connection connection) {
        try {
            var rows = queryRows(connection, """
                select a.AGT_ID, a.AGT_ALIAS, a.AGT_NAME, a.AGT_GRP_ID,
                       g.AGT_GRP_NAME, a.AGT_IP_MAC_STR, a.AGT_OS_STR, a.AGT_STATUS, a.AGT_LAST_USERID
                from dbo.AGENT a
                left join dbo.AGENT_GROUP g on g.AGT_GRP_ID = a.AGT_GRP_ID
                """);
            var agents = new LinkedHashMap<Integer, Map<String, Object>>();
            for (var row : rows) {
                agents.put(integer(row.get("AGT_ID"), 0), row);
            }
            return agents;
        } catch (SQLException ex) {
            return Map.of();
        }
    }

    private Map<String, OmenConfig> loadConfigs(Connection connection) throws SQLException {
        var rows = queryRows(connection, """
            select OME_ID, OME_TYPE, OME_INNER_ID, OME_NAME, OME_CONTENT
            from dbo.OMEN_CONFIG
            """);
        var configs = new LinkedHashMap<String, OmenConfig>();
        for (var row : rows) {
            var id = text(row.get("OME_ID"), "").toUpperCase();
            if (!id.isBlank()) {
                var xml = decodeContent(row.get("OME_CONTENT"));
                configs.put(id, new OmenConfig(
                    id,
                    integer(row.get("OME_TYPE"), 0),
                    text(row.get("OME_NAME"), ""),
                    xml,
                    tag(xml, "MEMO"),
                    tag(xml, "TIMERANGE")
                ));
            }
        }
        return configs;
    }

    private List<Map<String, Object>> loadOmenTables(Connection connection, int limit) throws SQLException {
        var safeLimit = Math.max(1, Math.min(limit, 200));
        return queryRows(connection, """
            select top (?) ID, OME_DATE, OME_TABLE_NAME, OME_RECORD_COUNT, OME_RECORD_MAXID, OME_ID
            from dbo.OMEN_INFO
            where coalesce(OME_RECORD_COUNT, 0) > 0
            order by OME_DATE desc, ID desc
            """, statement -> statement.setInt(1, safeLimit));
    }

    private List<Map<String, Object>> loadLogRows(Connection connection, String tableName, int limit) throws SQLException {
        var safeLimit = Math.max(1, Math.min(limit, 500));
        return queryRows(connection, "select top " + safeLimit + " * from [dbo]." + quoteIdentifier(tableName) + " order by ID");
    }

    private Connection openConnection(Object configValue, String databaseOverride) throws SQLException {
        var config = parseConfig(configValue);
        var host = text(config.get("host"), text(config.get("ip"), ""));
        var port = integer(config.get("port"), 1433);
        var database = text(databaseOverride, text(config.get("database"), "master"));
        var username = text(config.get("username"), "");
        var password = text(config.get("password"), "");
        var encrypt = bool(config.get("encrypt"), false);
        var trustServerCertificate = bool(config.get("trustServerCertificate"), true);

        if (host.isBlank() || username.isBlank()) {
            throw new IllegalArgumentException("SQL Server host and username are required");
        }

        var url = "jdbc:sqlserver://%s:%d;databaseName=%s;encrypt=%s;trustServerCertificate=%s;loginTimeout=5;queryTimeout=30;"
            .formatted(host, port, database.replace(";", ""), encrypt, trustServerCertificate);
        var props = new Properties();
        props.setProperty("user", username);
        props.setProperty("password", password);
        return DriverManager.getConnection(url, props);
    }

    private Map<String, Object> parseConfig(Object configValue) {
        if (configValue == null) {
            return Map.of();
        }
        try {
            var text = configValue instanceof byte[] bytes
                ? new String(bytes, StandardCharsets.UTF_8)
                : configValue.toString();
            Object decoded = objectMapper.readValue(text, Object.class);
            if (decoded instanceof Map<?, ?> map) {
                var result = new LinkedHashMap<String, Object>();
                map.forEach((key, value) -> {
                    if (key != null) {
                        result.put(key.toString(), value);
                    }
                });
                return result;
            }
            if (decoded instanceof String innerJson) {
                return objectMapper.readValue(innerJson, new TypeReference<>() {});
            }
            return Map.of();
        } catch (Exception ex) {
            return Map.of();
        }
    }

    private List<Map<String, Object>> queryRows(Connection connection, String sql) throws SQLException {
        return queryRows(connection, sql, statement -> {});
    }

    private List<Map<String, Object>> queryRows(Connection connection, String sql, StatementBinder binder) throws SQLException {
        try (var statement = connection.prepareStatement(sql)) {
            binder.bind(statement);
            try (var resultSet = statement.executeQuery()) {
                var rows = new ArrayList<Map<String, Object>>();
                var metaData = resultSet.getMetaData();
                while (resultSet.next()) {
                    rows.add(row(resultSet, metaData));
                }
                return rows;
            }
        }
    }

    private Map<String, Object> row(ResultSet resultSet, ResultSetMetaData metaData) throws SQLException {
        var row = new LinkedHashMap<String, Object>();
        for (int i = 1; i <= metaData.getColumnCount(); i++) {
            row.put(metaData.getColumnLabel(i), resultSet.getObject(i));
        }
        return row;
    }

    private String cleanTableName(String value) {
        return text(value, "").replace("[", "").replace("]", "").trim();
    }

    private boolean isAllowedOmenTable(String tableName) {
        return OME_TABLE_PATTERN.matcher(tableName).matches();
    }

    private String quoteIdentifier(String identifier) {
        var value = text(identifier, "");
        if (value.isBlank() || value.contains("]") || !isAllowedOmenTable(value)) {
            throw new IllegalArgumentException("Invalid OME table name");
        }
        return "[" + value + "]";
    }

    private String typeFromTable(String tableName) {
        var parts = tableName.split("_");
        return parts.length > 1 ? parts[1].toLowerCase() : "";
    }

    private String omenTypeLabel(String tableType, int typeCode) {
        return switch (tableType) {
            case "doc" -> "文档风险";
            case "ud" -> "移动存储风险";
            case "mail" -> "邮件外发风险";
            case "prt" -> "打印风险";
            default -> "外部预警 " + typeCode;
        };
    }

    private String severity(int level) {
        if (level >= 30) {
            return "high";
        }
        if (level >= 20) {
            return "medium";
        }
        if (level >= 10) {
            return "low";
        }
        return "info";
    }

    private String severityLabel(String severity) {
        return switch (severity) {
            case "critical" -> "严重";
            case "high" -> "高危";
            case "medium" -> "中危";
            case "low" -> "低危";
            default -> "提示";
        };
    }

    private String occurredAt(Object value) {
        if (value instanceof Timestamp timestamp) {
            return timestamp.toInstant().toString();
        }
        if (value instanceof java.util.Date date) {
            return date.toInstant().toString();
        }
        if (value instanceof Number number) {
            return Instant.ofEpochMilli(number.longValue()).toString();
        }
        return Instant.now().toString();
    }

    private String decodeContent(Object value) {
        if (value == null) {
            return "";
        }
        try {
            var bytes = value instanceof byte[] rawBytes
                ? rawBytes
                : Base64.getDecoder().decode(value.toString());
            return new String(bytes, StandardCharsets.UTF_16LE)
                .replace("\u0000", "")
                .trim();
        } catch (IllegalArgumentException ex) {
            return "";
        }
    }

    private List<String> metricCodes(String xml) {
        var metrics = new ArrayList<String>();
        for (var tagName : List.of("METRIC", "MTR_ID")) {
            var searchFrom = 0;
            var start = "<" + tagName + ">";
            var end = "</" + tagName + ">";
            while (xml != null && searchFrom < xml.length()) {
                var startIndex = xml.indexOf(start, searchFrom);
                if (startIndex < 0) {
                    break;
                }
                var endIndex = xml.indexOf(end, startIndex + start.length());
                if (endIndex < 0) {
                    break;
                }
                var value = xml.substring(startIndex + start.length(), endIndex).trim();
                if (!value.isBlank() && !metrics.contains(value)) {
                    metrics.add(value);
                }
                searchFrom = endIndex + end.length();
            }
        }
        return metrics;
    }

    private String tag(String xml, String name) {
        if (xml == null || xml.isBlank()) {
            return "";
        }
        var start = "<" + name + ">";
        var end = "</" + name + ">";
        var startIndex = xml.indexOf(start);
        var endIndex = xml.indexOf(end);
        if (startIndex < 0 || endIndex <= startIndex) {
            return "";
        }
        return xml.substring(startIndex + start.length(), endIndex).trim();
    }

    private String text(Object value, String fallback) {
        if (value == null) {
            return fallback;
        }
        var text = value.toString().trim();
        return text.isEmpty() ? fallback : text;
    }

    private String firstText(Object... values) {
        for (var value : values) {
            var text = text(value, "");
            if (!text.isBlank()) {
                return text;
            }
        }
        return "";
    }

    private int integer(Object value, int fallback) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value != null) {
            try {
                return Integer.parseInt(value.toString());
            } catch (NumberFormatException ignored) {
                return fallback;
            }
        }
        return fallback;
    }

    private boolean bool(Object value, boolean fallback) {
        if (value instanceof Boolean bool) {
            return bool;
        }
        if (value != null) {
            return Boolean.parseBoolean(value.toString());
        }
        return fallback;
    }

    private String password(Object configValue) {
        return text(parseConfig(configValue).get("password"), "");
    }

    private String sanitize(String message, String password) {
        if (message == null) {
            return "SQL Server OME sync failed";
        }
        if (password == null || password.isBlank()) {
            return message;
        }
        return message.replace(password, "******");
    }

    @FunctionalInterface
    private interface StatementBinder {
        void bind(java.sql.PreparedStatement statement) throws SQLException;
    }

    private record OmenConfig(String id, int type, String name, String xml, String memo, String timeRange) {
        static OmenConfig empty() {
            return new OmenConfig("", 0, "", "", "", "");
        }

        String policyName(String fallback) {
            if (name != null && !name.isBlank() && !"$$".equals(name)) {
                return name;
            }
            if (memo != null && !memo.isBlank()) {
                return fallback + "策略 " + memo;
            }
            return fallback + "策略";
        }

        Map<String, Object> asMap() {
            var result = new LinkedHashMap<String, Object>();
            result.put("id", id);
            result.put("type", type);
            result.put("name", name);
            result.put("memo", memo);
            result.put("timeRange", timeRange);
            return result;
        }
    }
}
