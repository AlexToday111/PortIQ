package ru.ba6kir.tradelab.market.domain.price;

import java.math.BigDecimal;
import java.time.Instant;

public record MarketQuote(String instrumentUid, BigDecimal price, String currency, Instant quoteTime,
                          Instant fetchedAt, String source) {
    public MarketQuote {
        PriceValues.identity(instrumentUid, source);
        PriceValues.price(price);
        PriceValues.time(quoteTime, fetchedAt);
        if (currency == null || !currency.matches("[A-Za-z]{3}")) throw new IllegalArgumentException("Missing price currency");
    }
}
