package ru.ba6kir.tradelab.fundamentals.domain.model;

import java.time.Instant;
import java.util.*;

public record FundamentalSnapshot(Long id, String assetUid, String source, Instant fetchedAt, Instant lastFetchedAt,
                                  Instant providerAsOf, Instant periodStart, Instant periodEnd, String contentHash,
                                  String completeness, List<MetricValue> metrics, Map<String,String> rawPayload) {
    public FundamentalSnapshot { metrics=List.copyOf(metrics); rawPayload=Map.copyOf(rawPayload); }
}
