package ru.ba6kir.tradelab.dividends.infrastructure.tinvest;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("tradelab.dividends")
public record DividendProperties(int lookbackYears,int forwardDays,Duration cacheTtl,Duration refreshCooldown) {
    public DividendProperties {
        if (lookbackYears<1 || lookbackYears>5 || forwardDays<90 || forwardDays>366)
            throw new IllegalArgumentException("Dividend bounds must be 1..5 years back and 90..366 days forward");
        if (cacheTtl==null || cacheTtl.isNegative() || cacheTtl.isZero() || cacheTtl.compareTo(Duration.ofDays(30))>0
                || refreshCooldown==null || refreshCooldown.compareTo(Duration.ofSeconds(10))<0 || refreshCooldown.compareTo(Duration.ofMinutes(10))>0)
            throw new IllegalArgumentException("Invalid dividend TTL or refresh cooldown");
    }
}
