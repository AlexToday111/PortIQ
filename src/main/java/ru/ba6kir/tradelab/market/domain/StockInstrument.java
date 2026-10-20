package ru.ba6kir.tradelab.market.domain;

import java.time.Instant;
import java.util.Objects;

/** Security metadata, not issuer business information or live trading status. */
public record StockInstrument(String uid, String ticker, String classCode, String name,
        String figi, String isin, String currency, String exchange, Integer lotSize,
        String tradingStatus, Boolean apiTradeAvailable, Instant updatedAt, String source) {
    public StockInstrument {
        if (uid == null || uid.isBlank()) throw new IllegalArgumentException("Instrument UID is required");
        Objects.requireNonNull(updatedAt, "Metadata timestamp is required");
        Objects.requireNonNull(source, "Metadata source is required");
    }
}
