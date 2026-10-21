package ru.ba6kir.tradelab.analysis.ranking.domain;

import java.time.Duration;

/** Eligibility and cache policy only; never a financial scoring formula. */
public record RankingPolicy(String version,Duration snapshotTtl,Duration refreshCooldown,Duration scoreMaxAge,
        Duration quoteMaxAge,Duration metadataMaxAge,Duration classificationMaxAge) {
    public RankingPolicy {
        if (version==null || !version.matches("[A-Za-z0-9._-]{1,80}")) throw new IllegalArgumentException("Ranking policy version required");
        for (var d:new Duration[]{snapshotTtl,refreshCooldown,scoreMaxAge,quoteMaxAge,metadataMaxAge,classificationMaxAge})
            if (d==null || d.isNegative() || d.isZero()) throw new IllegalArgumentException("Positive ranking ages required");
        if (snapshotTtl.compareTo(Duration.ofDays(1))>0 || refreshCooldown.compareTo(Duration.ofSeconds(10))<0
                || refreshCooldown.compareTo(Duration.ofHours(1))>0 || scoreMaxAge.compareTo(Duration.ofDays(30))>0
                || quoteMaxAge.compareTo(Duration.ofDays(7))>0 || metadataMaxAge.compareTo(Duration.ofDays(30))>0)
            throw new IllegalArgumentException("Ranking age/cooldown outside supported bounds");
    }
}
