package ru.ba6kir.tradelab.market.infrastructure.tinvest;

import java.time.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import com.google.protobuf.Timestamp;
import ru.ba6kir.tradelab.market.application.price.*;
import ru.ba6kir.tradelab.market.domain.StockInstrument;
import ru.ba6kir.tradelab.market.domain.price.*;
import ru.tinkoff.piapi.contract.v1.*;

public class TInvestMarketPriceProvider implements MarketPriceProvider {
    public static final String SOURCE = "T_INVEST_PRODUCTION_EXCHANGE";
    private final MarketDataServiceGrpc.MarketDataServiceBlockingStub stub;
    private final TInvestRpc rpc;
    private final InvestProperties properties;
    private final Clock clock;
    public TInvestMarketPriceProvider(MarketDataServiceGrpc.MarketDataServiceBlockingStub stub, TInvestRpc rpc,
                                     InvestProperties properties, Clock clock) {
        this.stub = stub; this.rpc = rpc; this.properties = properties; this.clock = clock;
    }
    private MarketDataServiceGrpc.MarketDataServiceBlockingStub client() {
        return stub.withDeadlineAfter(properties.timeout().toMillis(), TimeUnit.MILLISECONDS);
    }
    @Override public Optional<MarketQuote> getLastPrice(StockInstrument instrument) {
        var request = GetLastPricesRequest.newBuilder().addInstrumentId(instrument.uid())
                .setLastPriceType(LastPriceType.LAST_PRICE_EXCHANGE).build();
        var prices = rpc.call(() -> client().getLastPrices(request)).getLastPricesList();
        if (prices.isEmpty()) return Optional.empty();
        try {
            if (prices.size() != 1) throw new IllegalArgumentException("Unexpected quote count");
            var p = prices.getFirst();
            if (!instrument.uid().equals(p.getInstrumentUid()) || !p.hasPrice() || !p.hasTime()
                    || p.getLastPriceType() != LastPriceType.LAST_PRICE_EXCHANGE)
                throw new IllegalArgumentException("Invalid quote response");
            return Optional.of(new MarketQuote(instrument.uid(), QuotationDecimals.from(p.getPrice()), instrument.currency(),
                    instant(p.getTime()), clock.instant(), SOURCE));
        } catch (RuntimeException e) { throw new PriceException(PriceException.Reason.INVALID_DATA); }
    }
    @Override public List<DailyCandle> getDailyCandles(StockInstrument instrument, Instant from, Instant to) {
        if (from == null || to == null || !from.isBefore(to) || Duration.between(from, to).compareTo(Duration.ofDays(180)) > 0)
            throw new IllegalArgumentException("Daily candle requests must be split into at most 180 days");
        var request = GetCandlesRequest.newBuilder().setInstrumentId(instrument.uid())
                .setFrom(timestamp(from)).setTo(timestamp(to)).setInterval(CandleInterval.CANDLE_INTERVAL_DAY)
                .setCandleSourceType(GetCandlesRequest.CandleSource.CANDLE_SOURCE_EXCHANGE).setLimit(1000).build();
        var response = rpc.call(() -> client().getCandles(request));
        try {
            var result = new TreeMap<Instant, DailyCandle>();
            for (var c : response.getCandlesList()) {
                if (!c.hasTime() || !c.hasOpen() || !c.hasHigh() || !c.hasLow() || !c.hasClose()
                        || c.getCandleSource() != CandleSource.CANDLE_SOURCE_EXCHANGE)
                    throw new IllegalArgumentException("Missing candle fields");
                var time = instant(c.getTime());
                // The API may include the right boundary; use half-open windows for deterministic deduplication.
                if (time.equals(to)) continue;
                if (time.isBefore(from) || !time.isBefore(to)) throw new IllegalArgumentException("Candle outside request");
                var candle = new DailyCandle(instrument.uid(), time, time.atZone(DailyCandle.TRADING_ZONE).toLocalDate(),
                        QuotationDecimals.from(c.getOpen()), QuotationDecimals.from(c.getHigh()),
                        QuotationDecimals.from(c.getLow()), QuotationDecimals.from(c.getClose()), c.getVolume(),
                        c.getIsComplete(), SOURCE, clock.instant());
                if (result.putIfAbsent(time, candle) != null) throw new IllegalArgumentException("Duplicate candle in response");
            }
            return List.copyOf(result.values());
        } catch (RuntimeException e) { throw new PriceException(PriceException.Reason.INVALID_DATA); }
    }
    private static Instant instant(Timestamp value) {
        if (value.getNanos() < 0 || value.getNanos() > 999_999_999) throw new IllegalArgumentException("Invalid timestamp nanos");
        return Instant.ofEpochSecond(value.getSeconds(), value.getNanos());
    }
    private static Timestamp timestamp(Instant value) {
        return Timestamp.newBuilder().setSeconds(value.getEpochSecond()).setNanos(value.getNano()).build();
    }
}
