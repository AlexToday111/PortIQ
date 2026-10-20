package ru.ba6kir.tradelab.market.application.price;

import java.time.Instant;
import java.util.*;
import ru.ba6kir.tradelab.market.domain.StockInstrument;
import ru.ba6kir.tradelab.market.domain.price.*;

public interface MarketPriceProvider {
    Optional<MarketQuote> getLastPrice(StockInstrument instrument);
    List<DailyCandle> getDailyCandles(StockInstrument instrument, Instant from, Instant to);
}
