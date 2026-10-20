package ru.ba6kir.tradelab.config;

import java.util.stream.Stream;

import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

import static org.assertj.core.api.Assertions.assertThat;

class DatabaseConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withInitializer(new ConfigDataApplicationContextInitializer())
            .withUserConfiguration(TestConfiguration.class)
            .withPropertyValues(
                    "DB_HOST=localhost", "DB_PORT=5432", "DB_NAME=tradelab",
                    "DB_USERNAME=test-user", "DB_PASSWORD=test-secret");

    @Test
    void bindsEnvironmentVariablesAndConfiguresPoolWithoutConnecting() {
        runner.run(context -> {
            assertThat(context).hasNotFailed().hasSingleBean(HikariDataSource.class);
            var pool = context.getBean(HikariDataSource.class);
            assertThat(pool.getJdbcUrl()).isEqualTo("jdbc:postgresql://localhost:5432/tradelab");
            assertThat(pool.getUsername()).isEqualTo("test-user");
            assertThat(pool.getPassword()).isEqualTo("test-secret");
            assertThat(pool.getConnectionInitSql()).isEqualTo("SET TIME ZONE 'UTC'");
            assertThat(pool.getMaximumPoolSize()).isEqualTo(5);
            assertThat(pool.isRunning()).isFalse();
        });
    }

    @ParameterizedTest(name = "rejects {0}")
    @MethodSource("invalidConfiguration")
    void rejectsInvalidConfigurationBeforeOpeningConnections(String property, String message) {
        runner.withPropertyValues(property).run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).hasStackTraceContaining(message);
        });
    }

    static Stream<Arguments> invalidConfiguration() {
        return Stream.of(
                Arguments.of("DB_HOST=", "DB_HOST must be set"),
                Arguments.of("DB_HOST=localhost/path?password=oops", "DB_HOST must be a hostname"),
                Arguments.of("DB_PORT=0", "DB_PORT must be between 1 and 65535"),
                Arguments.of("DB_PORT=65536", "DB_PORT must be between 1 and 65535"),
                Arguments.of("DB_PORT=not-a-number", "tradelab.database.port"),
                Arguments.of("DB_NAME=", "DB_NAME must be set"),
                Arguments.of("DB_NAME=db?ssl=false", "DB_NAME must use letters"),
                Arguments.of("DB_USERNAME=", "DB_USERNAME must be set"),
                Arguments.of("DB_PASSWORD=", "DB_PASSWORD must be set"),
                Arguments.of("DB_PASSWORD=   ", "DB_PASSWORD must be set"));
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(DatabaseProperties.class)
    @Import(DatabaseConfiguration.class)
    static class TestConfiguration {
    }
}
