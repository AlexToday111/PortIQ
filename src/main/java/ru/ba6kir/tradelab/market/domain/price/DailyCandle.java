package ru.ba6kir.tradelab.market.domain.price;

import java.math.BigDecimal;
import java.time.*;

public record DailyCandle(String instrumentUid, Instant candleTime, LocalDate tradingDate,
                          BigDecimal open, BigDecimal high, BigDecimal low, BigDecimal close, long volume,
                          boolean complete, String source, Instant fetchedAt) {
    public static final ZoneId TRADING_ZONE = ZoneId.of("Europe/Moscow");
    public DailyCandle {
        PriceValues.identity(instrumentUid, source);
        PriceValues.time(candleTime, fetchedAt);
        PriceValues.price(open); PriceValues.price(high); PriceValues.price(low); PriceValues.price(close);
        if (volume < 0 || low.compareTo(high) > 0 || open.compareTo(low) < 0 || open.compareTo(high) > 0
                || close.compareTo(low) < 0 || close.compareTo(high) > 0
                || !candleTime.atZone(TRADING_ZONE).toLocalDate().equals(tradingDate))
            throw new IllegalArgumentException("Invalid daily candle");
    }
}
