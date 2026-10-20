package ru.ba6kir.tradelab.fundamentals.domain.model;

import java.time.Instant;
import java.util.*;

/** Transport-independent source values. Text preserves non-finite diagnostics safely in JSON. */
public record FundamentalData(String assetUid, String currency, Instant periodStart, Instant periodEnd,
                              Instant providerAsOf, Instant fetchedAt, String source, Map<MetricCode,RawNumber> values) {
    public record RawNumber(String text, boolean presenceKnown) { }
    public FundamentalData { values=Map.copyOf(values); }
}
