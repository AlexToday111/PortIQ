package ru.ba6kir.tradelab.config;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("tradelab.database")
public record DatabaseProperties(
        @NotBlank(message = "DB_HOST must be set")
        @Pattern(regexp = "[A-Za-z0-9][A-Za-z0-9.-]*|\\[[0-9a-fA-F:]+\\]",
                message = "DB_HOST must be a hostname, IPv4 address, or bracketed IPv6 address")
        String host,
        @NotNull(message = "DB_PORT must be set")
        @Min(value = 1, message = "DB_PORT must be between 1 and 65535")
        @Max(value = 65535, message = "DB_PORT must be between 1 and 65535")
        Integer port,
        @NotBlank(message = "DB_NAME must be set")
        @Pattern(regexp = "[A-Za-z_][A-Za-z0-9_]*",
                message = "DB_NAME must use letters, digits, and underscores, starting with a letter or underscore")
        String name,
        @NotBlank(message = "DB_USERNAME must be set") String username,
        @NotBlank(message = "DB_PASSWORD must be set") String password) {

    public String jdbcUrl() {
        return "jdbc:postgresql://%s:%d/%s".formatted(host, port, name);
    }

    @Override
    public String toString() {
        return "DatabaseProperties[host=%s, port=%s, name=%s, username=%s, password=<redacted>]"
                .formatted(host, port, name, username);
    }
}
