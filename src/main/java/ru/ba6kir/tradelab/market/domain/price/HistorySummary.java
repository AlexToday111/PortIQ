package ru.ba6kir.tradelab.market.domain.price;

import java.math.*;
import java.time.LocalDate;
import java.util.*;

public record HistorySummary(LocalDate firstDate, LocalDate lastDate, BigDecimal firstClose, BigDecimal lastClose,
                             BigDecimal absoluteChange, BigDecimal percentChange, int finalizedCount) {
    public static Optional<HistorySummary> calculate(List<DailyCandle> candles) {
        var finalized = candles.stream().filter(DailyCandle::complete)
                .sorted(Comparator.comparing(DailyCandle::candleTime)).toList();
        if (finalized.isEmpty()) return Optional.empty();
        var first = finalized.getFirst();
        var last = finalized.getLast();
        var change = last.close().subtract(first.close());
        // A zero/invalid baseline is rejected by the domain; guard explicitly before division as well.
        if (first.close().signum() <= 0) throw new IllegalArgumentException("Invalid history baseline");
        return Optional.of(new HistorySummary(first.tradingDate(), last.tradingDate(), first.close(), last.close(),
                change, change.multiply(BigDecimal.valueOf(100)).divide(first.close(), 6, RoundingMode.HALF_UP), finalized.size()));
    }
}
