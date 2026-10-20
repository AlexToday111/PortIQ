package ru.ba6kir.tradelab.market.infrastructure.tinvest;

import java.time.Duration;
import java.util.*;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("tradelab.market-data")
public record MarketDataProperties(List<String> trackedTickers, int historyDays, Duration refreshCooldown) {
    public MarketDataProperties {
        trackedTickers = trackedTickers == null ? List.of() : trackedTickers.stream()
                .map(s -> s.toUpperCase(Locale.ROOT)).distinct().toList();
        if (trackedTickers.size() > 20 || trackedTickers.stream().anyMatch(s -> !s.matches("[A-Z0-9][A-Z0-9._-]{0,31}")))
            throw new IllegalArgumentException("INVEST_TRACKED_TICKERS must contain at most 20 valid tickers");
        if (historyDays < 1 || historyDays > 365) throw new IllegalArgumentException("INVEST_HISTORY_DAYS must be 1 to 365");
        if (refreshCooldown == null || refreshCooldown.compareTo(Duration.ofSeconds(10)) < 0
                || refreshCooldown.compareTo(Duration.ofMinutes(10)) > 0)
            throw new IllegalArgumentException("INVEST_REFRESH_COOLDOWN must be 10s to 10m");
    }
}
