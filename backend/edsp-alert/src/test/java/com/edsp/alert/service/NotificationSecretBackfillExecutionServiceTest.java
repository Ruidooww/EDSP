package com.edsp.alert.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Statement;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.web.server.ResponseStatusException;

class NotificationSecretBackfillExecutionServiceTest {
    private static final String CONFIRMATION = "EXECUTE_NOTIFICATION_SECRET_BACKFILL";
    private static final String TEST_MASTER_KEY =
        "MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=";
    private static final String WEBHOOK_TOKEN = "WEBHOOKTOKEN123456";
    private static final String WECOM_KEY = "WESECRET123456";
    private static final String FEISHU_TOKEN = "FEISHUTOKEN123456";
    private static final String WEBHOOK_URL = "https://legacy.example.test/hook?token=" + WEBHOOK_TOKEN;
    private static final String WECOM_URL =
        "https://qyapi.weixin.qq.com/cgi-bin/webhook/send?key=" + WECOM_KEY;
    private static final String FEISHU_URL =
        "https://open.feishu.cn/open-apis/bot/v2/hook/" + FEISHU_TOKEN;

    private JdbcTemplate jdbcTemplate;
    private NotificationService service;

    @BeforeEach
    void setUp() {
        var dataSource = new JdbcDataSource();
        dataSource.setURL(
            "jdbc:h2:mem:notification_secret_backfill_execution_" + System.nanoTime()
                + ";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;"
                + "DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1;"
                + "INIT=CREATE DOMAIN IF NOT EXISTS JSONB AS JSON\\;"
                + "CREATE DOMAIN IF NOT EXISTS TIMESTAMPTZ AS TIMESTAMP WITH TIME ZONE"
        );
        dataSource.setUser("sa");
        dataSource.setPassword("");

        jdbcTemplate = new JdbcTemplate(dataSource);
        createTables();
        service = new NotificationService(
            jdbcTemplate,
            new ObjectMapper(),
            new NotificationSecretStore(TEST_MASTER_KEY),
            new DataSourceTransactionManager(dataSource)
        );
    }

    @Test
    void executeSecretBackfillMigratesEligibleChannelsAndAuditsWithoutLeakingSecrets() {
        var webhookId = insertLegacyChannel("webhook legacy", "webhook", WEBHOOK_URL, true);
        var wecomId = insertLegacyChannel("wecom legacy", "wecom", WECOM_URL, true);
        var feishuId = insertLegacyChannel("feishu legacy", "feishu", FEISHU_URL, true);
        jdbcTemplate.update(
            "update notification_channels set config_json = cast(? as jsonb), updated_at = timestamp '2026-01-01 00:00:00' where id = ?",
            "{\"team\":\"secops\",\"note\":\"keep config\"}",
            webhookId
        );
        insertDelivery(webhookId, "historical response " + WEBHOOK_TOKEN);
        var beforeConfig = configJson(webhookId);
        var beforeDelivery = deliverySnapshots();

        var result = service.executeSecretBackfill(Map.of(
            "channelIds", List.of(webhookId, wecomId, feishuId),
            "confirmation", CONFIRMATION,
            "requestedBy", "ops-user"
        ));

        assertEquals("completed", result.get("status"));
        assertEquals(3, number(result.get("total_requested")));
        assertEquals(3, number(result.get("eligible_count")));
        assertEquals(3, number(result.get("migrated_count")));
        assertEquals(0, number(result.get("skipped_count")));
        assertEquals(0, number(result.get("failed_count")));
        assertEquals(true, result.get("confirmation_accepted"));
        assertFalse(result.containsKey("confirmation"));
        assertSecretSafe(result);

        for (var id : List.of(webhookId, wecomId, feishuId)) {
            var channel = channelRow(id);
            assertEquals(null, channel.get("endpoint_url"));
            assertEquals("local-v1", channel.get("endpoint_secret_key_version"));
            assertEquals("encrypted", channel.get("secret_storage_status"));
            assertCiphertextFormat(String.valueOf(channel.get("endpoint_secret_ciphertext")));
            assertFalse(String.valueOf(channel.get("endpoint_secret_ciphertext")).contains("TOKEN"));
            assertFalse(String.valueOf(channel.get("endpoint_masked")).contains("TOKEN"));
            assertNotEquals("2026-01-01 00:00:00", String.valueOf(channel.get("updated_at")));
        }
        assertFalse(String.valueOf(channelRow(webhookId).get("endpoint_masked")).contains(WEBHOOK_TOKEN));
        assertFalse(String.valueOf(channelRow(wecomId).get("endpoint_masked")).contains(WECOM_KEY));
        assertFalse(String.valueOf(channelRow(feishuId).get("endpoint_masked")).contains(FEISHU_TOKEN));
        assertEquals(beforeConfig, configJson(webhookId));
        assertEquals(beforeDelivery, deliverySnapshots());
        assertEquals(0L, count("alert_lifecycle_events"));

        var runId = number(result.get("id"));
        var run = jdbcTemplate.queryForMap("select * from notification_secret_backfill_runs where id = ?", runId);
        assertEquals("manual_channel_ids", run.get("mode"));
        assertEquals("completed", run.get("status"));
        assertEquals(true, run.get("confirmation_accepted"));
        assertEquals("ops-user", run.get("requested_by"));
        assertEquals(3, number(run.get("migrated_count")));
        var items = jdbcTemplate.queryForList(
            "select * from notification_secret_backfill_items where run_id = ? order by channel_id",
            runId
        );
        assertEquals(3, items.size());
        assertTrue(items.stream().allMatch(item -> "migrated".equals(item.get("item_status"))));
        assertTrue(items.stream().allMatch(item -> "legacy_plaintext".equals(item.get("before_secret_storage_status"))));
        assertTrue(items.stream().allMatch(item -> "encrypted".equals(item.get("after_secret_storage_status"))));
        assertSecretSafe(service.secretBackfillRunDetail(runId));
    }

    @Test
    void executeSecretBackfillValidatesRequestAndMasterKeyBeforeRunCreation() {
        var channelId = insertLegacyChannel("webhook legacy", "webhook", WEBHOOK_URL, true);

        assertBadRequest("invalid_channel_ids", () -> service.executeSecretBackfill(Map.of("confirmation", CONFIRMATION)));
        assertBadRequest("invalid_channel_ids", () -> service.executeSecretBackfill(Map.of(
            "channelIds", List.of(),
            "confirmation", CONFIRMATION
        )));
        assertBadRequest("invalid_channel_ids", () -> service.executeSecretBackfill(Map.of(
            "channelIds", List.of(0),
            "confirmation", CONFIRMATION
        )));
        assertBadRequest("too_many_channels", () -> service.executeSecretBackfill(Map.of(
            "channelIds", java.util.stream.LongStream.rangeClosed(1, 51).boxed().toList(),
            "confirmation", CONFIRMATION
        )));
        assertBadRequest("invalid_confirmation", () -> service.executeSecretBackfill(Map.of("channelIds", List.of(channelId))));
        assertBadRequest("invalid_confirmation", () -> service.executeSecretBackfill(Map.of(
            "channelIds", List.of(channelId),
            "confirmation", "wrong"
        )));

        var missingKeyService = new NotificationService(
            jdbcTemplate,
            new ObjectMapper(),
            new NotificationSecretStore(""),
            (DataSourceTransactionManager) null
        );
        var invalidKeyService = new NotificationService(
            jdbcTemplate,
            new ObjectMapper(),
            new NotificationSecretStore("not-base64"),
            (DataSourceTransactionManager) null
        );
        assertBadRequest("notification_secret_key_missing", () -> missingKeyService.executeSecretBackfill(Map.of(
            "channelIds", List.of(channelId),
            "confirmation", CONFIRMATION
        )));
        assertBadRequest("notification_secret_key_invalid", () -> invalidKeyService.executeSecretBackfill(Map.of(
            "channelIds", List.of(channelId),
            "confirmation", CONFIRMATION
        )));

        assertEquals(WEBHOOK_URL, channelRow(channelId).get("endpoint_url"));
        assertEquals(null, channelRow(channelId).get("endpoint_secret_ciphertext"));
        assertEquals(0L, count("notification_secret_backfill_runs"));
        assertEquals(0L, count("notification_secret_backfill_items"));
    }

    @Test
    void executeSecretBackfillSkipsIneligibleItemsWithoutMutatingChannels() {
        var encryptedId = insertEncryptedChannel("encrypted");
        var missingId = insertMissingChannel("missing");
        var missingEndpointId = insertLegacyChannel("missing endpoint", "webhook", null, true);
        var invalidEndpointId = insertLegacyChannel("invalid endpoint", "webhook", "not-a-url", true);
        var unsupportedId = insertLegacyChannel("unsupported", "email", "https://legacy.example.test/hook", true);
        var eligibleId = insertLegacyChannel("eligible", "webhook", WEBHOOK_URL, true);
        var before = channelSnapshots();

        var result = service.executeSecretBackfill(Map.of(
            "channelIds", List.of(encryptedId, missingId, missingEndpointId, invalidEndpointId, unsupportedId, eligibleId, 999999L),
            "confirmation", CONFIRMATION
        ));

        assertEquals("completed", result.get("status"));
        assertEquals(7, number(result.get("total_requested")));
        assertEquals(1, number(result.get("eligible_count")));
        assertEquals(1, number(result.get("migrated_count")));
        assertEquals(6, number(result.get("skipped_count")));
        assertEquals(0, number(result.get("failed_count")));
        assertEquals("manual", runRow(number(result.get("id"))).get("requested_by"));
        assertEquals("encrypted", channelRow(eligibleId).get("secret_storage_status"));
        assertEquals(null, channelRow(eligibleId).get("endpoint_url"));

        for (var snapshot : before) {
            var id = ((Number) snapshot.get("id")).longValue();
            if (id == eligibleId) {
                continue;
            }
            assertEquals(snapshot, channelSnapshot(id));
        }

        var detail = service.secretBackfillRunDetail(number(result.get("id")));
        var items = asList(detail.get("items"));
        assertItem(items, encryptedId, "skipped", "already_encrypted");
        assertItem(items, missingId, "skipped", "not_legacy_plaintext");
        assertItem(items, missingEndpointId, "skipped", "endpoint_missing");
        assertItem(items, invalidEndpointId, "skipped", "endpoint_invalid");
        assertItem(items, unsupportedId, "skipped", "unsupported_channel_type");
        assertItem(items, 999999L, "skipped", "not_found");
        assertSecretSafe(detail);
    }

    @Test
    void executeSecretBackfillRecordsStoreFailureWithoutRollingBackOtherItems() {
        var firstId = insertLegacyChannel("first eligible", "webhook", WEBHOOK_URL, true);
        var failingId = insertLegacyChannel("failing eligible", "webhook", "https://legacy.example.test/fail?token=FAILTOKEN123456", true);
        var secondId = insertLegacyChannel("second eligible", "wecom", WECOM_URL, true);
        var failingService = new NotificationService(
            jdbcTemplate,
            new ObjectMapper(),
            new FailingSecretStore(TEST_MASTER_KEY),
            (DataSourceTransactionManager) null
        );

        var result = failingService.executeSecretBackfill(Map.of(
            "channelIds", List.of(firstId, failingId, secondId),
            "confirmation", CONFIRMATION
        ));

        assertEquals("completed_with_failures", result.get("status"));
        assertEquals(3, number(result.get("eligible_count")));
        assertEquals(2, number(result.get("migrated_count")));
        assertEquals(0, number(result.get("skipped_count")));
        assertEquals(1, number(result.get("failed_count")));
        assertEquals("encrypted", channelRow(firstId).get("secret_storage_status"));
        assertEquals("encrypted", channelRow(secondId).get("secret_storage_status"));
        assertEquals("legacy_plaintext", channelRow(failingId).get("secret_storage_status"));
        assertEquals("https://legacy.example.test/fail?token=FAILTOKEN123456", channelRow(failingId).get("endpoint_url"));
        assertEquals(null, channelRow(failingId).get("endpoint_secret_ciphertext"));

        var items = asList(service.secretBackfillRunDetail(number(result.get("id"))).get("items"));
        assertItem(items, firstId, "migrated", null);
        assertItem(items, failingId, "failed", "notification_secret_store_failed");
        assertItem(items, secondId, "migrated", null);
        assertSecretSafe(result);
    }

    @Test
    void listAndDetailBackfillRunsAreSecretSafeAndValidateFilters() {
        var channelId = insertLegacyChannel("webhook legacy", "webhook", WEBHOOK_URL, true);
        var result = service.executeSecretBackfill(Map.of(
            "channelIds", List.of(channelId),
            "confirmation", CONFIRMATION
        ));
        var runId = number(result.get("id"));

        var runs = service.listSecretBackfillRuns(null, null);
        var completedRuns = service.listSecretBackfillRuns("completed", "1");
        var detail = service.secretBackfillRunDetail(runId);

        assertEquals(1, asList(runs.get("items")).size());
        assertEquals(20, number(runs.get("limit")));
        assertEquals(1, asList(completedRuns.get("items")).size());
        assertEquals(1, number(completedRuns.get("limit")));
        assertEquals(runId, number(detail.get("id")));
        assertEquals(1, asList(detail.get("items")).size());
        assertSecretSafe(runs);
        assertSecretSafe(completedRuns);
        assertSecretSafe(detail);

        assertBadRequest("invalid_backfill_run_status", () -> service.listSecretBackfillRuns("unknown", null));
        assertBadRequest("invalid_limit", () -> service.listSecretBackfillRuns(null, "0"));
        assertBadRequest("invalid_limit", () -> service.listSecretBackfillRuns(null, "101"));
        assertBadRequest("invalid_limit", () -> service.listSecretBackfillRuns(null, "abc"));
        var missing = assertThrows(ResponseStatusException.class, () -> service.secretBackfillRunDetail(999999L));
        assertEquals(HttpStatus.NOT_FOUND, missing.getStatusCode());
        assertEquals("backfill_run_not_found", missing.getReason());
    }

    private void createTables() {
        jdbcTemplate.execute("""
            create table notification_channels (
                id bigserial primary key,
                name varchar(160) not null,
                channel_type varchar(40) not null default 'webhook',
                endpoint_url text,
                endpoint_secret_ciphertext text,
                endpoint_secret_key_version varchar(64),
                endpoint_masked text,
                secret_storage_status varchar(32) not null default 'legacy_plaintext',
                description text,
                config_json jsonb not null default '{}',
                enabled boolean not null default true,
                status varchar(40) not null default 'draft',
                last_test_status varchar(40),
                last_test_message text,
                last_test_at timestamptz,
                created_at timestamptz not null default now(),
                updated_at timestamptz not null default now()
            )
            """);
        jdbcTemplate.execute("""
            create table notification_deliveries (
                id bigserial primary key,
                channel_id bigint,
                alert_id bigint,
                title varchar(240) not null,
                severity varchar(32),
                status varchar(32) not null,
                response_code integer,
                response_body text,
                payload_json jsonb not null default '{}',
                failure_type varchar(80),
                failure_reason text,
                retryable boolean not null default false,
                retry_of_delivery_id bigint,
                retry_count integer not null default 0,
                created_at timestamptz not null default now()
            )
            """);
        jdbcTemplate.execute("""
            create table alert_lifecycle_events (
                id bigserial primary key,
                alert_id bigint,
                event_type varchar(64) not null,
                created_at timestamptz not null default now()
            )
            """);
        jdbcTemplate.execute("""
            create table notification_secret_backfill_runs (
                id bigserial primary key,
                mode varchar(32) not null,
                status varchar(32) not null,
                confirmation_accepted boolean not null default false,
                requested_by varchar(128),
                requested_at timestamptz not null default now(),
                started_at timestamptz,
                completed_at timestamptz,
                total_requested integer not null default 0,
                eligible_count integer not null default 0,
                migrated_count integer not null default 0,
                skipped_count integer not null default 0,
                failed_count integer not null default 0,
                failure_reason text,
                created_at timestamptz not null default now(),
                updated_at timestamptz not null default now()
            )
            """);
        jdbcTemplate.execute("""
            create table notification_secret_backfill_items (
                id bigserial primary key,
                run_id bigint not null,
                channel_id bigint not null,
                channel_type varchar(40),
                before_secret_storage_status varchar(32),
                after_secret_storage_status varchar(32),
                endpoint_masked text,
                item_status varchar(32) not null,
                failure_reason text,
                created_at timestamptz not null default now(),
                updated_at timestamptz not null default now()
            )
            """);
    }

    private Long insertLegacyChannel(String name, String channelType, String endpointUrl, boolean enabled) {
        return insertAndReturnId("""
            insert into notification_channels(
                name, channel_type, endpoint_url, secret_storage_status, enabled, status
            )
            values (?, ?, ?, 'legacy_plaintext', ?, ?)
            """, name, channelType, endpointUrl, enabled, enabled ? "ready" : "disabled");
    }

    private Long insertEncryptedChannel(String name) {
        return insertAndReturnId("""
            insert into notification_channels(
                name, channel_type, endpoint_url, endpoint_secret_ciphertext,
                endpoint_secret_key_version, endpoint_masked, secret_storage_status, enabled, status
            )
            values (?, 'webhook', null, 'v1:ciphertext', 'local-v1', 'https://encrypted.example.test/...',
                    'encrypted', true, 'ready')
            """, name);
    }

    private Long insertMissingChannel(String name) {
        return insertAndReturnId("""
            insert into notification_channels(
                name, channel_type, endpoint_url, endpoint_masked, secret_storage_status, enabled, status
            )
            values (?, 'webhook', null, 'demo://not-configured', 'missing', false, 'disabled')
            """, name);
    }

    private void insertDelivery(long channelId, String responseBody) {
        jdbcTemplate.update("""
            insert into notification_deliveries(
                channel_id, title, severity, status, response_code, response_body, payload_json,
                failure_reason
            )
            values (?, 'historical delivery', 'high', 'failed', 500, ?, cast(? as jsonb), ?)
            """,
            channelId,
            responseBody,
            "{\"payload\":\"" + WEBHOOK_TOKEN + "\"}",
            "failure " + WEBHOOK_TOKEN
        );
    }

    private Long insertAndReturnId(String sql, Object... args) {
        var keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            var statement = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS);
            for (var index = 0; index < args.length; index++) {
                statement.setObject(index + 1, args[index]);
            }
            return statement;
        }, keyHolder);
        var keys = keyHolder.getKeys();
        Number key = null;
        if (keys != null && keys.get("id") instanceof Number id) {
            key = id;
        } else if (keys != null && keys.get("ID") instanceof Number id) {
            key = id;
        } else {
            key = keyHolder.getKey();
        }
        if (key == null) {
            throw new IllegalStateException("Insert did not return a generated id");
        }
        return key.longValue();
    }

    private Map<String, Object> channelRow(long id) {
        return jdbcTemplate.queryForMap("select * from notification_channels where id = ?", id);
    }

    private Map<String, Object> runRow(long id) {
        return jdbcTemplate.queryForMap("select * from notification_secret_backfill_runs where id = ?", id);
    }

    private List<Map<String, Object>> channelSnapshots() {
        return jdbcTemplate.queryForList("""
            select id, endpoint_url, endpoint_secret_ciphertext, endpoint_secret_key_version,
                   endpoint_masked, secret_storage_status, enabled, status,
                   cast(config_json as varchar) as config_json
            from notification_channels
            order by id
            """);
    }

    private Map<String, Object> channelSnapshot(long id) {
        return jdbcTemplate.queryForMap("""
            select id, endpoint_url, endpoint_secret_ciphertext, endpoint_secret_key_version,
                   endpoint_masked, secret_storage_status, enabled, status,
                   cast(config_json as varchar) as config_json
            from notification_channels
            where id = ?
            """, id);
    }

    private List<Map<String, Object>> deliverySnapshots() {
        return jdbcTemplate.queryForList("""
            select channel_id, response_body, failure_reason, cast(payload_json as varchar) as payload_json
            from notification_deliveries
            order by id
            """);
    }

    private String configJson(long id) {
        return jdbcTemplate.queryForObject(
            "select cast(config_json as varchar) from notification_channels where id = ?",
            String.class,
            id
        );
    }

    private long count(String table) {
        return jdbcTemplate.queryForObject("select count(*) from " + table, Long.class);
    }

    private int number(Object value) {
        return ((Number) value).intValue();
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> asList(Object value) {
        return (List<Map<String, Object>>) value;
    }

    private void assertItem(List<Map<String, Object>> items, long channelId, String status, String reason) {
        var item = items.stream()
            .filter(row -> ((Number) row.get("channel_id")).longValue() == channelId)
            .findFirst()
            .orElseThrow();
        assertEquals(status, item.get("item_status"));
        assertEquals(reason, item.get("failure_reason"));
        assertFalse(item.containsKey("endpoint_url"));
        assertFalse(item.containsKey("endpoint_secret_ciphertext"));
        assertFalse(item.containsKey("endpoint_secret_key_version"));
    }

    private void assertBadRequest(String reason, Runnable action) {
        var error = assertThrows(ResponseStatusException.class, action::run);
        assertEquals(HttpStatus.BAD_REQUEST, error.getStatusCode());
        assertEquals(reason, error.getReason());
    }

    private void assertCiphertextFormat(String ciphertext) {
        var parts = ciphertext.split(":", 3);
        assertEquals(3, parts.length);
        assertEquals("v1", parts[0]);
        assertEquals(12, Base64.getDecoder().decode(parts[1]).length);
        assertTrue(Base64.getDecoder().decode(parts[2]).length > 0);
    }

    private void assertSecretSafe(Object value) {
        var text = String.valueOf(value);
        assertFalse(text.contains("endpoint_url"));
        assertFalse(text.contains("endpoint_secret_ciphertext"));
        assertFalse(text.contains("endpoint_secret_key_version"));
        assertFalse(text.contains("confirmation="));
        assertFalse(text.contains(WEBHOOK_TOKEN));
        assertFalse(text.contains(WECOM_KEY));
        assertFalse(text.contains(FEISHU_TOKEN));
        assertFalse(text.contains("FAILTOKEN123456"));
        assertFalse(text.contains("access_token"));
        assertFalse(text.contains("Authorization"));
        assertFalse(text.contains("Bearer"));
    }

    private static class FailingSecretStore extends NotificationSecretStore {
        FailingSecretStore(String configuredMasterKey) {
            super(configuredMasterKey);
        }

        @Override
        public StoredEndpoint storeEndpoint(String endpointUrl, String endpointMasked) {
            if (endpointUrl.contains("FAILTOKEN123456")) {
                throw new IllegalStateException("simulated store failure");
            }
            return super.storeEndpoint(endpointUrl, endpointMasked);
        }
    }
}
