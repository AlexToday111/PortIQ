package ru.ba6kir.tradelab.market.infrastructure.tinvest;

import java.math.BigDecimal;
import ru.tinkoff.piapi.contract.v1.*;

public final class QuotationDecimals {
    private QuotationDecimals() { }
    public static BigDecimal from(long units, int nano) {
        if (nano <= -1_000_000_000 || nano >= 1_000_000_000
                || units > 0 && nano < 0 || units < 0 && nano > 0)
            throw new IllegalArgumentException("Invalid units/nano representation");
        return BigDecimal.valueOf(units).add(BigDecimal.valueOf(nano, 9));
    }
    public static BigDecimal from(Quotation value) { return from(value.getUnits(), value.getNano()); }
    public static BigDecimal from(MoneyValue value) { return from(value.getUnits(), value.getNano()); }
}
