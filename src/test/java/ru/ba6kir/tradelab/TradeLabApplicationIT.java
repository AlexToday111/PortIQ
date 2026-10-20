package ru.ba6kir.tradelab;

import java.time.Instant;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.ApplicationContext;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Transactional;
import org.telegram.telegrambots.longpolling.starter.TelegramBotInitializer;
import org.telegram.telegrambots.meta.generics.TelegramClient;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import ru.ba6kir.tradelab.common.metadata.ApplicationMetadata;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"tradelab.telegram.enabled=false", "tradelab.invest.enabled=false", "tradelab.invest.token="})
@Testcontainers(disabledWithoutDocker = true)
class TradeLabApplicationIT {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-bookworm");

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("tradelab.database.host", POSTGRES::getHost);
        registry.add("tradelab.database.port", POSTGRES::getFirstMappedPort);
        registry.add("tradelab.database.name", POSTGRES::getDatabaseName);
        registry.add("tradelab.database.username", POSTGRES::getUsername);
        registry.add("tradelab.database.password", POSTGRES::getPassword);
    }

    @Autowired
    private ApplicationContext context;
    @Autowired
    private Flyway flyway;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private TestRestTemplate http;
    @PersistenceContext
    private EntityManager entityManager;

    @Test
    void startsFullApplicationContext() {
        assertThat(context.getBean(TradeLabApplication.class)).isNotNull();
        assertThat(context.getBeansOfType(TelegramClient.class)).isEmpty();
        assertThat(context.getBeansOfType(TelegramBotInitializer.class)).isEmpty();
        assertThat(entityManager.getEntityManagerFactory().getProperties())
                .containsEntry("hibernate.hbm2ddl.auto", "validate");
    }

    @Test
    @Transactional
    void migratesPostgresAndReadsMetadataThroughJpa() {
        assertThat(flyway.info().current().getVersion().getVersion()).isEqualTo("5");
        assertThat(flyway.info().pending()).isEmpty();
        assertThat(flyway.validateWithResult().validationSuccessful).isTrue();
        var metadata = entityManager.find(ApplicationMetadata.class, (short) 1);
        assertThat(metadata.getApplicationName()).isEqualTo("TradeLab Portfolio");
        assertThat(metadata.getInstalledAt()).isBeforeOrEqualTo(Instant.now());
        assertThat(jdbc.queryForObject("SHOW TIME ZONE", String.class)).isEqualTo("UTC");
        assertThat(jdbc.queryForObject("""
                SELECT data_type FROM information_schema.columns
                WHERE table_name = 'application_metadata' AND column_name = 'installed_at'
                """, String.class)).isEqualTo("timestamp with time zone");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM flyway_schema_history WHERE success", Long.class))
                .isEqualTo(5L);
    }

    @Test
    void rerunningMigrationsPreservesInstallationMetadata() {
        var installedAt = jdbc.queryForObject("SELECT installed_at FROM application_metadata WHERE id = 1",
                java.time.OffsetDateTime.class);

        assertThat(flyway.migrate().migrationsExecuted).isZero();
        assertThat(jdbc.queryForObject("SELECT installed_at FROM application_metadata WHERE id = 1",
                java.time.OffsetDateTime.class)).isEqualTo(installedAt);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM application_metadata", Long.class)).isEqualTo(1L);
    }

    @Test
    void reportsHealthyApplicationAndDatabaseReadiness() {
        for (String endpoint : new String[]{"/actuator/health", "/actuator/health/readiness"}) {
            var response = http.getForEntity(endpoint, JsonNode.class);
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(response.getBody()).isNotNull();
            assertThat(response.getBody().path("status").asText()).isEqualTo("UP");
            assertThat(response.getBody().has("components")).isFalse();
        }
        assertThat(http.getForEntity("/actuator/info", String.class).getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void doesNotExposeOtherActuatorEndpoints() {
        for (String endpoint : new String[]{"/actuator", "/actuator/env", "/actuator/configprops",
                "/actuator/beans", "/actuator/loggers", "/actuator/metrics", "/actuator/shutdown"}) {
            assertThat(http.getForEntity(endpoint, String.class).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        }
    }
}
