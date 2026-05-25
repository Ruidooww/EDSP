package com.edsp.core.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import org.flywaydb.core.Flyway;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class DemoDataSeederTest {
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void setUp() {
        var dataSource = new JdbcDataSource();
        dataSource.setURL(
            "jdbc:h2:mem:demo_data_seeder_test_" + System.nanoTime()
                + ";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;"
                + "DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1;"
                + "INIT=CREATE DOMAIN IF NOT EXISTS TIMESTAMPTZ AS TIMESTAMP WITH TIME ZONE\\;"
                + "CREATE DOMAIN IF NOT EXISTS JSONB AS JSON"
        );
        dataSource.setUser("sa");
        dataSource.setPassword("");

        Flyway.configure()
            .dataSource(dataSource)
            .locations("classpath:db/migration")
            .cleanDisabled(false)
            .load()
            .clean();
        Flyway.configure()
            .dataSource(dataSource)
            .locations("classpath:db/migration")
            .load()
            .migrate();

        jdbcTemplate = new JdbcTemplate(dataSource);
    }

    @Test
    void seedsDemoNotificationChannelsWithoutPlaintextSecrets() {
        jdbcTemplate.update("""
            insert into notification_channels(
                name, channel_type, endpoint_url, endpoint_secret_ciphertext,
                endpoint_secret_key_version, endpoint_masked, secret_storage_status,
                config_json, enabled, status
            )
            values ('安全运营 Webhook', 'webhook', 'https://old.example.test/hook',
                    'v1:old-nonce:old-ciphertext', 'local-v1', 'https://old.example.test/...',
                    'encrypted', cast('{}' as jsonb), true, 'ready')
            """);

        new DemoDataSeeder(jdbcTemplate, true).run(null);

        var rows = jdbcTemplate.queryForList("""
            select channel_type, endpoint_url, endpoint_secret_ciphertext, endpoint_secret_key_version,
                   endpoint_masked, secret_storage_status, enabled, status
            from notification_channels
            order by channel_type
            """);

        assertEquals(5, rows.size());
        for (var row : rows) {
            assertEquals(null, row.get("endpoint_url"));
            assertEquals(null, row.get("endpoint_secret_ciphertext"));
            assertEquals(null, row.get("endpoint_secret_key_version"));
            assertEquals("demo://not-configured", row.get("endpoint_masked"));
            assertEquals("missing", row.get("secret_storage_status"));
            assertEquals(false, row.get("enabled"));
            assertEquals("disabled", row.get("status"));
        }
        var allEndpoints = jdbcTemplate.queryForList(
            "select coalesce(endpoint_url, '') from notification_channels",
            String.class
        );
        assertFalse(allEndpoints.stream().anyMatch(value -> value.contains("key=")));
        assertFalse(allEndpoints.stream().anyMatch(value -> value.contains("/open-apis/bot/v2/hook/")));
    }
}
