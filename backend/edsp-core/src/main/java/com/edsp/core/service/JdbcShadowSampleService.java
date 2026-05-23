package com.edsp.core.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import org.springframework.stereotype.Service;

@Service
public class JdbcShadowSampleService {
    private final ObjectMapper objectMapper;

    public JdbcShadowSampleService(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public List<Map<String, Object>> sample(
        String sourceType,
        Object configValue,
        String schemaName,
        String tableName,
        List<String> fields,
        int sampleLimit
    ) {
        var normalizedType = normalizeType(sourceType);
        var config = parseConfig(configValue);
        try (var connection = openConnection(normalizedType, config)) {
            connection.setReadOnly(true);
            var sql = sampleSql(normalizedType, schemaName, tableName, fields, sampleLimit);
            try (var statement = connection.createStatement();
                 var rs = statement.executeQuery(sql)) {
                var meta = rs.getMetaData();
                var rows = new ArrayList<Map<String, Object>>();
                while (rs.next()) {
                    var row = new LinkedHashMap<String, Object>();
                    for (var index = 1; index <= meta.getColumnCount(); index++) {
                        row.put(columnLabel(meta, index), rs.getObject(index));
                    }
                    rows.add(row);
                }
                return rows;
            }
        } catch (SQLException ex) {
            throw new IllegalStateException(sanitize(ex.getMessage(), text(config.get("password"), "")), ex);
        }
    }

    private String sampleSql(
        String sourceType,
        String schemaName,
        String tableName,
        List<String> fields,
        int sampleLimit
    ) {
        if (fields == null || fields.isEmpty()) {
            throw new IllegalArgumentException("Shadow sample requires at least one source field");
        }
        var selected = fields.stream()
            .map(field -> quoteIdentifier(sourceType, field))
            .toList();
        var table = qualifiedName(sourceType, schemaName, tableName);
        if ("sqlserver".equals(sourceType)) {
            return "select top (%d) %s from %s".formatted(sampleLimit, String.join(", ", selected), table);
        }
        return "select %s from %s limit %d".formatted(String.join(", ", selected), table, sampleLimit);
    }

    private String qualifiedName(String sourceType, String schemaName, String tableName) {
        var table = quoteIdentifier(sourceType, tableName);
        if (schemaName == null || schemaName.isBlank()) {
            return table;
        }
        return quoteIdentifier(sourceType, schemaName) + "." + table;
    }

    private String quoteIdentifier(String sourceType, String identifier) {
        if (identifier == null || identifier.isBlank()) {
            throw new IllegalArgumentException("Identifier is required");
        }
        if ("sqlserver".equals(sourceType)) {
            return "[" + identifier.replace("]", "]]") + "]";
        }
        return "\"" + identifier.replace("\"", "\"\"") + "\"";
    }

    private Connection openConnection(String sourceType, Map<String, Object> config) throws SQLException {
        var url = text(config.get("url"), "");
        var username = text(config.get("username"), text(config.get("user"), ""));
        var password = text(config.get("password"), "");
        var props = new Properties();
        if (!username.isBlank()) {
            props.setProperty("user", username);
        }
        props.setProperty("password", password);

        if (!url.isBlank()) {
            return DriverManager.getConnection(url, props);
        }

        return switch (sourceType) {
            case "sqlserver" -> DriverManager.getConnection(sqlServerUrl(config), props);
            case "postgresql" -> DriverManager.getConnection(postgresUrl(config), props);
            case "h2" -> DriverManager.getConnection(h2Url(config), props);
            default -> throw new IllegalArgumentException("Shadow sampler is not enabled for source type: " + sourceType);
        };
    }

    private String sqlServerUrl(Map<String, Object> config) {
        var host = text(config.get("host"), text(config.get("ip"), ""));
        var port = integer(config.get("port"), 1433);
        var database = text(config.get("database"), "master");
        var encrypt = bool(config.get("encrypt"), false);
        var trustServerCertificate = bool(config.get("trustServerCertificate"), true);
        if (host.isBlank()) {
            throw new IllegalArgumentException("SQL Server host is required");
        }
        return "jdbc:sqlserver://%s:%d;databaseName=%s;encrypt=%s;trustServerCertificate=%s;loginTimeout=5;queryTimeout=30;"
            .formatted(host, port, database.replace(";", ""), encrypt, trustServerCertificate);
    }

    private String postgresUrl(Map<String, Object> config) {
        var host = text(config.get("host"), text(config.get("ip"), ""));
        var port = integer(config.get("port"), 5432);
        var database = text(config.get("database"), "postgres");
        if (host.isBlank()) {
            throw new IllegalArgumentException("PostgreSQL host is required");
        }
        return "jdbc:postgresql://%s:%d/%s".formatted(host, port, database.replace("/", ""));
    }

    private String h2Url(Map<String, Object> config) {
        var url = text(config.get("jdbcUrl"), text(config.get("database"), ""));
        if (url.startsWith("jdbc:h2:")) {
            return url;
        }
        throw new IllegalArgumentException("H2 url is required");
    }

    private String columnLabel(ResultSetMetaData meta, int index) throws SQLException {
        var label = meta.getColumnLabel(index);
        if (label == null || label.isBlank()) {
            return meta.getColumnName(index);
        }
        return label;
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

    private String normalizeType(String sourceType) {
        var type = text(sourceType, "").toLowerCase(Locale.ROOT);
        return switch (type) {
            case "mssql" -> "sqlserver";
            case "postgres" -> "postgresql";
            default -> type;
        };
    }

    private String text(Object value, String fallback) {
        if (value == null) {
            return fallback;
        }
        var text = value.toString().trim();
        return text.isEmpty() || "null".equalsIgnoreCase(text) ? fallback : text;
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

    private String sanitize(String message, String password) {
        if (message == null) {
            return "Shadow sample failed";
        }
        if (password == null || password.isBlank()) {
            return message;
        }
        return message.replace(password, "******");
    }
}
