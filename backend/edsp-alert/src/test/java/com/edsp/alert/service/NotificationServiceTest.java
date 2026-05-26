package com.edsp.alert.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.edsp.alert.dto.NotificationChannelRequest;
import com.edsp.alert.dto.NotificationSendRequest;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Statement;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.web.server.ResponseStatusException;

class NotificationServiceTest {
    private static final String FEISHU_TOKEN = "FEISHUTOKEN123456";
    private static final String FEISHU_URL =
        "https://open.feishu.cn/open-apis/bot/v2/hook/" + FEISHU_TOKEN;
    private static final String TEST_MASTER_KEY =
        "MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=";

    private JdbcTemplate jdbcTemplate;
    private NotificationService service;

    @BeforeEach
    void setUp() {
        var dataSource = new JdbcDataSource();
        dataSource.setURL(
            "jdbc:h2:mem:notification_service_test_" + System.nanoTime()
                + ";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;"
                + "DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1;"
                + "INIT=CREATE DOMAIN IF NOT EXISTS JSONB AS JSON\\;"
                + "CREATE DOMAIN IF NOT EXISTS TIMESTAMPTZ AS TIMESTAMP WITH TIME ZONE"
        );
        dataSource.setUser("sa");
        dataSource.setPassword("");

        jdbcTemplate = new JdbcTemplate(dataSource);
        jdbcTemplate.execute("""
            create table alerts (
                id bigserial primary key,
                title varchar(240) not null,
                severity varchar(32) not null,
                status varchar(32) not null default 'open'
            )
            """);
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
        service = new NotificationService(
            jdbcTemplate,
            new ObjectMapper(),
            new NotificationSecretStore(TEST_MASTER_KEY)
        );
    }

    @Test
    void listDeliveriesCanFilterByAlertIdWithoutChangingDefaultLimitBehavior() {
        var firstAlertId = insertAlert("first alert");
        var secondAlertId = insertAlert("second alert");
        var channelId = insertChannel("webhook");
        insertDelivery(channelId, firstAlertId, "first");
        insertDelivery(channelId, secondAlertId, "second");

        var all = service.listDeliveries(50, null);
        var filtered = service.listDeliveries(50, firstAlertId);

        assertEquals(2, all.size());
        assertEquals(1, filtered.size());
        assertEquals(firstAlertId, ((Number) filtered.get(0).get("alert_id")).longValue());
    }

    @Test
    void listChannelsCanFilterBySecretStorageStatusAndEnabled() {
        service.createChannel(new NotificationChannelRequest(
            "encrypted enabled",
            "webhook",
            "https://hook.example.test/enabled?token=ENABLEDSECRET123456",
            null,
            true,
            Map.of()
        ));
        service.createChannel(new NotificationChannelRequest(
            "encrypted disabled",
            "webhook",
            "https://hook.example.test/disabled?token=DISABLEDSECRET123456",
            null,
            false,
            Map.of()
        ));
        insertLegacyChannel("legacy enabled", "https://legacy.example.test/enabled?token=LEGACYENABLED123456", true);
        insertLegacyChannel("legacy disabled", "https://legacy.example.test/disabled?token=LEGACYDISABLED123456", false);
        insertMissingChannel("webhook", false);

        var encrypted = service.listChannels("encrypted", null);
        var legacy = service.listChannels("legacy_plaintext", null);
        var missing = service.listChannels("missing", null);
        var enabled = service.listChannels(null, "true");
        var disabled = service.listChannels(null, "false");
        var enabledLegacy = service.listChannels("legacy_plaintext", "true");
        var disabledEncrypted = service.listChannels("encrypted", "false");
        var all = service.listChannels(null, null);

        assertEquals(2, encrypted.size());
        assertEquals(true, encrypted.stream().allMatch(row -> "encrypted".equals(row.get("secret_storage_status"))));
        assertEquals(2, legacy.size());
        assertEquals(true, legacy.stream().allMatch(row -> "legacy_plaintext".equals(row.get("secret_storage_status"))));
        assertEquals(1, missing.size());
        assertEquals("missing", missing.get(0).get("secret_storage_status"));
        assertEquals(2, enabled.size());
        assertEquals(true, enabled.stream().allMatch(row -> Boolean.TRUE.equals(row.get("enabled"))));
        assertEquals(3, disabled.size());
        assertEquals(true, disabled.stream().allMatch(row -> Boolean.FALSE.equals(row.get("enabled"))));
        assertEquals(1, enabledLegacy.size());
        assertEquals("legacy enabled", enabledLegacy.get(0).get("name"));
        assertEquals(1, disabledEncrypted.size());
        assertEquals("encrypted disabled", disabledEncrypted.get(0).get("name"));
        assertEquals(5, all.size());

        for (var row : all) {
            assertFalse(row.containsKey("endpoint_url"));
            assertFalse(row.containsKey("endpoint_secret_ciphertext"));
            assertFalse(row.containsKey("endpoint_secret_key_version"));
            assertFalse(String.valueOf(row).contains("ENABLEDSECRET123456"));
            assertFalse(String.valueOf(row).contains("DISABLEDSECRET123456"));
            assertFalse(String.valueOf(row).contains("LEGACYENABLED123456"));
            assertFalse(String.valueOf(row).contains("LEGACYDISABLED123456"));
        }
    }

    @Test
    void listChannelsRejectsInvalidReadinessFilters() {
        var invalidSecretStorageStatus = assertThrows(
            ResponseStatusException.class,
            () -> service.listChannels("plaintext", null)
        );
        var invalidEnabled = assertThrows(
            ResponseStatusException.class,
            () -> service.listChannels(null, "abc")
        );
        var emptyEnabled = assertThrows(
            ResponseStatusException.class,
            () -> service.listChannels(null, "")
        );

        assertEquals(HttpStatus.BAD_REQUEST, invalidSecretStorageStatus.getStatusCode());
        assertEquals("invalid_secret_storage_status", invalidSecretStorageStatus.getReason());
        assertEquals(HttpStatus.BAD_REQUEST, invalidEnabled.getStatusCode());
        assertEquals("invalid_enabled_filter", invalidEnabled.getReason());
        assertEquals(HttpStatus.BAD_REQUEST, emptyEnabled.getStatusCode());
        assertEquals("invalid_enabled_filter", emptyEnabled.getReason());
    }

    @Test
    void secretBackfillDryRunClassifiesAndSummarizesWithoutWriting() throws Exception {
        insertEncryptedChannel("encrypted", "webhook", false);
        insertMissingChannel("webhook", false);
        insertLegacyChannel("legacy valid", "https://legacy.example.test/hook?token=WEBHOOKTOKEN123456", true);
        insertLegacyChannel("legacy missing", null, true);
        insertLegacyChannel("legacy invalid", "not-a-url", true);
        insertLegacyChannel("legacy unsupported", "email", "https://legacy.example.test/hook?token=EMAILTOKEN123456", true);
        var before = channelSnapshots();

        var result = service.secretBackfillDryRun(null, null, null);
        var summary = asMap(result.get("summary"));
        var blockReasons = asMap(result.get("blockReasons"));
        var items = asList(result.get("items"));
        var response = String.valueOf(result);

        assertEquals(6, number(summary.get("totalChannels")));
        assertEquals(4, number(summary.get("legacyPlaintext")));
        assertEquals(1, number(summary.get("migrationEligible")));
        assertEquals(3, number(summary.get("blocked")));
        assertEquals(1, number(summary.get("encrypted")));
        assertEquals(1, number(summary.get("missing")));
        assertEquals(1, number(blockReasons.get("endpoint_missing")));
        assertEquals(1, number(blockReasons.get("endpoint_invalid")));
        assertEquals(1, number(blockReasons.get("unsupported_channel_type")));
        assertEquals(100, number(result.get("limit")));
        assertEquals(false, result.get("truncated"));
        assertEquals(6, items.size());

        assertDryRunItem(items, "encrypted", "already_encrypted", null, false);
        assertDryRunItem(items, "missing channel", "missing", null, false);
        assertDryRunItem(items, "legacy valid", "migration_eligible", null, true);
        assertDryRunItem(items, "legacy missing", "blocked", "endpoint_missing", false);
        assertDryRunItem(items, "legacy invalid", "blocked", "endpoint_invalid", false);
        assertDryRunItem(items, "legacy unsupported", "blocked", "unsupported_channel_type", false);

        assertEquals(before, channelSnapshots());
        assertEquals(0L, jdbcTemplate.queryForObject("select count(*) from notification_deliveries", Long.class));
        assertEquals(0L, jdbcTemplate.queryForObject("select count(*) from alert_lifecycle_events", Long.class));
        assertFalse(response.contains("endpoint_url"));
        assertFalse(response.contains("endpoint_secret_ciphertext"));
        assertFalse(response.contains("endpoint_secret_key_version"));
        assertFalse(response.contains("WEBHOOKTOKEN123456"));
        assertFalse(response.contains("EMAILTOKEN123456"));
        assertFalse(response.contains("access_token"));
        assertFalse(response.contains("Authorization"));
        assertFalse(response.contains("Bearer"));
    }

    @Test
    void secretBackfillDryRunFiltersLimitsAndDoesNotRequireMasterKey() {
        insertLegacyChannel("enabled webhook", "webhook", "https://legacy.example.test/webhook?token=WEBHOOKTOKEN123456", true);
        insertLegacyChannel("disabled webhook", "webhook", "https://legacy.example.test/disabled?token=DISABLEDTOKEN123456", false);
        insertLegacyChannel("enabled wecom", "wecom", "https://qyapi.weixin.qq.com/cgi-bin/webhook/send?key=WESECRET123456", true);
        insertLegacyChannel("enabled feishu", "feishu", FEISHU_URL, true);
        var noKeyService = new NotificationService(jdbcTemplate, new ObjectMapper(), new NotificationSecretStore(""));

        var enabledWebhook = noKeyService.secretBackfillDryRun("true", "webhook", "1");
        var summary = asMap(enabledWebhook.get("summary"));
        var items = asList(enabledWebhook.get("items"));

        assertEquals(1, number(summary.get("totalChannels")));
        assertEquals(1, number(summary.get("legacyPlaintext")));
        assertEquals(1, number(summary.get("migrationEligible")));
        assertEquals(1, items.size());
        assertEquals(false, enabledWebhook.get("truncated"));
        assertEquals("enabled webhook", items.get(0).get("name"));

        var disabled = noKeyService.secretBackfillDryRun("false", null, null);
        assertEquals(1, number(asMap(disabled.get("summary")).get("totalChannels")));
        assertEquals("disabled webhook", asList(disabled.get("items")).get(0).get("name"));

        var wecom = noKeyService.secretBackfillDryRun(null, "wecom", null);
        assertEquals(1, number(asMap(wecom.get("summary")).get("totalChannels")));
        assertEquals("enabled wecom", asList(wecom.get("items")).get(0).get("name"));

        var feishu = noKeyService.secretBackfillDryRun(null, "feishu", null);
        assertEquals(1, number(asMap(feishu.get("summary")).get("totalChannels")));
        assertEquals("enabled feishu", asList(feishu.get("items")).get(0).get("name"));

        var allLimited = noKeyService.secretBackfillDryRun(null, null, "2");
        var response = String.valueOf(allLimited);
        assertEquals(4, number(asMap(allLimited.get("summary")).get("totalChannels")));
        assertEquals(2, asList(allLimited.get("items")).size());
        assertEquals(true, allLimited.get("truncated"));
        assertFalse(response.contains("WEBHOOKTOKEN123456"));
        assertFalse(response.contains("DISABLEDTOKEN123456"));
        assertFalse(response.contains("WESECRET123456"));
        assertFalse(response.contains(FEISHU_TOKEN));
    }

    @Test
    void secretBackfillDryRunRejectsInvalidFilters() {
        assertBadRequest("invalid_enabled_filter", () -> service.secretBackfillDryRun("abc", null, null));
        assertBadRequest("invalid_enabled_filter", () -> service.secretBackfillDryRun("", null, null));
        assertBadRequest("unsupported_channel", () -> service.secretBackfillDryRun(null, "email", null));
        assertBadRequest("invalid_limit", () -> service.secretBackfillDryRun(null, null, "0"));
        assertBadRequest("invalid_limit", () -> service.secretBackfillDryRun(null, null, "-1"));
        assertBadRequest("invalid_limit", () -> service.secretBackfillDryRun(null, null, "abc"));
        assertBadRequest("invalid_limit", () -> service.secretBackfillDryRun(null, null, ""));
        assertBadRequest("invalid_limit", () -> service.secretBackfillDryRun(null, null, "501"));
    }

    @Test
    void listDeliveriesReturnsReliabilityFieldsWithSafeDefaultsForOldRows() {
        var alertId = insertAlert("reliability fields alert");
        var channelId = insertChannel("webhook");
        insertDelivery(channelId, alertId, "old delivery", "success");
        var retrySourceId = insertDelivery(
            channelId,
            alertId,
            "retry source",
            "failed",
            "http_5xx",
            "server failed",
            true,
            null,
            2
        );
        insertDelivery(
            channelId,
            alertId,
            "retry child",
            "failed",
            "timeout",
            "webhook_timeout",
            true,
            retrySourceId,
            0
        );

        var deliveries = service.listDeliveries(50, alertId);

        assertEquals(3, deliveries.size());
        var retryChild = deliveries.stream()
            .filter(row -> "retry child".equals(row.get("title")))
            .findFirst()
            .orElseThrow();
        var oldDelivery = deliveries.stream()
            .filter(row -> "old delivery".equals(row.get("title")))
            .findFirst()
            .orElseThrow();

        assertEquals("timeout", retryChild.get("failure_type"));
        assertEquals("webhook_timeout", retryChild.get("failure_reason"));
        assertEquals(true, retryChild.get("retryable"));
        assertEquals(retrySourceId, ((Number) retryChild.get("retry_of_delivery_id")).longValue());
        assertEquals(0, ((Number) retryChild.get("retry_count")).intValue());
        assertEquals(null, oldDelivery.get("failure_type"));
        assertEquals(null, oldDelivery.get("failure_reason"));
        assertEquals(false, oldDelivery.get("retryable"));
        assertEquals(null, oldDelivery.get("retry_of_delivery_id"));
        assertEquals(0, ((Number) oldDelivery.get("retry_count")).intValue());
    }

    @Test
    void listDeliveriesCanFilterBySuccessAndFailedStatus() {
        var alertId = insertAlert("status alert");
        var channelId = insertChannel("webhook");
        insertDelivery(channelId, alertId, "success delivery", "success");
        insertDelivery(channelId, alertId, "failed delivery", "failed");

        var success = service.listDeliveries(50, null, "success", null, null);
        var failed = service.listDeliveries(50, null, "failed", null, null);

        assertEquals(1, success.size());
        assertEquals("success", success.get(0).get("status"));
        assertEquals(1, failed.size());
        assertEquals("failed", failed.get(0).get("status"));
    }

    @Test
    void listDeliveriesCanFilterBySupportedChannelTypes() {
        var alertId = insertAlert("channel type alert");
        insertDelivery(insertChannel("webhook"), alertId, "webhook delivery");
        insertDelivery(insertChannel("wecom"), alertId, "wecom delivery");
        insertDelivery(insertChannel("feishu"), alertId, "feishu delivery");

        for (var channelType : List.of("webhook", "wecom", "feishu")) {
            var deliveries = service.listDeliveries(50, null, null, channelType, null);

            assertEquals(1, deliveries.size());
            assertEquals(channelType, deliveries.get(0).get("channel_type"));
        }
    }

    @Test
    void listDeliveriesCanFilterByChannelId() {
        var alertId = insertAlert("channel id alert");
        var firstChannelId = insertChannel("webhook");
        var secondChannelId = insertChannel("webhook");
        insertDelivery(firstChannelId, alertId, "first channel");
        insertDelivery(secondChannelId, alertId, "second channel");

        var deliveries = service.listDeliveries(50, null, null, null, firstChannelId);

        assertEquals(1, deliveries.size());
        assertEquals(firstChannelId, ((Number) deliveries.get(0).get("channel_id")).longValue());
    }

    @Test
    void listDeliveriesCanCombineAlertStatusChannelTypeAndChannelIdFilters() {
        var targetAlertId = insertAlert("target alert");
        var otherAlertId = insertAlert("other alert");
        var targetChannelId = insertChannel("wecom");
        var otherChannelId = insertChannel("wecom");
        insertDelivery(targetChannelId, targetAlertId, "target", "failed");
        insertDelivery(targetChannelId, targetAlertId, "wrong status", "success");
        insertDelivery(targetChannelId, otherAlertId, "wrong alert", "failed");
        insertDelivery(otherChannelId, targetAlertId, "wrong channel", "failed");
        insertDelivery(insertChannel("webhook"), targetAlertId, "wrong type", "failed");

        var deliveries = service.listDeliveries(50, targetAlertId, "failed", "wecom", targetChannelId);

        assertEquals(1, deliveries.size());
        assertEquals("target", deliveries.get(0).get("title"));
        assertEquals(targetAlertId, ((Number) deliveries.get(0).get("alert_id")).longValue());
        assertEquals(targetChannelId, ((Number) deliveries.get(0).get("channel_id")).longValue());
        assertEquals("failed", deliveries.get(0).get("status"));
        assertEquals("wecom", deliveries.get(0).get("channel_type"));
    }

    @Test
    void listDeliveriesRejectsUnsupportedStatusAndChannelTypeFilters() {
        var invalidStatus = assertThrows(
            ResponseStatusException.class,
            () -> service.listDeliveries(50, null, "pending", null, null)
        );
        var invalidChannelType = assertThrows(
            ResponseStatusException.class,
            () -> service.listDeliveries(50, null, null, "email", null)
        );

        assertEquals(HttpStatus.BAD_REQUEST, invalidStatus.getStatusCode());
        assertEquals("invalid_delivery_status", invalidStatus.getReason());
        assertEquals(HttpStatus.BAD_REQUEST, invalidChannelType.getStatusCode());
        assertEquals("unsupported_channel", invalidChannelType.getReason());
    }

    @Test
    void legacyChannelTestAndSendPathsAreDisabledForNotificationMvp() {
        var error = assertThrows(ResponseStatusException.class, () -> service.testChannel(1L));
        assertEquals(HttpStatus.BAD_REQUEST, error.getStatusCode());
        assertEquals("use_alert_notification_endpoint", error.getReason());

        var sendError = assertThrows(ResponseStatusException.class, () -> service.send(new NotificationSendRequest(
            List.of(1L),
            1L,
            "title",
            "message",
            "high",
            Map.of()
        )));
        assertEquals(HttpStatus.BAD_REQUEST, sendError.getStatusCode());
        assertEquals("use_alert_notification_endpoint", sendError.getReason());
        assertEquals(0L, jdbcTemplate.queryForObject("select count(*) from notification_deliveries", Long.class));
    }

    @Test
    void createChannelOnlyAcceptsWebhookWithValidHttpEndpoint() {
        var webhookUrl = "https://hook.example.test/robot/PATHSECRET123456/send?Access_Token=QUERYSECRET123456&SIGNATURE=SIGNATUREQUERY123456";
        var config = new LinkedHashMap<String, Object>();
        config.put("team", "secops");
        config.put("webhookUrl", webhookUrl);
        config.put("endpointUrl", webhookUrl);
        config.put("url", webhookUrl);
        config.put("key", "KEYSECRET123456");
        config.put("token", "QUERYSECRET123456");
        config.put("secret", "SECRET123456");
        config.put("access_token", "ACCESSSECRET123456");
        config.put("signature", "SIGNATURESECRET123456");
        config.put("authorization", "Bearer AUTHSECRET123456");
        config.put("bearer", "BEARERSECRET123456");
        config.put("nested", Map.of("safeNote", "keep", "token", "NESTEDTOKEN123456"));

        var unsupported = assertThrows(ResponseStatusException.class, () -> service.createChannel(
            new NotificationChannelRequest("email", "email", "https://mail.example.test/send", null, true, Map.of())
        ));
        var invalidUrl = assertThrows(ResponseStatusException.class, () -> service.createChannel(
            new NotificationChannelRequest("bad webhook", "webhook", "/relative/path", null, true, Map.of())
        ));

        var created = service.createChannel(new NotificationChannelRequest(
            "webhook",
            "webhook",
            webhookUrl,
            "webhook only",
            true,
            config
        ));
        var id = ((Number) created.get("id")).longValue();
        var channel = service.listChannels().stream()
            .filter(row -> ((Number) row.get("id")).longValue() == id)
            .findFirst()
            .orElseThrow();
        var storedConfig = jdbcTemplate.queryForObject(
            "select cast(config_json as varchar) from notification_channels where id = ?",
            String.class,
            id
        );

        assertEquals(HttpStatus.BAD_REQUEST, unsupported.getStatusCode());
        assertEquals("unsupported_channel", unsupported.getReason());
        assertEquals(HttpStatus.BAD_REQUEST, invalidUrl.getStatusCode());
        assertEquals("invalid_webhook_url", invalidUrl.getReason());
        assertEquals("webhook", channel.get("channel_type"));
        assertEquals("encrypted", channel.get("secret_storage_status"));
        assertFalse(channel.containsKey("endpoint_url"));
        assertFalse(channel.containsKey("endpoint_secret_ciphertext"));
        assertFalse(channel.containsKey("endpoint_secret_key_version"));
        assertEquals("https://hook.example.test/...", channel.get("endpoint_masked"));
        var storedChannel = channelRow(id);
        var ciphertext = String.valueOf(storedChannel.get("endpoint_secret_ciphertext"));
        assertEquals(null, storedChannel.get("endpoint_url"));
        assertEquals("local-v1", storedChannel.get("endpoint_secret_key_version"));
        assertCiphertextFormat(ciphertext);
        assertFalse(ciphertext.contains(webhookUrl));
        assertFalse(ciphertext.contains("PATHSECRET123456"));
        assertFalse(ciphertext.contains("QUERYSECRET123456"));
        assertFalse(String.valueOf(channel.get("endpoint_masked")).contains("/robot/"));
        assertFalse(String.valueOf(channel.get("endpoint_masked")).contains("PATHSECRET123456"));
        assertFalse(String.valueOf(channel.get("endpoint_masked")).contains("QUERYSECRET123456"));
        assertFalse(String.valueOf(channel.get("endpoint_masked")).contains("SIGNATUREQUERY123456"));
        assertFalse(storedConfig.contains(webhookUrl));
        assertFalse(storedConfig.contains("PATHSECRET123456"));
        assertFalse(storedConfig.contains("QUERYSECRET123456"));
        assertFalse(storedConfig.contains("SIGNATUREQUERY123456"));
        assertFalse(storedConfig.contains("KEYSECRET123456"));
        assertFalse(storedConfig.contains("SECRET123456"));
        assertFalse(storedConfig.contains("ACCESSSECRET123456"));
        assertFalse(storedConfig.contains("SIGNATURESECRET123456"));
        assertFalse(storedConfig.contains("AUTHSECRET123456"));
        assertFalse(storedConfig.contains("BEARERSECRET123456"));
        assertFalse(storedConfig.contains("NESTEDTOKEN123456"));
        assertFalse(storedConfig.contains("webhookUrl"));
        assertFalse(storedConfig.contains("endpointUrl"));
        assertFalse(storedConfig.contains("access_token"));
        assertEquals(true, storedConfig.contains("secops"));
        assertEquals(true, storedConfig.contains("keep"));
    }

    @Test
    void updateChannelAlsoSanitizesConfigJson() {
        var created = service.createChannel(new NotificationChannelRequest(
            "webhook",
            "webhook",
            "https://hook.example.test/first?token=FIRSTSECRET123456",
            null,
            true,
            Map.of("team", "secops")
        ));
        var id = ((Number) created.get("id")).longValue();
        var updatedUrl = "https://hook.example.test/second?access_token=UPDATEDSECRET123456";

        service.updateChannel(id, new NotificationChannelRequest(
            "webhook updated",
            "webhook",
            updatedUrl,
            null,
            true,
            Map.of(
                "team", "secops",
                "endpoint_url", updatedUrl,
                "Access_Token", "UPDATEDSECRET123456",
                "nested", List.of(Map.of("Authorization", "Bearer AUTHSECRET123456"), "keep")
            )
        ));

        var storedConfig = jdbcTemplate.queryForObject(
            "select cast(config_json as varchar) from notification_channels where id = ?",
            String.class,
            id
        );
        var storedChannel = channelRow(id);
        var ciphertext = String.valueOf(storedChannel.get("endpoint_secret_ciphertext"));

        assertEquals(null, storedChannel.get("endpoint_url"));
        assertEquals("encrypted", storedChannel.get("secret_storage_status"));
        assertCiphertextFormat(ciphertext);
        assertFalse(ciphertext.contains(updatedUrl));
        assertFalse(ciphertext.contains("UPDATEDSECRET123456"));
        assertFalse(storedConfig.contains(updatedUrl));
        assertFalse(storedConfig.contains("UPDATEDSECRET123456"));
        assertFalse(storedConfig.contains("AUTHSECRET123456"));
        assertFalse(storedConfig.contains("endpoint_url"));
        assertFalse(storedConfig.contains("Access_Token"));
        assertEquals(true, storedConfig.contains("secops"));
        assertEquals(true, storedConfig.contains("keep"));
    }

    @Test
    void updateEncryptedChannelWithoutEndpointPreservesSecretAndDoesNotRequireMasterKey() {
        var created = service.createChannel(new NotificationChannelRequest(
            "encrypted",
            "webhook",
            "https://hook.example.test/first?token=FIRSTSECRET123456",
            "old description",
            true,
            Map.of("team", "secops")
        ));
        var id = ((Number) created.get("id")).longValue();
        var before = channelRow(id);
        var missingKeyService = new NotificationService(
            jdbcTemplate,
            new ObjectMapper(),
            new NotificationSecretStore("")
        );

        missingKeyService.updateChannel(
            id,
            new NotificationChannelRequest("renamed", null, null, null, null, null),
            Set.of("name")
        );

        var after = channelRow(id);
        assertEquals("renamed", after.get("name"));
        assertEquals("old description", after.get("description"));
        assertEquals(true, after.get("enabled"));
        assertEquals(before.get("endpoint_secret_ciphertext"), after.get("endpoint_secret_ciphertext"));
        assertEquals(before.get("endpoint_secret_key_version"), after.get("endpoint_secret_key_version"));
        assertEquals(before.get("endpoint_masked"), after.get("endpoint_masked"));
        assertEquals("encrypted", after.get("secret_storage_status"));
        assertEquals(null, after.get("endpoint_url"));
    }

    @Test
    void updateEncryptedChannelWithNewEndpointReencryptsAndKeepsSecretsOutOfResponses() {
        var created = service.createChannel(new NotificationChannelRequest(
            "encrypted",
            "webhook",
            "https://hook.example.test/first?token=FIRSTSECRET123456",
            null,
            true,
            Map.of("team", "secops")
        ));
        var id = ((Number) created.get("id")).longValue();
        var before = channelRow(id);
        var updatedUrl = "https://hook.example.test/second?token=SECONDSECRET123456";

        service.updateChannel(
            id,
            new NotificationChannelRequest("encrypted", "webhook", updatedUrl, null, true, Map.of()),
            Set.of("name", "channelType", "webhookUrl", "enabled", "config")
        );

        var after = channelRow(id);
        var response = service.listChannels().stream()
            .filter(row -> ((Number) row.get("id")).longValue() == id)
            .findFirst()
            .orElseThrow();

        assertEquals(null, after.get("endpoint_url"));
        assertEquals("encrypted", after.get("secret_storage_status"));
        assertEquals("local-v1", after.get("endpoint_secret_key_version"));
        assertNotEquals(before.get("endpoint_secret_ciphertext"), after.get("endpoint_secret_ciphertext"));
        assertEquals("https://hook.example.test/...", after.get("endpoint_masked"));
        assertFalse(String.valueOf(after.get("endpoint_secret_ciphertext")).contains("FIRSTSECRET123456"));
        assertFalse(String.valueOf(after.get("endpoint_secret_ciphertext")).contains("SECONDSECRET123456"));
        assertFalse(response.containsKey("endpoint_url"));
        assertFalse(response.containsKey("endpoint_secret_ciphertext"));
        assertFalse(response.containsKey("endpoint_secret_key_version"));
        assertFalse(String.valueOf(response).contains("FIRSTSECRET123456"));
        assertFalse(String.valueOf(response).contains("SECONDSECRET123456"));
        assertFalse(String.valueOf(response).contains(updatedUrl));
    }

    @Test
    void updateBlankEndpointRejectsWithoutClearingExistingSecret() {
        var created = service.createChannel(new NotificationChannelRequest(
            "encrypted",
            "webhook",
            "https://hook.example.test/first?token=FIRSTSECRET123456",
            null,
            true,
            Map.of()
        ));
        var id = ((Number) created.get("id")).longValue();
        var before = channelRow(id);

        var error = assertThrows(ResponseStatusException.class, () -> service.updateChannel(
            id,
            new NotificationChannelRequest("encrypted", "webhook", "   ", null, true, Map.of()),
            Set.of("webhookUrl")
        ));

        var after = channelRow(id);
        assertEquals(HttpStatus.BAD_REQUEST, error.getStatusCode());
        assertEquals("invalid_webhook_url", error.getReason());
        assertEquals(before.get("endpoint_secret_ciphertext"), after.get("endpoint_secret_ciphertext"));
        assertEquals(before.get("endpoint_masked"), after.get("endpoint_masked"));
        assertEquals(null, after.get("endpoint_url"));
    }

    @Test
    void updateLegacyPlaintextWithoutEndpointPreservesFallbackAndDoesNotRequireMasterKey() {
        var id = insertChannel("webhook", "https://legacy.example.test/hook?token=LEGACYSECRET123456");
        var missingKeyService = new NotificationService(
            jdbcTemplate,
            new ObjectMapper(),
            new NotificationSecretStore("")
        );

        missingKeyService.updateChannel(
            id,
            new NotificationChannelRequest("legacy renamed", null, null, null, null, null),
            Set.of("name")
        );

        var after = channelRow(id);
        var response = service.listChannels().stream()
            .filter(row -> ((Number) row.get("id")).longValue() == id)
            .findFirst()
            .orElseThrow();
        assertEquals("legacy renamed", after.get("name"));
        assertEquals("https://legacy.example.test/hook?token=LEGACYSECRET123456", after.get("endpoint_url"));
        assertEquals(null, after.get("endpoint_secret_ciphertext"));
        assertEquals(null, after.get("endpoint_secret_key_version"));
        assertEquals("legacy_plaintext", after.get("secret_storage_status"));
        assertFalse(response.containsKey("endpoint_url"));
        assertEquals("legacy_plaintext", response.get("secret_storage_status"));
        assertFalse(String.valueOf(response).contains("LEGACYSECRET123456"));
    }

    @Test
    void updateLegacyPlaintextWithNewEndpointConvertsToEncryptedStorage() {
        var id = insertChannel("webhook", "https://legacy.example.test/hook?token=LEGACYSECRET123456");
        var updatedUrl = "https://hook.example.test/new?token=NEWSECRET123456";

        service.updateChannel(
            id,
            new NotificationChannelRequest("legacy upgraded", "webhook", updatedUrl, null, true, Map.of()),
            Set.of("name", "channelType", "webhookUrl", "enabled", "config")
        );

        var after = channelRow(id);
        var response = service.listChannels().stream()
            .filter(row -> ((Number) row.get("id")).longValue() == id)
            .findFirst()
            .orElseThrow();
        assertEquals(null, after.get("endpoint_url"));
        assertEquals("encrypted", after.get("secret_storage_status"));
        assertEquals("local-v1", after.get("endpoint_secret_key_version"));
        assertCiphertextFormat(String.valueOf(after.get("endpoint_secret_ciphertext")));
        assertFalse(String.valueOf(after.get("endpoint_secret_ciphertext")).contains("LEGACYSECRET123456"));
        assertFalse(String.valueOf(after.get("endpoint_secret_ciphertext")).contains("NEWSECRET123456"));
        assertFalse(String.valueOf(response).contains("LEGACYSECRET123456"));
        assertFalse(String.valueOf(response).contains("NEWSECRET123456"));
        assertFalse(response.containsKey("endpoint_url"));
    }

    @Test
    void updateMissingChannelWithoutEndpointRequiresFinalDisabledState() {
        var disabledMissingId = insertMissingChannel("webhook", false);
        var enabledMissingId = insertMissingChannel("webhook", true);

        service.updateChannel(
            disabledMissingId,
            new NotificationChannelRequest("still missing", null, null, null, null, null),
            Set.of("name")
        );
        var disabledAfter = channelRow(disabledMissingId);
        assertEquals("still missing", disabledAfter.get("name"));
        assertEquals("missing", disabledAfter.get("secret_storage_status"));
        assertEquals(false, disabledAfter.get("enabled"));
        assertEquals(null, disabledAfter.get("endpoint_url"));
        assertEquals(null, disabledAfter.get("endpoint_secret_ciphertext"));

        var enableError = assertThrows(ResponseStatusException.class, () -> service.updateChannel(
            disabledMissingId,
            new NotificationChannelRequest(null, null, null, null, true, null),
            Set.of("enabled")
        ));
        var renameEnabledMissingError = assertThrows(ResponseStatusException.class, () -> service.updateChannel(
            enabledMissingId,
            new NotificationChannelRequest("invalid enabled missing", null, null, null, null, null),
            Set.of("name")
        ));

        assertEquals("notification_secret_unavailable", enableError.getReason());
        assertEquals("notification_secret_unavailable", renameEnabledMissingError.getReason());
        assertEquals(false, channelRow(disabledMissingId).get("enabled"));
        assertEquals("missing channel", channelRow(enabledMissingId).get("name"));
    }

    @Test
    void updateMissingChannelWithEndpointConvertsToEncryptedStorage() {
        var id = insertMissingChannel("webhook", false);
        var endpoint = "https://hook.example.test/new?token=NEWSECRET123456";

        service.updateChannel(
            id,
            new NotificationChannelRequest("configured", "webhook", endpoint, null, true, Map.of()),
            Set.of("name", "channelType", "webhookUrl", "enabled", "config")
        );

        var after = channelRow(id);
        assertEquals("configured", after.get("name"));
        assertEquals(true, after.get("enabled"));
        assertEquals(null, after.get("endpoint_url"));
        assertEquals("encrypted", after.get("secret_storage_status"));
        assertCiphertextFormat(String.valueOf(after.get("endpoint_secret_ciphertext")));
        assertFalse(String.valueOf(after.get("endpoint_secret_ciphertext")).contains("NEWSECRET123456"));
    }

    @Test
    void partialUpdatePreservesMissingFieldsAndHonorsExplicitNullOrFalseValues() {
        var created = service.createChannel(new NotificationChannelRequest(
            "original",
            "webhook",
            "https://hook.example.test/original?token=ORIGINALSECRET123456",
            "original description",
            true,
            Map.of("team", "secops")
        ));
        var id = ((Number) created.get("id")).longValue();

        service.updateChannel(
            id,
            new NotificationChannelRequest("renamed", null, null, null, null, null),
            Set.of("name")
        );
        var afterNameOnly = channelRow(id);
        assertEquals("renamed", afterNameOnly.get("name"));
        assertEquals("original description", afterNameOnly.get("description"));
        assertEquals(true, afterNameOnly.get("enabled"));
        assertTrue(configJson(id).contains("secops"));

        service.updateChannel(
            id,
            new NotificationChannelRequest(null, null, null, null, null, null),
            Set.of("description")
        );
        assertEquals(null, channelRow(id).get("description"));

        service.updateChannel(
            id,
            new NotificationChannelRequest(null, null, null, null, false, null),
            Set.of("enabled")
        );
        assertEquals(false, channelRow(id).get("enabled"));
        assertEquals("disabled", channelRow(id).get("status"));

        service.updateChannel(
            id,
            new NotificationChannelRequest(null, null, null, null, null, Map.of()),
            Set.of("config")
        );
        assertFalse(configJson(id).contains("secops"));
    }

    @Test
    void updateChannelTypeIsImmutable() {
        var created = service.createChannel(new NotificationChannelRequest(
            "webhook",
            "webhook",
            "https://hook.example.test/original?token=ORIGINALSECRET123456",
            null,
            true,
            Map.of()
        ));
        var id = ((Number) created.get("id")).longValue();

        service.updateChannel(
            id,
            new NotificationChannelRequest("same type", "webhook", null, null, null, null),
            Set.of("name", "channelType")
        );

        var error = assertThrows(ResponseStatusException.class, () -> service.updateChannel(
            id,
            new NotificationChannelRequest("wrong type", "wecom", null, null, null, null),
            Set.of("channelType")
        ));

        assertEquals(HttpStatus.BAD_REQUEST, error.getStatusCode());
        assertEquals("channel_type_immutable", error.getReason());
        assertEquals("webhook", channelRow(id).get("channel_type"));
    }

    @Test
    void listDeliveriesRedactsStoredResponseFailureReasonAndPayloadPreview() {
        var alertId = insertAlert("historical delivery alert");
        var endpoint = "https://hook.example.test/webhook?token=DELIVERYSECRET123456&tenant=secops";
        var channelId = insertChannel("webhook", endpoint);
        jdbcTemplate.update("""
            insert into notification_deliveries(
                channel_id, alert_id, title, severity, status, response_code, response_body,
                payload_json, failure_type, failure_reason
            )
            values (?, ?, 'historical leak', 'high', 'failed', 500, ?,
                cast(? as jsonb), 'http_5xx', ?)
            """,
            channelId,
            alertId,
            "response echoed " + endpoint + " Authorization: Bearer BODYSECRET123456",
            "{\"message\":\"payload token=DELIVERYSECRET123456\",\"tenant\":\"secops\",\"Authorization\":\"Bearer PAYLOADSECRET123456\"}",
            "failure access_token=DELIVERYSECRET123456"
        );

        var delivery = service.listDeliveries(50, alertId).get(0);

        assertFalse(String.valueOf(delivery.get("response_body")).contains("DELIVERYSECRET123456"));
        assertFalse(String.valueOf(delivery.get("response_body")).contains("BODYSECRET123456"));
        assertFalse(String.valueOf(delivery.get("failure_reason")).contains("DELIVERYSECRET123456"));
        assertFalse(String.valueOf(delivery.get("payload_json")).contains("DELIVERYSECRET123456"));
        assertTrue(String.valueOf(delivery.get("payload_json")).contains("PAYLOADSECRET123456"));
        assertTrue(String.valueOf(delivery.get("payload_json")).contains("secops"));
        assertFalse(delivery.containsKey("endpoint_url"));
    }

    @Test
    void createWeComChannelRequiresHttpsRobotUrlAndDoesNotDuplicateKeyInConfigOrMaskedEndpoint() {
        var httpUrl = assertThrows(ResponseStatusException.class, () -> service.createChannel(
            new NotificationChannelRequest(
                "bad wecom",
                "wecom",
                "http://qyapi.weixin.qq.com/cgi-bin/webhook/send?key=WESECRET123456",
                null,
                true,
                Map.of()
            )
        ));
        var missingKey = assertThrows(ResponseStatusException.class, () -> service.createChannel(
            new NotificationChannelRequest(
                "bad wecom",
                "wecom",
                "https://qyapi.weixin.qq.com/cgi-bin/webhook/send?not_key=WESECRET123456",
                null,
                true,
                Map.of()
            )
        ));

        var created = service.createChannel(new NotificationChannelRequest(
            "wecom",
            "wecom",
            "https://qyapi.weixin.qq.com/cgi-bin/webhook/send?key=WESECRET123456",
            "wecom robot",
            true,
            Map.of(
                "team", "secops",
                "key", "WESECRET123456",
                "robotKey", "WESECRET123456",
                "webhookUrl", "https://qyapi.weixin.qq.com/cgi-bin/webhook/send?key=WESECRET123456"
            )
        ));
        var id = ((Number) created.get("id")).longValue();
        var channel = service.listChannels().stream()
            .filter(row -> ((Number) row.get("id")).longValue() == id)
            .findFirst()
            .orElseThrow();
        var storedConfig = jdbcTemplate.queryForObject(
            "select cast(config_json as varchar) from notification_channels where id = ?",
            String.class,
            id
        );

        assertEquals(HttpStatus.BAD_REQUEST, httpUrl.getStatusCode());
        assertEquals("invalid_wecom_webhook_url", httpUrl.getReason());
        assertEquals(HttpStatus.BAD_REQUEST, missingKey.getStatusCode());
        assertEquals("invalid_wecom_webhook_url", missingKey.getReason());
        assertEquals("wecom", channel.get("channel_type"));
        assertEquals("encrypted", channel.get("secret_storage_status"));
        assertEquals("https://qyapi.weixin.qq.com/...", channel.get("endpoint_masked"));
        var storedChannel = channelRow(id);
        var ciphertext = String.valueOf(storedChannel.get("endpoint_secret_ciphertext"));
        assertEquals(null, storedChannel.get("endpoint_url"));
        assertEquals("local-v1", storedChannel.get("endpoint_secret_key_version"));
        assertCiphertextFormat(ciphertext);
        assertFalse(ciphertext.contains("WESECRET123456"));
        assertFalse(ciphertext.contains("qyapi.weixin.qq.com"));
        assertFalse(String.valueOf(channel.get("endpoint_masked")).contains("WESECRET123456"));
        assertFalse(storedConfig.contains("WESECRET123456"));
        assertFalse(storedConfig.contains("qyapi.weixin.qq.com"));
        assertFalse(storedConfig.contains("webhookUrl"));
        assertFalse(storedConfig.contains("robotKey"));
        assertEquals(true, storedConfig.contains("secops"));
    }

    @Test
    void createFeishuChannelRequiresOfficialHttpsHookAndDoesNotStoreTokenInConfigOrMaskedEndpoint() {
        var httpUrl = assertThrows(ResponseStatusException.class, () -> service.createChannel(
            new NotificationChannelRequest(
                "bad feishu",
                "feishu",
                "http://open.feishu.cn/open-apis/bot/v2/hook/" + FEISHU_TOKEN,
                null,
                true,
                Map.of()
            )
        ));
        var wrongHost = assertThrows(ResponseStatusException.class, () -> service.createChannel(
            new NotificationChannelRequest(
                "bad feishu",
                "feishu",
                "https://example.test/open-apis/bot/v2/hook/" + FEISHU_TOKEN,
                null,
                true,
                Map.of()
            )
        ));
        var emptyToken = assertThrows(ResponseStatusException.class, () -> service.createChannel(
            new NotificationChannelRequest(
                "bad feishu",
                "feishu",
                "https://open.feishu.cn/open-apis/bot/v2/hook/",
                null,
                true,
                Map.of()
            )
        ));
        var extraPathSegment = assertThrows(ResponseStatusException.class, () -> service.createChannel(
            new NotificationChannelRequest(
                "bad feishu",
                "feishu",
                FEISHU_URL + "/extra",
                null,
                true,
                Map.of()
            )
        ));

        var created = service.createChannel(new NotificationChannelRequest(
            "feishu",
            "feishu",
            FEISHU_URL,
            "feishu robot",
            true,
            Map.of(
                "team", "secops",
                "token", FEISHU_TOKEN,
                "webhookUrl", FEISHU_URL,
                "endpointUrl", FEISHU_URL
            )
        ));
        var id = ((Number) created.get("id")).longValue();
        var channel = service.listChannels().stream()
            .filter(row -> ((Number) row.get("id")).longValue() == id)
            .findFirst()
            .orElseThrow();
        var storedConfig = jdbcTemplate.queryForObject(
            "select cast(config_json as varchar) from notification_channels where id = ?",
            String.class,
            id
        );

        assertEquals(HttpStatus.BAD_REQUEST, httpUrl.getStatusCode());
        assertEquals("invalid_feishu_webhook_url", httpUrl.getReason());
        assertEquals(HttpStatus.BAD_REQUEST, wrongHost.getStatusCode());
        assertEquals("invalid_feishu_webhook_url", wrongHost.getReason());
        assertEquals(HttpStatus.BAD_REQUEST, emptyToken.getStatusCode());
        assertEquals("invalid_feishu_webhook_url", emptyToken.getReason());
        assertEquals(HttpStatus.BAD_REQUEST, extraPathSegment.getStatusCode());
        assertEquals("invalid_feishu_webhook_url", extraPathSegment.getReason());
        assertEquals("feishu", channel.get("channel_type"));
        assertEquals("encrypted", channel.get("secret_storage_status"));
        assertEquals("https://open.feishu.cn/open-apis/bot/v2/hook/...", channel.get("endpoint_masked"));
        var storedChannel = channelRow(id);
        var ciphertext = String.valueOf(storedChannel.get("endpoint_secret_ciphertext"));
        assertEquals(null, storedChannel.get("endpoint_url"));
        assertEquals("local-v1", storedChannel.get("endpoint_secret_key_version"));
        assertCiphertextFormat(ciphertext);
        assertFalse(ciphertext.contains(FEISHU_TOKEN));
        assertFalse(ciphertext.contains(FEISHU_URL));
        assertFalse(String.valueOf(channel.get("endpoint_masked")).contains(FEISHU_TOKEN));
        assertFalse(storedConfig.contains(FEISHU_TOKEN));
        assertFalse(storedConfig.contains(FEISHU_URL));
        assertFalse(storedConfig.contains("webhookUrl"));
        assertFalse(storedConfig.contains("endpointUrl"));
        assertEquals(true, storedConfig.contains("secops"));
    }

    @Test
    void createAndUpdateRejectEncryptedChannelWhenMasterKeyIsMissingOrInvalid() {
        var missingKeyService = new NotificationService(
            jdbcTemplate,
            new ObjectMapper(),
            new NotificationSecretStore("")
        );
        var invalidKeyService = new NotificationService(
            jdbcTemplate,
            new ObjectMapper(),
            new NotificationSecretStore("not-base64")
        );

        var missing = assertThrows(ResponseStatusException.class, () -> missingKeyService.createChannel(
            new NotificationChannelRequest(
                "missing key",
                "webhook",
                "https://hook.example.test/send?token=WEBHOOKTOKEN123456",
                null,
                true,
                Map.of()
            )
        ));
        var invalid = assertThrows(ResponseStatusException.class, () -> invalidKeyService.createChannel(
            new NotificationChannelRequest(
                "invalid key",
                "webhook",
                "https://hook.example.test/send?token=WEBHOOKTOKEN123456",
                null,
                true,
                Map.of()
            )
        ));

        assertEquals(HttpStatus.BAD_REQUEST, missing.getStatusCode());
        assertEquals("notification_secret_key_missing", missing.getReason());
        assertEquals(HttpStatus.BAD_REQUEST, invalid.getStatusCode());
        assertEquals("notification_secret_key_invalid", invalid.getReason());
        assertEquals(0L, jdbcTemplate.queryForObject("select count(*) from notification_channels", Long.class));

        var created = service.createChannel(new NotificationChannelRequest(
            "valid",
            "webhook",
            "https://hook.example.test/send?token=WEBHOOKTOKEN123456",
            null,
            true,
            Map.of()
        ));
        var id = ((Number) created.get("id")).longValue();

        var updateMissing = assertThrows(ResponseStatusException.class, () -> missingKeyService.updateChannel(
            id,
            new NotificationChannelRequest(
                "missing update",
                "webhook",
                "https://hook.example.test/send?token=UPDATEDTOKEN123456",
                null,
                true,
                Map.of()
            )
        ));

        assertEquals(HttpStatus.BAD_REQUEST, updateMissing.getStatusCode());
        assertEquals("notification_secret_key_missing", updateMissing.getReason());
    }

    private Long insertAlert(String title) {
        return insertAndReturnId("insert into alerts(title, severity, status) values (?, 'high', 'open')", title);
    }

    private Long insertChannel(String channelType) {
        return insertAndReturnId(
            "insert into notification_channels(name, channel_type) values (?, ?)",
            channelType,
            channelType
        );
    }

    private Long insertChannel(String channelType, String endpointUrl) {
        return insertAndReturnId(
            "insert into notification_channels(name, channel_type, endpoint_url) values (?, ?, ?)",
            channelType,
            channelType,
            endpointUrl
        );
    }

    private Long insertLegacyChannel(String name, String endpointUrl, boolean enabled) {
        return insertAndReturnId("""
            insert into notification_channels(
                name, channel_type, endpoint_url, secret_storage_status, enabled, status
            )
            values (?, 'webhook', ?, 'legacy_plaintext', ?, ?)
            """, name, endpointUrl, enabled, enabled ? "ready" : "disabled");
    }

    private Long insertMissingChannel(String channelType, boolean enabled) {
        return insertAndReturnId("""
            insert into notification_channels(
                name, channel_type, endpoint_url, endpoint_masked, secret_storage_status, enabled, status
            )
            values ('missing channel', ?, null, 'demo://not-configured', 'missing', ?, ?)
            """, channelType, enabled, enabled ? "ready" : "disabled");
    }

    private Long insertEncryptedChannel(String name, String channelType, boolean enabled) {
        return insertAndReturnId("""
            insert into notification_channels(
                name, channel_type, endpoint_url, endpoint_secret_ciphertext,
                endpoint_secret_key_version, endpoint_masked, secret_storage_status, enabled, status
            )
            values (?, ?, null, 'v1:ciphertext', 'local-v1', 'https://encrypted.example.test/...', 'encrypted', ?, ?)
            """, name, channelType, enabled, enabled ? "ready" : "disabled");
    }

    private Long insertLegacyChannel(String name, String channelType, String endpointUrl, boolean enabled) {
        return insertAndReturnId("""
            insert into notification_channels(
                name, channel_type, endpoint_url, secret_storage_status, enabled, status
            )
            values (?, ?, ?, 'legacy_plaintext', ?, ?)
            """, name, channelType, endpointUrl, enabled, enabled ? "ready" : "disabled");
    }

    private List<Map<String, Object>> channelSnapshots() {
        return jdbcTemplate.queryForList("""
            select id, endpoint_url, endpoint_secret_ciphertext, endpoint_secret_key_version,
                   endpoint_masked, secret_storage_status, updated_at, enabled, status
            from notification_channels
            order by id
            """);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> asMap(Object value) {
        return (Map<String, Object>) value;
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> asList(Object value) {
        return (List<Map<String, Object>>) value;
    }

    private long number(Object value) {
        return ((Number) value).longValue();
    }

    private void assertDryRunItem(
        List<Map<String, Object>> items,
        String name,
        String dryRunStatus,
        String blockReason,
        boolean migrationEligible
    ) {
        var item = items.stream()
            .filter(row -> name.equals(row.get("name")))
            .findFirst()
            .orElseThrow();
        assertEquals(dryRunStatus, item.get("dryRunStatus"));
        assertEquals(blockReason, item.get("blockReason"));
        assertEquals(migrationEligible, item.get("migrationEligible"));
        assertFalse(item.containsKey("endpoint_url"));
        assertFalse(item.containsKey("endpoint_secret_ciphertext"));
        assertFalse(item.containsKey("endpoint_secret_key_version"));
    }

    private void assertBadRequest(String reason, Runnable action) {
        var error = assertThrows(ResponseStatusException.class, action::run);
        assertEquals(HttpStatus.BAD_REQUEST, error.getStatusCode());
        assertEquals(reason, error.getReason());
    }

    private String configJson(long id) {
        return jdbcTemplate.queryForObject(
            "select cast(config_json as varchar) from notification_channels where id = ?",
            String.class,
            id
        );
    }

    private Map<String, Object> channelRow(long id) {
        return jdbcTemplate.queryForMap("select * from notification_channels where id = ?", id);
    }

    private void assertCiphertextFormat(String ciphertext) {
        var parts = ciphertext.split(":", 3);
        assertEquals(3, parts.length);
        assertEquals("v1", parts[0]);
        assertEquals(12, Base64.getDecoder().decode(parts[1]).length);
        assertTrue(Base64.getDecoder().decode(parts[2]).length > 0);
    }

    private void insertDelivery(Long channelId, Long alertId, String title) {
        insertDelivery(channelId, alertId, title, "success");
    }

    private void insertDelivery(Long channelId, Long alertId, String title, String status) {
        jdbcTemplate.update("""
            insert into notification_deliveries(
                channel_id, alert_id, title, severity, status, response_code, response_body, payload_json
            )
            values (?, ?, ?, 'high', ?, 200, 'ok', cast('{}' as jsonb))
            """, channelId, alertId, title, status);
    }

    private Long insertDelivery(
        Long channelId,
        Long alertId,
        String title,
        String status,
        String failureType,
        String failureReason,
        boolean retryable,
        Long retryOfDeliveryId,
        int retryCount
    ) {
        return insertAndReturnId("""
            insert into notification_deliveries(
                channel_id, alert_id, title, severity, status, response_code, response_body,
                payload_json, failure_type, failure_reason, retryable, retry_of_delivery_id, retry_count
            )
            values (?, ?, ?, 'high', ?, 500, 'failed', cast('{}' as jsonb), ?, ?, ?, ?, ?)
            """,
            channelId, alertId, title, status, failureType, failureReason, retryable, retryOfDeliveryId, retryCount);
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
}
