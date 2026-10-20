package ru.ba6kir.tradelab.market.infrastructure.tinvest;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("tradelab.invest")
public record InvestProperties(boolean enabled, String token, Duration metadataTtl, Duration timeout,
                               int maxAttempts, Duration retryBackoff) {
    public InvestProperties {
        if (enabled && (token == null || token.isBlank() || !token.equals(token.trim())
                || token.chars().anyMatch(Character::isWhitespace))) {
            throw new IllegalArgumentException("INVEST_API_TOKEN must be non-empty without whitespace when INVEST_API_ENABLED=true");
        }
        if (metadataTtl == null || metadataTtl.isNegative() || metadataTtl.isZero()
                || metadataTtl.compareTo(Duration.ofDays(30)) > 0)
            throw new IllegalArgumentException("INVEST_METADATA_TTL must be positive and at most 30d");
        if (timeout == null || timeout.toMillis() < 1 || timeout.compareTo(Duration.ofSeconds(30)) > 0)
            throw new IllegalArgumentException("INVEST_API_TIMEOUT must be 1ms to 30s");
        if (maxAttempts < 1 || maxAttempts > 3)
            throw new IllegalArgumentException("INVEST_API_MAX_ATTEMPTS must be 1 to 3");
        if (retryBackoff == null || retryBackoff.toMillis() < 1 || retryBackoff.compareTo(Duration.ofSeconds(2)) > 0)
            throw new IllegalArgumentException("INVEST_API_RETRY_BACKOFF must be 1ms to 2s");
    }

    @Override
    public String toString() {
        return "InvestProperties[enabled=" + enabled + ", token=<redacted>]";
    }
}
