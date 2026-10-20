package ru.ba6kir.tradelab.fundamentals.infrastructure.tinvest;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("tradelab.fundamentals")
public record FundamentalsProperties(Duration snapshotTtl,Duration reportingMaxAge,Duration refreshCooldown,int reportWindowDays) {
    public FundamentalsProperties {
        if (snapshotTtl==null || snapshotTtl.isNegative() || snapshotTtl.isZero() || snapshotTtl.compareTo(Duration.ofDays(30))>0)
            throw new IllegalArgumentException("FUNDAMENTALS_SNAPSHOT_TTL must be positive and at most 30d");
        if (reportingMaxAge==null || reportingMaxAge.compareTo(Duration.ofDays(30))<0 || reportingMaxAge.compareTo(Duration.ofDays(3650))>0)
            throw new IllegalArgumentException("FUNDAMENTALS_REPORTING_MAX_AGE must be 30d to 3650d");
        if (refreshCooldown==null || refreshCooldown.compareTo(Duration.ofSeconds(10))<0 || refreshCooldown.compareTo(Duration.ofMinutes(10))>0)
            throw new IllegalArgumentException("FUNDAMENTALS_REFRESH_COOLDOWN must be 10s to 10m");
        if (reportWindowDays<1 || reportWindowDays>180) throw new IllegalArgumentException("FUNDAMENTALS_REPORT_WINDOW_DAYS must be 1 to 180");
    }
}
