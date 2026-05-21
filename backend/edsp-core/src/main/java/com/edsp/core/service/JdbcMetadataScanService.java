package com.edsp.core.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import org.springframework.stereotype.Service;

@Service
public class JdbcMetadataScanService {
    private final ObjectMapper objectMapper;

    public JdbcMetadataScanService(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public MetadataScanResult scan(
        String sourceType,
        Object configValue,
        String databaseOverride,
        int tableLimit,
        int fieldLimit,
        boolean includeViews
    ) {
        var normalizedType = normalizeType(sourceType);
        var config = parseConfig(configValue);
        try (var connection = openConnection(normalizedType, config, databaseOverride)) {
            if ("sqlserver".equals(normalizedType)) {
                return scanSqlServer(connection, tableLimit, fieldLimit, includeViews);
            }
            return scanGeneric(connection, tableLimit, fieldLimit, includeViews);
        } catch (SQLException ex) {
            throw new IllegalStateException(sanitize(ex.getMessage(), text(config.get("password"), "")), ex);
        }
    }

    private MetadataScanResult scanSqlServer(
        Connection connection,
        int tableLimit,
        int fieldLimit,
        boolean includeViews
    ) throws SQLException {
        var databaseName = singleString(connection, "select db_name()");
        var tableSql = includeViews ? """
            select top (?) s.name as schema_name,
                   o.name as table_name,
                   lower(o.type_desc) as table_type,
                   cast(coalesce(sum(case when p.index_id in (0, 1) then p.rows else 0 end), 0) as bigint) as row_count,
                   o.modify_date
            from sys.objects o
            join sys.schemas s on s.schema_id = o.schema_id
            left join sys.partitions p on p.object_id = o.object_id
            where o.type in ('U', 'V') and o.is_ms_shipped = 0
            group by s.name, o.name, o.type_desc, o.modify_date
            order by row_count desc, s.name, o.name
            """ : """
            select top (?) s.name as schema_name,
                   t.name as table_name,
                   'table' as table_type,
                   cast(coalesce(sum(case when p.index_id in (0, 1) then p.rows else 0 end), 0) as bigint) as row_count,
                   t.modify_date
            from sys.tables t
            join sys.schemas s on s.schema_id = t.schema_id
            left join sys.partitions p on p.object_id = t.object_id
            where t.is_ms_shipped = 0
            group by s.name, t.name, t.modify_date
            order by row_count desc, s.name, t.name
            """;

        var tables = new ArrayList<MetadataTable>();
        try (var statement = connection.prepareStatement(tableSql)) {
            statement.setInt(1, Math.max(1, tableLimit));
            try (var rs = statement.executeQuery()) {
                while (rs.next()) {
                    var schemaName = rs.getString("schema_name");
                    var tableName = rs.getString("table_name");
                    tables.add(new MetadataTable(
                        databaseName,
                        schemaName,
                        tableName,
                        normalizeTableType(rs.getString("table_type")),
                        longValue(rs.getObject("row_count")),
                        offsetDateTime(rs.getObject("modify_date")),
                        scanSqlServerColumns(connection, schemaName, tableName, fieldLimit)
                    ));
                }
            }
        }
        return new MetadataScanResult(databaseName, tables, 0);
    }

    private List<MetadataField> scanSqlServerColumns(
        Connection connection,
        String schemaName,
        String tableName,
        int fieldLimit
    ) throws SQLException {
        var fields = new ArrayList<MetadataField>();
        try (var statement = connection.prepareStatement("""
            select top (?) c.column_id,
                   c.name as column_name,
                   ty.name as data_type,
                   c.max_length,
                   c.precision,
                   c.scale,
                   c.is_nullable
            from sys.columns c
            join sys.objects o on o.object_id = c.object_id
            join sys.schemas s on s.schema_id = o.schema_id
            join sys.types ty on ty.user_type_id = c.user_type_id
            where s.name = ? and o.name = ?
            order by c.column_id
            """)) {
            statement.setInt(1, Math.max(1, fieldLimit));
            statement.setString(2, schemaName);
            statement.setString(3, tableName);
            try (var rs = statement.executeQuery()) {
                while (rs.next()) {
                    fields.add(new MetadataField(
                        rs.getString("column_name"),
                        sqlServerType(rs.getString("data_type"), rs.getObject("max_length"),
                            rs.getObject("precision"), rs.getObject("scale")),
                        rs.getBoolean("is_nullable"),
                        rs.getInt("column_id")
                    ));
                }
            }
        }
        return fields;
    }

    private MetadataScanResult scanGeneric(
        Connection connection,
        int tableLimit,
        int fieldLimit,
        boolean includeViews
    ) throws SQLException {
        var metaData = connection.getMetaData();
        var databaseName = firstNonBlank(connection.getCatalog(), connection.getSchema(), metaData.getDatabaseProductName());
        var tableTypes = includeViews ? new String[] {"TABLE", "VIEW"} : new String[] {"TABLE"};
        var tables = new ArrayList<MetadataTable>();

        try (var rs = metaData.getTables(connection.getCatalog(), null, "%", tableTypes)) {
            while (rs.next() && tables.size() < tableLimit) {
                var schemaName = rs.getString("TABLE_SCHEM");
                var tableName = rs.getString("TABLE_NAME");
                if (skipSchema(schemaName) || tableName == null || tableName.isBlank()) {
                    continue;
                }
                tables.add(new MetadataTable(
                    databaseName,
                    schemaName,
                    tableName,
                    normalizeTableType(rs.getString("TABLE_TYPE")),
                    null,
                    null,
                    scanGenericColumns(metaData, connection.getCatalog(), schemaName, tableName, fieldLimit)
                ));
            }
        }
        return new MetadataScanResult(databaseName, tables, 0);
    }

    private List<MetadataField> scanGenericColumns(
        DatabaseMetaData metaData,
        String catalog,
        String schemaName,
        String tableName,
        int fieldLimit
    ) throws SQLException {
        var fields = new ArrayList<MetadataField>();
        try (var rs = metaData.getColumns(catalog, schemaName, tableName, "%")) {
            while (rs.next() && fields.size() < fieldLimit) {
                fields.add(new MetadataField(
                    rs.getString("COLUMN_NAME"),
                    genericType(rs),
                    rs.getInt("NULLABLE") != DatabaseMetaData.columnNoNulls,
                    rs.getInt("ORDINAL_POSITION")
                ));
            }
        }
        return fields;
    }

    private Connection openConnection(String sourceType, Map<String, Object> config, String databaseOverride) throws SQLException {
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
            case "sqlserver" -> DriverManager.getConnection(sqlServerUrl(config, databaseOverride), props);
            case "postgresql" -> DriverManager.getConnection(postgresUrl(config, databaseOverride), props);
            case "h2" -> DriverManager.getConnection(h2Url(config), props);
            default -> throw new IllegalArgumentException("Metadata scanner is not enabled for source type: " + sourceType);
        };
    }

    private String sqlServerUrl(Map<String, Object> config, String databaseOverride) {
        var host = text(config.get("host"), text(config.get("ip"), ""));
        var port = integer(config.get("port"), 1433);
        var database = text(databaseOverride, text(config.get("database"), "master"));
        var encrypt = bool(config.get("encrypt"), false);
        var trustServerCertificate = bool(config.get("trustServerCertificate"), true);
        if (host.isBlank()) {
            throw new IllegalArgumentException("SQL Server host is required");
        }
        return "jdbc:sqlserver://%s:%d;databaseName=%s;encrypt=%s;trustServerCertificate=%s;loginTimeout=5;queryTimeout=30;"
            .formatted(host, port, database.replace(";", ""), encrypt, trustServerCertificate);
    }

    private String postgresUrl(Map<String, Object> config, String databaseOverride) {
        var host = text(config.get("host"), text(config.get("ip"), ""));
        var port = integer(config.get("port"), 5432);
        var database = text(databaseOverride, text(config.get("database"), "postgres"));
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

    private String singleString(Connection connection, String sql) throws SQLException {
        try (var statement = connection.createStatement();
             var rs = statement.executeQuery(sql)) {
            return rs.next() ? rs.getString(1) : "";
        }
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

    private String normalizeTableType(String tableType) {
        var value = text(tableType, "table").toLowerCase(Locale.ROOT);
        if (value.contains("view")) {
            return "view";
        }
        return "table";
    }

    private boolean skipSchema(String schemaName) {
        var schema = text(schemaName, "").toLowerCase(Locale.ROOT);
        return schema.equals("information_schema")
            || schema.equals("pg_catalog")
            || schema.equals("sys")
            || schema.startsWith("pg_toast");
    }

    private String sqlServerType(String dataType, Object lengthValue, Object precisionValue, Object scaleValue) {
        var type = text(dataType, "unknown");
        var length = integer(lengthValue, -1);
        var precision = integer(precisionValue, -1);
        var scale = integer(scaleValue, -1);
        if ((type.equalsIgnoreCase("varchar") || type.equalsIgnoreCase("nvarchar")
            || type.equalsIgnoreCase("char") || type.equalsIgnoreCase("nchar")) && length > 0) {
            var adjustedLength = type.toLowerCase(Locale.ROOT).startsWith("n") ? length / 2 : length;
            return type + "(" + adjustedLength + ")";
        }
        if ((type.equalsIgnoreCase("decimal") || type.equalsIgnoreCase("numeric")) && precision > 0) {
            return type + "(" + precision + "," + Math.max(0, scale) + ")";
        }
        return type;
    }

    private String genericType(ResultSet rs) throws SQLException {
        var type = text(rs.getString("TYPE_NAME"), "unknown");
        var size = rs.getInt("COLUMN_SIZE");
        var decimalDigits = rs.getInt("DECIMAL_DIGITS");
        if ((type.equalsIgnoreCase("varchar") || type.equalsIgnoreCase("char")) && size > 0) {
            return type + "(" + size + ")";
        }
        if ((type.equalsIgnoreCase("numeric") || type.equalsIgnoreCase("decimal")) && size > 0) {
            return type + "(" + size + "," + Math.max(0, decimalDigits) + ")";
        }
        return type;
    }

    private OffsetDateTime offsetDateTime(Object value) {
        if (value instanceof OffsetDateTime offsetDateTime) {
            return offsetDateTime;
        }
        if (value instanceof Timestamp timestamp) {
            return timestamp.toInstant().atZone(ZoneId.systemDefault()).toOffsetDateTime();
        }
        return null;
    }

    private Long longValue(Object value) {
        return value instanceof Number number ? number.longValue() : null;
    }

    private String firstNonBlank(String... values) {
        for (var value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return "";
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
            return "Metadata scan failed";
        }
        if (password == null || password.isBlank()) {
            return message;
        }
        return message.replace(password, "******");
    }

    public record MetadataScanResult(
        String databaseName,
        List<MetadataTable> tables,
        int failedTables
    ) {
        public int tableCount() {
            return tables.size();
        }

        public int fieldCount() {
            return tables.stream().mapToInt(table -> table.fields().size()).sum();
        }
    }

    public record MetadataTable(
        String databaseName,
        String schemaName,
        String tableName,
        String tableType,
        Long rowCount,
        OffsetDateTime sourceUpdatedAt,
        List<MetadataField> fields
    ) {
    }

    public record MetadataField(
        String fieldName,
        String fieldType,
        boolean nullable,
        int ordinalPosition
    ) {
    }
}
