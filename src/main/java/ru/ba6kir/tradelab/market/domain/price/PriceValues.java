package ru.ba6kir.tradelab.market.domain.price;

import java.math.BigDecimal;
import java.time.Instant;

public final class PriceValues {
    private PriceValues() { }
    public static BigDecimal price(BigDecimal value) {
        if (value == null || value.signum() <= 0 || value.scale() > 9 || value.precision() - value.scale() > 20)
            throw new IllegalArgumentException("Invalid stock price");
        return value;
    }
    public static void identity(String uid, String source) {
        if (uid == null || !uid.matches("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")
                || source == null || source.isBlank())
            throw new IllegalArgumentException("Missing market data identity");
    }
    public static void time(Instant observation, Instant fetched) {
        if (observation == null || fetched == null || !observation.isAfter(Instant.EPOCH)
                || observation.isAfter(fetched.plusSeconds(60))) throw new IllegalArgumentException("Invalid market data timestamp");
    }
}
