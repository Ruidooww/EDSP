package com.edsp.core.service;

import com.edsp.core.dto.DataSourceRequest;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import org.springframework.stereotype.Service;

@Service
public class SqlServerMetadataService {
    private final ObjectMapper objectMapper;

    public SqlServerMetadataService(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public Map<String, Object> test(DataSourceRequest request) {
        return test(request.sourceType(), request.configJson());
    }

    public Map<String, Object> test(String sourceType, Object configValue) {
        if (!"sqlserver".equalsIgnoreCase(sourceType) && !"mssql".equalsIgnoreCase(sourceType)) {
            return Map.of(
                "status", "unsupported",
                "message", "Only SQL Server connector is implemented in this phase"
            );
        }

        var result = new LinkedHashMap<String, Object>();

        try (var connection = openConnection(sourceType, configValue, "")) {
            result.putAll(connectionInfo(sourceType, configValue, ""));
            result.put("status", "active");
            result.put("message", "SQL Server connection succeeded");
            result.put("productName", connection.getMetaData().getDatabaseProductName());
            result.put("productVersion", connection.getMetaData().getDatabaseProductVersion());
            result.put("currentDatabase", singleString(connection, "select db_name()"));
            result.put("databases", stringList(connection, """
                select name
                from sys.databases
                where state_desc = 'ONLINE'
                order by name
                """, 500));
            result.put("tables", stringList(connection, """
                select top 30 concat(table_schema, '.', table_name)
                from information_schema.tables
                where table_type = 'BASE TABLE'
                order by table_schema, table_name
                """, 30));
        } catch (SQLException | IllegalArgumentException ex) {
            result.put("status", "error");
            result.put("message", sanitize(ex.getMessage(), password(configValue)));
        }

        return result;
    }

    public Map<String, Object> tables(String sourceType, Object configValue, String database, String keyword, int limit) {
        var result = new LinkedHashMap<String, Object>();
        try (var connection = openConnection(sourceType, configValue, database)) {
            var resolvedDatabase = singleString(connection, "select db_name()");
            result.put("status", "active");
            result.put("database", resolvedDatabase);
            result.put("keyword", text(keyword, ""));
            result.put("tables", queryRows(connection, """
                select top (?) s.name as schema_name,
                       t.name as table_name,
                       cast(coalesce(sum(case when p.index_id in (0, 1) then p.rows else 0 end), 0) as bigint) as row_count,
                       t.create_date,
                       t.modify_date
                from sys.tables t
                join sys.schemas s on s.schema_id = t.schema_id
                left join sys.partitions p on p.object_id = t.object_id
                where (? = '' or lower(s.name) like ? or lower(t.name) like ?)
                group by s.name, t.name, t.create_date, t.modify_date
                order by row_count desc, s.name, t.name
                """, statement -> {
                    var safeKeyword = "%" + text(keyword, "").toLowerCase() + "%";
                    statement.setInt(1, Math.max(1, Math.min(limit, 500)));
                    statement.setString(2, text(keyword, "").toLowerCase());
                    statement.setString(3, safeKeyword);
                    statement.setString(4, safeKeyword);
                }));
        } catch (SQLException | IllegalArgumentException ex) {
            result.put("status", "error");
            result.put("message", sanitize(ex.getMessage(), password(configValue)));
        }
        return result;
    }

    public Map<String, Object> columns(String sourceType, Object configValue, String database, String schema, String table) {
        var result = new LinkedHashMap<String, Object>();
        try (var connection = openConnection(sourceType, configValue, database)) {
            result.put("status", "active");
            result.put("database", singleString(connection, "select db_name()"));
            result.put("schema", schema);
            result.put("table", table);
            result.put("columns", queryRows(connection, """
                select c.column_id,
                       c.name as column_name,
                       ty.name as data_type,
                       c.max_length,
                       c.precision,
                       c.scale,
                       c.is_nullable
                from sys.columns c
                join sys.tables t on t.object_id = c.object_id
                join sys.schemas s on s.schema_id = t.schema_id
                join sys.types ty on ty.user_type_id = c.user_type_id
                where s.name = ? and t.name = ?
                order by c.column_id
                """, statement -> {
                    statement.setString(1, schema);
                    statement.setString(2, table);
                }));
        } catch (SQLException | IllegalArgumentException ex) {
            result.put("status", "error");
            result.put("message", sanitize(ex.getMessage(), password(configValue)));
        }
        return result;
    }

    public Map<String, Object> sample(String sourceType, Object configValue, String database, String schema, String table, int limit) {
        var result = new LinkedHashMap<String, Object>();
        try (var connection = openConnection(sourceType, configValue, database)) {
            var safeLimit = Math.max(1, Math.min(limit, 50));
            var sql = "select top " + safeLimit + " * from " + quoteIdentifier(schema) + "." + quoteIdentifier(table);
            result.put("status", "active");
            result.put("database", singleString(connection, "select db_name()"));
            result.put("schema", schema);
            result.put("table", table);
            result.put("rows", queryRows(connection, sql, statement -> {}));
        } catch (SQLException | IllegalArgumentException ex) {
            result.put("status", "error");
            result.put("message", sanitize(ex.getMessage(), password(configValue)));
        }
        return result;
    }

    private Connection openConnection(String sourceType, Object configValue, String databaseOverride) throws SQLException {
        if (!"sqlserver".equalsIgnoreCase(sourceType) && !"mssql".equalsIgnoreCase(sourceType)) {
            throw new IllegalArgumentException("Only SQL Server connector is implemented in this phase");
        }

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

        var url = "jdbc:sqlserver://%s:%d;databaseName=%s;encrypt=%s;trustServerCertificate=%s;loginTimeout=5;queryTimeout=20;"
            .formatted(host, port, database.replace(";", ""), encrypt, trustServerCertificate);
        var props = new Properties();
        props.setProperty("user", username);
        props.setProperty("password", password);
        return DriverManager.getConnection(url, props);
    }

    private Map<String, Object> connectionInfo(String sourceType, Object configValue, String databaseOverride) {
        var config = parseConfig(configValue);
        var info = new LinkedHashMap<String, Object>();
        info.put("host", text(config.get("host"), text(config.get("ip"), "")));
        info.put("port", integer(config.get("port"), 1433));
        info.put("database", text(databaseOverride, text(config.get("database"), "master")));
        return info;
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

    private String singleString(Connection connection, String sql) throws SQLException {
        try (var statement = connection.createStatement();
             var resultSet = statement.executeQuery(sql)) {
            if (resultSet.next()) {
                return resultSet.getString(1);
            }
            return "";
        }
    }

    private ArrayList<String> stringList(Connection connection, String sql, int limit) throws SQLException {
        var values = new ArrayList<String>();
        try (var statement = connection.createStatement();
             var resultSet = statement.executeQuery(sql)) {
            while (resultSet.next() && values.size() < limit) {
                values.add(resultSet.getString(1));
            }
        }
        return values;
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

    private String text(Object value, String fallback) {
        if (value == null) {
            return fallback;
        }
        var text = value.toString().trim();
        return text.isEmpty() ? fallback : text;
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
            return "SQL Server connection failed";
        }
        if (password == null || password.isBlank()) {
            return message;
        }
        return message.replace(password, "******");
    }

    private String quoteIdentifier(String identifier) {
        var value = text(identifier, "");
        if (value.isBlank() || value.contains("]")) {
            throw new IllegalArgumentException("Invalid SQL Server identifier");
        }
        return "[" + value + "]";
    }

    private String password(Object configValue) {
        return text(parseConfig(configValue).get("password"), "");
    }

    @FunctionalInterface
    private interface StatementBinder {
        void bind(PreparedStatement statement) throws SQLException;
    }
}
