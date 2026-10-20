package ru.ba6kir.tradelab.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

class DatabasePropertiesTest {

    @ParameterizedTest
    @CsvSource({
            "localhost,5432,tradelab,jdbc:postgresql://localhost:5432/tradelab",
            "db.internal,15432,trade_lab,jdbc:postgresql://db.internal:15432/trade_lab",
            "[::1],5432,tradelab,jdbc:postgresql://[::1]:5432/tradelab"
    })
    void buildsCredentialFreeJdbcUrl(String host, int port, String name, String expected) {
        var properties = new DatabaseProperties(host, port, name, "test-user", "test-secret");

        assertThat(properties.jdbcUrl()).isEqualTo(expected)
                .doesNotContain(properties.username(), properties.password());
    }

    @Test
    void diagnosticRepresentationRedactsPassword() {
        var properties = new DatabaseProperties("localhost", 5432, "tradelab", "test-user", "test-secret");

        assertThat(properties.toString()).contains("password=<redacted>").doesNotContain("test-secret");
    }
}
