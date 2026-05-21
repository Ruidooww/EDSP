package com.edsp.core.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.edsp.core.dto.DataSourceRequest;
import com.edsp.core.service.SqlServerMetadataService;
import com.edsp.core.support.CoreRequestSupport;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.flywaydb.core.Flyway;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.server.ResponseStatusException;

class DataSourceControllerTest {
    private DataSourceController controller;

    @BeforeEach
    void setUp() {
        var dataSource = new JdbcDataSource();
        dataSource.setURL(
            "jdbc:h2:mem:data_source_controller_test;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;"
                + "DB_CLOSE_DELAY=-1;INIT=CREATE DOMAIN IF NOT EXISTS TIMESTAMPTZ AS TIMESTAMP WITH TIME ZONE\\;"
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

        var objectMapper = new ObjectMapper();
        controller = new DataSourceController(
            new JdbcTemplate(dataSource),
            new SqlServerMetadataService(objectMapper),
            new CoreRequestSupport(objectMapper)
        );
    }

    @Test
    void createReturnsGeneratedId() {
        var response = controller.create(new DataSourceRequest(
            "External Security Platform",
            "security_platform",
            "database",
            "demo",
            "{}",
            true
        ));

        var id = (Number) response.data().get("id");
        assertTrue(id.longValue() > 0);
    }

    @Test
    void missingDataSourceIdReturnsNotFound() {
        assertNotFound(() -> controller.testConnection(999L));
        assertNotFound(() -> controller.tables(999L, "", "", 100));
        assertNotFound(() -> controller.columns(999L, "demo", "dbo", "alerts"));
        assertNotFound(() -> controller.sample(999L, "demo", "dbo", "alerts", 10));
    }

    private void assertNotFound(Runnable action) {
        var ex = assertThrows(ResponseStatusException.class, action::run);
        assertEquals(HttpStatus.NOT_FOUND, ex.getStatusCode());
        assertEquals("Data source not found: 999", ex.getReason());
    }
}
