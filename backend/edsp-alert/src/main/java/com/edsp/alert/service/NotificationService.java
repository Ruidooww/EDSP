package com.edsp.alert.service;

import com.edsp.alert.dto.NotificationChannelRequest;
import com.edsp.alert.dto.NotificationSendRequest;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class NotificationService {
    private static final String FEISHU_HOST = "open.feishu.cn";
    private static final String FEISHU_PATH_PREFIX = "/open-apis/bot/v2/hook/";
    private static final NotificationSecretSanitizer SECRET_SANITIZER = new NotificationSecretSanitizer();

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final NotificationSecretStore secretStore;
    private final TransactionTemplate transactionTemplate;

    public NotificationService(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this(jdbcTemplate, objectMapper, new NotificationSecretStore(""), (PlatformTransactionManager) null);
    }

    @Autowired
    public NotificationService(
        JdbcTemplate jdbcTemplate,
        ObjectMapper objectMapper,
        NotificationSecretStore secretStore,
        ObjectProvider<PlatformTransactionManager> transactionManagerProvider
    ) {
        this(jdbcTemplate, objectMapper, secretStore, transactionManagerProvider.getIfAvailable());
    }

    public NotificationService(
        JdbcTemplate jdbcTemplate,
        ObjectMapper objectMapper,
        NotificationSecretStore secretStore
    ) {
        this(jdbcTemplate, objectMapper, secretStore, (PlatformTransactionManager) null);
    }

    public NotificationService(
        JdbcTemplate jdbcTemplate,
        ObjectMapper objectMapper,
        NotificationSecretStore secretStore,
        PlatformTransactionManager transactionManager
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
        this.secretStore = secretStore;
        this.transactionTemplate = transactionManager == null ? null : requiresNewTemplate(transactionManager);
    }

    public List<Map<String, Object>> listChannels() {
        return listChannels(null, null);
    }

    public List<Map<String, Object>> listChannels(String secretStorageStatus, String enabled) {
        var normalizedSecretStorageStatus = normalizeSecretStorageStatus(secretStorageStatus);
        var normalizedEnabled = normalizeEnabledFilter(enabled);
        var filters = new ArrayList<String>();
        var args = new ArrayList<Object>();

        if (normalizedSecretStorageStatus != null) {
            filters.add("secret_storage_status = ?");
            args.add(normalizedSecretStorageStatus);
        }
        if (normalizedEnabled != null) {
            filters.add("enabled = ?");
            args.add(normalizedEnabled);
        }

        var sql = new StringBuilder("""
            select id, name, channel_type, endpoint_url, endpoint_masked, secret_storage_status,
                   description, enabled, status,
                   last_test_status, last_test_message, last_test_at, created_at, updated_at
            from notification_channels
            """);
        if (!filters.isEmpty()) {
            sql.append("where ").append(String.join(" and ", filters)).append("\n");
        }
        sql.append("""
            order by updated_at desc
            """);
        return jdbcTemplate.queryForList(sql.toString(), args.toArray()).stream()
            .map(this::presentChannel)
            .toList();
    }

    public Map<String, Object> secretBackfillDryRun(String enabled, String channelType, String limit) {
        var normalizedEnabled = normalizeEnabledFilter(enabled);
        var normalizedChannelType = normalizeDryRunChannelTypeFilter(channelType);
        var safeLimit = normalizeDryRunLimit(limit);
        var filters = new ArrayList<String>();
        var args = new ArrayList<Object>();

        if (normalizedEnabled != null) {
            filters.add("enabled = ?");
            args.add(normalizedEnabled);
        }
        if (normalizedChannelType != null) {
            filters.add("channel_type = ?");
            args.add(normalizedChannelType);
        }

        var sql = new StringBuilder("""
            select id, name, channel_type, endpoint_url, endpoint_masked,
                   secret_storage_status, enabled, updated_at
            from notification_channels
            """);
        if (!filters.isEmpty()) {
            sql.append("where ").append(String.join(" and ", filters)).append("\n");
        }
        sql.append("""
            order by updated_at desc, id desc
            """);

        var rows = jdbcTemplate.queryForList(sql.toString(), args.toArray());
        var summary = dryRunSummary();
        var blockReasons = dryRunBlockReasons();
        var items = new ArrayList<Map<String, Object>>();

        for (var row : rows) {
            var item = dryRunItem(row);
            updateDryRunSummary(summary, blockReasons, item);
            if (items.size() < safeLimit) {
                items.add(item);
            }
        }

        var result = new LinkedHashMap<String, Object>();
        result.put("summary", summary);
        result.put("blockReasons", blockReasons);
        result.put("limit", safeLimit);
        result.put("truncated", rows.size() > safeLimit);
        result.put("items", items);
        return result;
    }

    public Map<String, Object> executeSecretBackfill(Map<String, Object> request) {
        var channelIds = normalizeBackfillChannelIds(request.get("channelIds"));
        var confirmation = stringOrBlank(request.get("confirmation"));
        if (!"EXECUTE_NOTIFICATION_SECRET_BACKFILL".equals(confirmation)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid_confirmation");
        }
        secretStore.requireWritableMasterKey();

        var requestedBy = normalizeRequestedBy(request.get("requestedBy"));
        var runId = createBackfillRun(channelIds.size(), requestedBy);
        var eligibleCount = 0;
        var migratedCount = 0;
        var skippedCount = 0;
        var failedCount = 0;
        String globalFailureReason = null;

        try {
            for (var channelId : channelIds) {
                var item = processBackfillChannel(runId, channelId);
                if (item.eligible()) {
                    eligibleCount++;
                }
                if ("migrated".equals(item.itemStatus())) {
                    migratedCount++;
                } else if ("skipped".equals(item.itemStatus())) {
                    skippedCount++;
                } else if ("failed".equals(item.itemStatus())) {
                    failedCount++;
                }
            }
            var finalStatus = failedCount > 0 ? "completed_with_failures" : "completed";
            finalizeBackfillRun(
                runId,
                finalStatus,
                eligibleCount,
                migratedCount,
                skippedCount,
                failedCount,
                null
            );
        } catch (RuntimeException ex) {
            globalFailureReason = "unexpected_error";
            finalizeBackfillRun(
                runId,
                "failed",
                eligibleCount,
                migratedCount,
                skippedCount,
                failedCount,
                globalFailureReason
            );
            throw ex;
        }

        var result = backfillRunRow(runId);
        if (globalFailureReason != null) {
            result.put("failure_reason", globalFailureReason);
        }
        return result;
    }

    public Map<String, Object> listSecretBackfillRuns(String status, String limit) {
        var normalizedStatus = normalizeBackfillRunStatus(status);
        var safeLimit = normalizeBackfillRunLimit(limit);
        var filters = new ArrayList<String>();
        var args = new ArrayList<Object>();
        if (normalizedStatus != null) {
            filters.add("status = ?");
            args.add(normalizedStatus);
        }

        var sql = new StringBuilder("""
            select id, mode, status, confirmation_accepted, requested_by,
                   requested_at, started_at, completed_at,
                   total_requested, eligible_count, migrated_count, skipped_count,
                   failed_count, failure_reason, created_at, updated_at
            from notification_secret_backfill_runs
            """);
        if (!filters.isEmpty()) {
            sql.append("where ").append(String.join(" and ", filters)).append("\n");
        }
        sql.append("""
            order by created_at desc, id desc
            limit ?
            """);
        args.add(safeLimit);

        var result = new LinkedHashMap<String, Object>();
        result.put("limit", safeLimit);
        result.put("items", jdbcTemplate.queryForList(sql.toString(), args.toArray()).stream()
            .map(this::presentBackfillRun)
            .toList());
        return result;
    }

    public Map<String, Object> secretBackfillRunDetail(long id) {
        var rows = jdbcTemplate.queryForList("""
            select id, mode, status, confirmation_accepted, requested_by,
                   requested_at, started_at, completed_at,
                   total_requested, eligible_count, migrated_count, skipped_count,
                   failed_count, failure_reason, created_at, updated_at
            from notification_secret_backfill_runs
            where id = ?
            """, id);
        if (rows.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "backfill_run_not_found");
        }
        var run = presentBackfillRun(rows.get(0));
        run.put("items", jdbcTemplate.queryForList("""
            select id, run_id, channel_id, channel_type, before_secret_storage_status,
                   after_secret_storage_status, endpoint_masked, item_status,
                   failure_reason, created_at, updated_at
            from notification_secret_backfill_items
            where run_id = ?
            order by id
            """, id).stream()
            .map(this::presentBackfillItem)
            .toList());
        return run;
    }

    public List<Map<String, Object>> listDeliveries(int limit) {
        return listDeliveries(limit, null);
    }

    public List<Map<String, Object>> listDeliveries(int limit, Long alertId) {
        return listDeliveries(limit, alertId, null, null, null);
    }

    public List<Map<String, Object>> listDeliveries(
        int limit,
        Long alertId,
        String status,
        String channelType,
        Long channelId
    ) {
        var safeLimit = Math.max(1, Math.min(limit, 200));
        var normalizedStatus = normalizeDeliveryStatus(status);
        var normalizedChannelType = normalizeOptionalChannelType(channelType);
        var filters = new ArrayList<String>();
        var args = new ArrayList<Object>();

        if (alertId != null) {
            filters.add("d.alert_id = ?");
            args.add(alertId);
        }
        if (normalizedStatus != null) {
            filters.add("d.status = ?");
            args.add(normalizedStatus);
        }
        if (normalizedChannelType != null) {
            filters.add("c.channel_type = ?");
            args.add(normalizedChannelType);
        }
        if (channelId != null) {
            filters.add("d.channel_id = ?");
            args.add(channelId);
        }

        var sql = new StringBuilder("""
            select d.id, d.channel_id, c.name as channel_name, c.channel_type,
                   d.alert_id, a.title as alert_title, c.endpoint_url,
                   d.title, d.severity, d.status, d.response_code, d.response_body,
                   cast(d.payload_json as varchar) as payload_json,
                   d.failure_type, d.failure_reason, d.retryable,
                   d.retry_of_delivery_id, d.retry_count,
                   d.created_at
            from notification_deliveries d
            left join notification_channels c on c.id = d.channel_id
            left join alerts a on a.id = d.alert_id
            """);
        if (!filters.isEmpty()) {
            sql.append("where ").append(String.join(" and ", filters)).append("\n");
        }
        sql.append("""
            order by d.created_at desc
            limit ?
            """);
        args.add(safeLimit);
        return jdbcTemplate.queryForList(sql.toString(), args.toArray()).stream()
            .map(this::presentDelivery)
            .toList();
    }

    public Map<String, Object> createChannel(NotificationChannelRequest request) {
        var channelType = normalizeType(request.channelType());
        var endpointUrl = normalizeEndpoint(channelType, request.webhookUrl());
        var storedEndpoint = secretStore.storeEndpoint(endpointUrl, SECRET_SANITIZER.maskEndpoint(endpointUrl));
        var keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            var statement = connection.prepareStatement("""
                insert into notification_channels(
                    name, channel_type, endpoint_url, endpoint_secret_ciphertext,
                    endpoint_secret_key_version, endpoint_masked, secret_storage_status,
                    description, config_json, enabled, status
                )
                values (?, ?, null, ?, ?, ?, ?, ?, cast(? as jsonb), ?, ?)
                """, new String[] {"id"});
            statement.setString(1, request.name());
            statement.setString(2, channelType);
            statement.setString(3, storedEndpoint.ciphertext());
            statement.setString(4, storedEndpoint.keyVersion());
            statement.setString(5, storedEndpoint.endpointMasked());
            statement.setString(6, storedEndpoint.status());
            statement.setString(7, request.description());
            statement.setString(8, configJson(request));
            statement.setBoolean(9, request.enabled());
            statement.setString(10, request.enabled() ? "ready" : "disabled");
            return statement;
        }, keyHolder);
        var idValue = keyHolder.getKey();
        var id = idValue == null ? 0 : idValue.longValue();
        return Map.of("id", id);
    }

    public Map<String, Object> updateChannel(long id, NotificationChannelRequest request) {
        return updateChannel(id, request, Set.of("name", "channelType", "webhookUrl", "description", "enabled", "config"));
    }

    public Map<String, Object> updateChannel(
        long id,
        NotificationChannelRequest request,
        Set<String> presentFields
    ) {
        var fields = presentFields == null ? Set.<String>of() : new HashSet<>(presentFields);
        var current = currentChannel(id);
        var currentChannelType = normalizeType(stringOrBlank(current.get("channel_type")));
        var requestedChannelType = fields.contains("channelType")
            ? normalizeType(request.channelType())
            : currentChannelType;
        if (!requestedChannelType.equals(currentChannelType)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "channel_type_immutable");
        }

        var finalName = fields.contains("name") ? normalizeName(request.name()) : stringOrBlank(current.get("name"));
        var finalDescription = fields.contains("description")
            ? request.description()
            : stringOrNull(current.get("description"));
        var finalEnabled = fields.contains("enabled") ? request.enabled() : booleanValue(current.get("enabled"));
        var endpointProvided = fields.contains("webhookUrl") || fields.contains("endpointUrl");
        var newEndpoint = endpointProvided && request.webhookUrl() != null;

        String endpointUrl;
        String endpointSecretCiphertext;
        String endpointSecretKeyVersion;
        String endpointMasked;
        String secretStorageStatus;
        String configJson;

        if (newEndpoint) {
            var normalizedEndpoint = normalizeEndpoint(currentChannelType, request.webhookUrl());
            var storedEndpoint = secretStore.storeEndpoint(
                normalizedEndpoint,
                SECRET_SANITIZER.maskEndpoint(normalizedEndpoint)
            );
            endpointUrl = null;
            endpointSecretCiphertext = storedEndpoint.ciphertext();
            endpointSecretKeyVersion = storedEndpoint.keyVersion();
            endpointMasked = storedEndpoint.endpointMasked();
            secretStorageStatus = storedEndpoint.status();
            configJson = fields.contains("config")
                ? configJson(request, normalizedEndpoint)
                : stringOrBlank(current.get("config_json"));
        } else {
            endpointUrl = stringOrNull(current.get("endpoint_url"));
            endpointSecretCiphertext = stringOrNull(current.get("endpoint_secret_ciphertext"));
            endpointSecretKeyVersion = stringOrNull(current.get("endpoint_secret_key_version"));
            endpointMasked = stringOrNull(current.get("endpoint_masked"));
            secretStorageStatus = stringOrBlank(current.get("secret_storage_status"));
            if (secretStorageStatus.isBlank()) {
                secretStorageStatus = "legacy_plaintext";
            }
            if ("missing".equals(secretStorageStatus) && finalEnabled) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "notification_secret_unavailable");
            }
            configJson = fields.contains("config")
                ? configJson(request, endpointUrl)
                : stringOrBlank(current.get("config_json"));
        }

        jdbcTemplate.update("""
            update notification_channels
            set name = ?, channel_type = ?, endpoint_url = ?,
                endpoint_secret_ciphertext = ?, endpoint_secret_key_version = ?,
                endpoint_masked = ?, secret_storage_status = ?, description = ?,
                config_json = cast(? as jsonb), enabled = ?, status = ?, updated_at = now()
            where id = ?
            """,
            finalName, currentChannelType, endpointUrl, endpointSecretCiphertext,
            endpointSecretKeyVersion, endpointMasked, secretStorageStatus, finalDescription,
            configJson, finalEnabled, finalEnabled ? "ready" : "disabled", id);
        return Map.of("id", id);
    }

    public Map<String, Object> deleteChannel(long id) {
        jdbcTemplate.update("delete from notification_channels where id = ?", id);
        return Map.of("id", id);
    }

    public Map<String, Object> testChannel(long id) {
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "use_alert_notification_endpoint");
    }

    public Map<String, Object> send(NotificationSendRequest request) {
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "use_alert_notification_endpoint");
    }

    private Map<String, Object> presentChannel(Map<String, Object> row) {
        var result = new LinkedHashMap<>(row);
        result.put("endpoint_masked", endpointMasked(row));
        result.remove("endpoint_url");
        result.remove("endpoint_secret_ciphertext");
        result.remove("endpoint_secret_key_version");
        return result;
    }

    private Map<String, Object> presentDelivery(Map<String, Object> row) {
        var result = new LinkedHashMap<>(row);
        var endpointUrl = stringOrBlank(row.get("endpoint_url"));
        result.put("response_body", redactNullable(row.get("response_body"), endpointUrl));
        result.put("failure_reason", redactNullable(row.get("failure_reason"), endpointUrl));
        result.put("payload_json", redactPayloadNullable(row.get("payload_json"), endpointUrl));
        result.remove("endpoint_url");
        return result;
    }

    private String redactNullable(Object value, String endpointUrl) {
        return value == null ? null : SECRET_SANITIZER.redactText(String.valueOf(value), endpointUrl);
    }

    private String redactPayloadNullable(Object value, String endpointUrl) {
        return value == null ? null : SECRET_SANITIZER.redactPayloadSecrets(String.valueOf(value), endpointUrl);
    }

    private String configJson(NotificationChannelRequest request) {
        return configJson(request, request.webhookUrl());
    }

    private String configJson(NotificationChannelRequest request, String endpointUrl) {
        return toJson(SECRET_SANITIZER.sanitizeConfig(request.config(), endpointUrl));
    }

    private String endpointMasked(Map<String, Object> row) {
        var status = stringOrBlank(row.get("secret_storage_status"));
        var persisted = stringOrBlank(row.get("endpoint_masked"));
        if ("missing".equals(status)) {
            return persisted.isBlank()
                ? "demo://not-configured"
                : SECRET_SANITIZER.sanitizeEndpointDisplay(persisted);
        }
        if (!persisted.isBlank()) {
            return SECRET_SANITIZER.sanitizeEndpointDisplay(persisted);
        }
        return SECRET_SANITIZER.maskEndpoint(row.get("endpoint_url"));
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException ex) {
            return "{}";
        }
    }

    private String normalizeType(String value) {
        var type = value == null || value.isBlank() ? "webhook" : value.trim().toLowerCase();
        if (!Set.of("webhook", "wecom", "feishu").contains(type)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "unsupported_channel");
        }
        return type;
    }

    private String normalizeName(String value) {
        if (value == null || value.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid_channel_name");
        }
        return value.trim();
    }

    private String normalizeOptionalChannelType(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return normalizeType(value);
    }

    private String normalizeDeliveryStatus(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        var status = value.trim().toLowerCase(Locale.ROOT);
        if (!Set.of("success", "failed").contains(status)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid_delivery_status");
        }
        return status;
    }

    private String normalizeDryRunChannelTypeFilter(String value) {
        if (value == null) {
            return null;
        }
        var channelType = value.trim().toLowerCase(Locale.ROOT);
        if (!Set.of("webhook", "wecom", "feishu").contains(channelType)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "unsupported_channel");
        }
        return channelType;
    }

    private int normalizeDryRunLimit(String value) {
        if (value == null) {
            return 100;
        }
        var normalized = value.trim();
        if (normalized.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid_limit");
        }
        try {
            var limit = Integer.parseInt(normalized);
            if (limit < 1 || limit > 500) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid_limit");
            }
            return limit;
        } catch (NumberFormatException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid_limit");
        }
    }

    private List<Long> normalizeBackfillChannelIds(Object value) {
        if (!(value instanceof List<?> values) || values.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid_channel_ids");
        }
        var ids = new LinkedHashSet<Long>();
        for (var item : values) {
            if (!(item instanceof Number number)) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid_channel_ids");
            }
            var id = number.longValue();
            if (id <= 0 || number.doubleValue() != id) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid_channel_ids");
            }
            ids.add(id);
        }
        if (ids.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid_channel_ids");
        }
        if (ids.size() > 50) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "too_many_channels");
        }
        return List.copyOf(ids);
    }

    private String normalizeRequestedBy(Object value) {
        var requestedBy = stringOrBlank(value).trim();
        return requestedBy.isBlank() ? "manual" : requestedBy;
    }

    private String normalizeBackfillRunStatus(String value) {
        if (value == null) {
            return null;
        }
        var status = value.trim().toLowerCase(Locale.ROOT);
        if (!Set.of("running", "completed", "completed_with_failures", "failed").contains(status)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid_backfill_run_status");
        }
        return status;
    }

    private int normalizeBackfillRunLimit(String value) {
        if (value == null) {
            return 20;
        }
        var normalized = value.trim();
        if (normalized.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid_limit");
        }
        try {
            var limit = Integer.parseInt(normalized);
            if (limit < 1 || limit > 100) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid_limit");
            }
            return limit;
        } catch (NumberFormatException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid_limit");
        }
    }

    private long createBackfillRun(int totalRequested, String requestedBy) {
        var keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            var statement = connection.prepareStatement("""
                insert into notification_secret_backfill_runs(
                    mode, status, confirmation_accepted, requested_by, started_at,
                    total_requested
                )
                values ('manual_channel_ids', 'running', true, ?, now(), ?)
                """, new String[] {"id"});
            statement.setString(1, requestedBy);
            statement.setInt(2, totalRequested);
            return statement;
        }, keyHolder);
        var id = keyHolder.getKey();
        if (id == null) {
            throw new IllegalStateException("Backfill run insert did not return an id");
        }
        return id.longValue();
    }

    private BackfillItemResult processBackfillChannel(long runId, long channelId) {
        try {
            return inBackfillTransaction(() -> processBackfillChannelInTransaction(runId, channelId));
        } catch (RuntimeException ex) {
            insertBackfillItemBestEffort(runId, channelId, null, null, null, null, "failed", "unexpected_error");
            return new BackfillItemResult("failed", false);
        }
    }

    private BackfillItemResult processBackfillChannelInTransaction(long runId, long channelId) {
        var rows = jdbcTemplate.queryForList("""
            select id, name, channel_type, endpoint_url, endpoint_secret_ciphertext,
                   endpoint_secret_key_version, endpoint_masked, secret_storage_status,
                   enabled, status, updated_at
            from notification_channels
            where id = ?
            for update
            """, channelId);
        if (rows.isEmpty()) {
            insertBackfillItem(runId, channelId, null, null, null, null, "skipped", "not_found");
            return new BackfillItemResult("skipped", false);
        }

        var row = rows.get(0);
        var channelType = stringOrBlank(row.get("channel_type")).trim().toLowerCase(Locale.ROOT);
        var beforeStatus = stringOrBlank(row.get("secret_storage_status"));
        var endpointUrl = stringOrBlank(row.get("endpoint_url"));
        var endpointMasked = endpointMasked(row);
        var skipReason = skipReason(channelType, beforeStatus, endpointUrl);
        if (skipReason != null) {
            insertBackfillItem(
                runId,
                channelId,
                channelType,
                beforeStatus,
                beforeStatus,
                endpointMasked,
                "skipped",
                skipReason
            );
            return new BackfillItemResult("skipped", false);
        }

        var normalizedEndpoint = normalizeEndpoint(channelType, endpointUrl);
        NotificationSecretStore.StoredEndpoint storedEndpoint;
        try {
            storedEndpoint = secretStore.storeEndpoint(
                normalizedEndpoint,
                SECRET_SANITIZER.maskEndpoint(normalizedEndpoint)
            );
        } catch (ResponseStatusException ex) {
            insertBackfillItem(
                runId,
                channelId,
                channelType,
                beforeStatus,
                beforeStatus,
                endpointMasked,
                "failed",
                secretStoreFailureReason(ex)
            );
            return new BackfillItemResult("failed", true);
        } catch (RuntimeException ex) {
            insertBackfillItem(
                runId,
                channelId,
                channelType,
                beforeStatus,
                beforeStatus,
                endpointMasked,
                "failed",
                "notification_secret_store_failed"
            );
            return new BackfillItemResult("failed", true);
        }

        jdbcTemplate.update("""
            update notification_channels
            set endpoint_url = null,
                endpoint_secret_ciphertext = ?,
                endpoint_secret_key_version = ?,
                endpoint_masked = ?,
                secret_storage_status = ?,
                updated_at = now()
            where id = ?
            """,
            storedEndpoint.ciphertext(),
            storedEndpoint.keyVersion(),
            storedEndpoint.endpointMasked(),
            storedEndpoint.status(),
            channelId
        );
        insertBackfillItem(
            runId,
            channelId,
            channelType,
            beforeStatus,
            storedEndpoint.status(),
            storedEndpoint.endpointMasked(),
            "migrated",
            null
        );
        return new BackfillItemResult("migrated", true);
    }

    private String skipReason(String channelType, String status, String endpointUrl) {
        if ("encrypted".equals(status)) {
            return "already_encrypted";
        }
        if (!"legacy_plaintext".equals(status)) {
            return "not_legacy_plaintext";
        }
        if (!Set.of("webhook", "wecom", "feishu").contains(channelType)) {
            return "unsupported_channel_type";
        }
        if (endpointUrl.isBlank()) {
            return "endpoint_missing";
        }
        if (endpointIsInvalid(channelType, endpointUrl)) {
            return "endpoint_invalid";
        }
        return null;
    }

    private String secretStoreFailureReason(ResponseStatusException ex) {
        if ("notification_secret_key_missing".equals(ex.getReason())) {
            return "notification_secret_key_missing";
        }
        if ("notification_secret_key_invalid".equals(ex.getReason())) {
            return "notification_secret_key_invalid";
        }
        return "notification_secret_store_failed";
    }

    private void insertBackfillItem(
        long runId,
        long channelId,
        String channelType,
        String beforeStatus,
        String afterStatus,
        String endpointMasked,
        String itemStatus,
        String failureReason
    ) {
        jdbcTemplate.update("""
            insert into notification_secret_backfill_items(
                run_id, channel_id, channel_type, before_secret_storage_status,
                after_secret_storage_status, endpoint_masked, item_status,
                failure_reason
            )
            values (?, ?, ?, ?, ?, ?, ?, ?)
            """,
            runId,
            channelId,
            channelType,
            beforeStatus,
            afterStatus,
            endpointMasked == null ? null : SECRET_SANITIZER.sanitizeEndpointDisplay(endpointMasked),
            itemStatus,
            failureReason
        );
    }

    private void insertBackfillItemBestEffort(
        long runId,
        long channelId,
        String channelType,
        String beforeStatus,
        String afterStatus,
        String endpointMasked,
        String itemStatus,
        String failureReason
    ) {
        try {
            inBackfillTransaction(() -> {
                insertBackfillItem(
                    runId,
                    channelId,
                    channelType,
                    beforeStatus,
                    afterStatus,
                    endpointMasked,
                    itemStatus,
                    failureReason
                );
                return null;
            });
        } catch (RuntimeException ignored) {
            // Best-effort audit must not prevent run finalization.
        }
    }

    private void finalizeBackfillRun(
        long runId,
        String status,
        int eligibleCount,
        int migratedCount,
        int skippedCount,
        int failedCount,
        String failureReason
    ) {
        jdbcTemplate.update("""
            update notification_secret_backfill_runs
            set status = ?, completed_at = now(), eligible_count = ?,
                migrated_count = ?, skipped_count = ?, failed_count = ?,
                failure_reason = ?, updated_at = now()
            where id = ?
            """,
            status,
            eligibleCount,
            migratedCount,
            skippedCount,
            failedCount,
            failureReason,
            runId
        );
    }

    private Map<String, Object> backfillRunRow(long runId) {
        return presentBackfillRun(jdbcTemplate.queryForMap("""
            select id, mode, status, confirmation_accepted, requested_by,
                   requested_at, started_at, completed_at,
                   total_requested, eligible_count, migrated_count, skipped_count,
                   failed_count, failure_reason, created_at, updated_at
            from notification_secret_backfill_runs
            where id = ?
            """, runId));
    }

    private Map<String, Object> presentBackfillRun(Map<String, Object> row) {
        var result = new LinkedHashMap<>(row);
        result.remove("confirmation");
        return result;
    }

    private Map<String, Object> presentBackfillItem(Map<String, Object> row) {
        var result = new LinkedHashMap<>(row);
        var endpointMasked = stringOrBlank(result.get("endpoint_masked"));
        if (!endpointMasked.isBlank()) {
            result.put("endpoint_masked", SECRET_SANITIZER.sanitizeEndpointDisplay(endpointMasked));
        }
        result.remove("endpoint_url");
        result.remove("endpoint_secret_ciphertext");
        result.remove("endpoint_secret_key_version");
        result.remove("confirmation");
        return result;
    }

    private <T> T inBackfillTransaction(Supplier<T> action) {
        if (transactionTemplate == null) {
            return action.get();
        }
        return transactionTemplate.execute(status -> action.get());
    }

    private static TransactionTemplate requiresNewTemplate(PlatformTransactionManager transactionManager) {
        var template = new TransactionTemplate(transactionManager);
        template.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        return template;
    }

    private String normalizeSecretStorageStatus(String value) {
        if (value == null) {
            return null;
        }
        var status = value.trim().toLowerCase(Locale.ROOT);
        if (!Set.of("encrypted", "legacy_plaintext", "missing").contains(status)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid_secret_storage_status");
        }
        return status;
    }

    private Boolean normalizeEnabledFilter(String value) {
        if (value == null) {
            return null;
        }
        var normalized = value.trim().toLowerCase(Locale.ROOT);
        if ("true".equals(normalized)) {
            return true;
        }
        if ("false".equals(normalized)) {
            return false;
        }
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid_enabled_filter");
    }

    private record BackfillItemResult(String itemStatus, boolean eligible) {
    }

    private Map<String, Object> dryRunSummary() {
        var summary = new LinkedHashMap<String, Object>();
        summary.put("totalChannels", 0);
        summary.put("legacyPlaintext", 0);
        summary.put("migrationEligible", 0);
        summary.put("blocked", 0);
        summary.put("encrypted", 0);
        summary.put("missing", 0);
        summary.put("unsupportedStatus", 0);
        return summary;
    }

    private Map<String, Object> dryRunBlockReasons() {
        var blockReasons = new LinkedHashMap<String, Object>();
        blockReasons.put("endpoint_missing", 0);
        blockReasons.put("endpoint_invalid", 0);
        blockReasons.put("unsupported_channel_type", 0);
        return blockReasons;
    }

    private Map<String, Object> dryRunItem(Map<String, Object> row) {
        var item = new LinkedHashMap<String, Object>();
        var status = stringOrBlank(row.get("secret_storage_status"));
        var channelType = stringOrBlank(row.get("channel_type")).trim().toLowerCase(Locale.ROOT);
        var endpointUrl = stringOrBlank(row.get("endpoint_url"));
        var dryRunStatus = "";
        String blockReason = null;
        var migrationEligible = false;

        if ("encrypted".equals(status)) {
            dryRunStatus = "already_encrypted";
        } else if ("missing".equals(status)) {
            dryRunStatus = "missing";
        } else if ("legacy_plaintext".equals(status)) {
            if (!Set.of("webhook", "wecom", "feishu").contains(channelType)) {
                dryRunStatus = "blocked";
                blockReason = "unsupported_channel_type";
            } else if (endpointUrl.isBlank()) {
                dryRunStatus = "blocked";
                blockReason = "endpoint_missing";
            } else if (endpointIsInvalid(channelType, endpointUrl)) {
                dryRunStatus = "blocked";
                blockReason = "endpoint_invalid";
            } else {
                dryRunStatus = "migration_eligible";
                migrationEligible = true;
            }
        } else {
            dryRunStatus = "unsupported_status";
        }

        item.put("id", row.get("id"));
        item.put("name", row.get("name"));
        item.put("channelType", channelType);
        item.put("enabled", booleanValue(row.get("enabled")));
        item.put("secretStorageStatus", status);
        item.put("endpointMasked", endpointMasked(row));
        item.put("dryRunStatus", dryRunStatus);
        item.put("blockReason", blockReason);
        item.put("migrationEligible", migrationEligible);
        item.put("updatedAt", row.get("updated_at"));
        return item;
    }

    private boolean endpointIsInvalid(String channelType, String endpointUrl) {
        try {
            normalizeEndpoint(channelType, endpointUrl);
            return false;
        } catch (ResponseStatusException | IllegalArgumentException ex) {
            return true;
        }
    }

    private void updateDryRunSummary(
        Map<String, Object> summary,
        Map<String, Object> blockReasons,
        Map<String, Object> item
    ) {
        increment(summary, "totalChannels");
        var status = stringOrBlank(item.get("secretStorageStatus"));
        if ("legacy_plaintext".equals(status)) {
            increment(summary, "legacyPlaintext");
            if (Boolean.TRUE.equals(item.get("migrationEligible"))) {
                increment(summary, "migrationEligible");
            } else {
                increment(summary, "blocked");
                var blockReason = stringOrBlank(item.get("blockReason"));
                if (!blockReason.isBlank() && blockReasons.containsKey(blockReason)) {
                    increment(blockReasons, blockReason);
                }
            }
            return;
        }
        if ("encrypted".equals(status)) {
            increment(summary, "encrypted");
            return;
        }
        if ("missing".equals(status)) {
            increment(summary, "missing");
            return;
        }
        increment(summary, "unsupportedStatus");
    }

    private void increment(Map<String, Object> values, String key) {
        values.put(key, ((Number) values.get(key)).intValue() + 1);
    }

    private String normalizeEndpoint(String channelType, String value) {
        if (value == null || value.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, invalidEndpointReason(channelType));
        }
        var endpoint = value.trim();
        try {
            var uri = URI.create(endpoint);
            var scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase();
            var host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
            if ("wecom".equals(channelType)) {
                var path = uri.getPath() == null ? "" : uri.getPath();
                var query = uri.getQuery() == null ? "" : uri.getQuery();
                if (!"https".equals(scheme)
                    || !"qyapi.weixin.qq.com".equals(host)
                    || !path.contains("/cgi-bin/webhook/send")
                    || !hasQueryKey(query)) {
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid_wecom_webhook_url");
                }
                return endpoint;
            }
            if ("feishu".equals(channelType)) {
                var path = uri.getPath() == null ? "" : uri.getPath();
                if (!"https".equals(scheme)
                    || !FEISHU_HOST.equals(host)
                    || !path.startsWith(FEISHU_PATH_PREFIX)
                    || !isSingleFeishuTokenPath(path)
                    || uri.getQuery() != null
                    || uri.getFragment() != null) {
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid_feishu_webhook_url");
                }
                return endpoint;
            }
            if (!("http".equals(scheme) || "https".equals(scheme)) || host.isBlank()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid_webhook_url");
            }
            return endpoint;
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, invalidEndpointReason(channelType));
        }
    }

    private String invalidEndpointReason(String channelType) {
        if ("wecom".equals(channelType)) {
            return "invalid_wecom_webhook_url";
        }
        if ("feishu".equals(channelType)) {
            return "invalid_feishu_webhook_url";
        }
        return "invalid_webhook_url";
    }

    private boolean hasQueryKey(String query) {
        return !extractQueryKeyFromQuery(query).isBlank();
    }

    private String extractQueryKeyFromQuery(String query) {
        if (query == null || query.isBlank()) {
            return "";
        }
        for (var part : query.split("&")) {
            var equalsIndex = part.indexOf('=');
            var key = equalsIndex >= 0 ? part.substring(0, equalsIndex) : part;
            if ("key".equals(key) && equalsIndex >= 0 && equalsIndex < part.length() - 1) {
                return part.substring(equalsIndex + 1);
            }
        }
        return "";
    }

    private boolean isSingleFeishuTokenPath(String path) {
        var token = path.substring(FEISHU_PATH_PREFIX.length()).trim();
        return !token.isBlank() && !token.contains("/");
    }

    private String stringOrBlank(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private String stringOrNull(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private boolean booleanValue(Object value) {
        if (value instanceof Boolean flag) {
            return flag;
        }
        return Boolean.parseBoolean(stringOrBlank(value));
    }

    private Map<String, Object> currentChannel(long id) {
        var rows = jdbcTemplate.queryForList("""
            select id, name, channel_type, endpoint_url, endpoint_secret_ciphertext,
                   endpoint_secret_key_version, endpoint_masked, secret_storage_status,
                   description, cast(config_json as varchar) as config_json,
                   enabled, status
            from notification_channels
            where id = ?
            """, id);
        if (rows.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "channel_not_found");
        }
        return rows.get(0);
    }

}
