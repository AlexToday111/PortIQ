package ru.ba6kir.tradelab.market;

import java.time.*;
import java.util.*;
import com.google.protobuf.Timestamp;
import io.grpc.Status;
import org.junit.jupiter.api.Test;
import ru.ba6kir.tradelab.market.application.*;
import ru.ba6kir.tradelab.market.application.price.*;
import ru.ba6kir.tradelab.market.infrastructure.tinvest.*;
import ru.tinkoff.piapi.contract.v1.*;
import static org.assertj.core.api.Assertions.*;
import static ru.ba6kir.tradelab.market.InstrumentServiceTest.*;

class TInvestPriceProviderTest {
    private final TInvestProviderTest.ScriptedChannel transport=new TInvestProviderTest.ScriptedChannel();
    private final Clock clock=Clock.fixed(NOW,ZoneOffset.UTC);
    private final InvestProperties config=new InvestProperties(true,"synthetic",Duration.ofDays(1),Duration.ofMillis(100),3,Duration.ofMillis(1));
    private final TInvestMarketPriceProvider provider=new TInvestMarketPriceProvider(MarketDataServiceGrpc.newBlockingStub(transport),
            new TInvestRpc(config,clock),config,clock);
    private final ru.ba6kir.tradelab.market.domain.StockInstrument stock=stock(UID,"Сбербанк",NOW);
    private static Timestamp ts(Instant time) { return Timestamp.newBuilder().setSeconds(time.getEpochSecond()).setNanos(time.getNano()).build(); }
    private LastPrice price() { return LastPrice.newBuilder().setInstrumentUid(UID).setPrice(Quotation.newBuilder().setUnits(123).setNano(123456789))
            .setTime(ts(NOW.minusSeconds(3600))).setLastPriceType(LastPriceType.LAST_PRICE_EXCHANGE).build(); }
    private HistoricCandle candle(boolean complete) {
        var p=Quotation.newBuilder().setUnits(123).setNano(123456789).build();
        return HistoricCandle.newBuilder().setTime(ts(NOW.minusSeconds(3600))).setOpen(p).setHigh(p).setLow(p).setClose(p)
                .setVolume(4_000_000_000L).setIsComplete(complete).setCandleSource(CandleSource.CANDLE_SOURCE_EXCHANGE).build();
    }
    @Test void latestPricePreservesPrecisionIdentityCurrencyAndOriginalTime() {
        transport.responses.add(GetLastPricesResponse.newBuilder().addLastPrices(price()).build());
        var q=provider.getLastPrice(stock).orElseThrow();
        assertThat(q.price()).isEqualByComparingTo("123.123456789");
        assertThat(q.quoteTime()).isEqualTo(NOW.minusSeconds(3600));
        assertThat(q.fetchedAt()).isEqualTo(NOW);
        assertThat(q.currency()).isEqualTo("rub");
        assertThat(transport.requests.getFirst()).isInstanceOfSatisfying(GetLastPricesRequest.class,r -> {
            assertThat(r.getInstrumentIdList()).containsExactly(UID);
            assertThat(r.getLastPriceType()).isEqualTo(LastPriceType.LAST_PRICE_EXCHANGE);
        });
    }
    @Test void emptyQuoteIsAbsenceAndInvalidDataIsNeverZeroOrFabricated() {
        transport.responses.add(GetLastPricesResponse.getDefaultInstance());
        assertThat(provider.getLastPrice(stock)).isEmpty();
        transport.responses.add(GetLastPricesResponse.newBuilder().addLastPrices(price().toBuilder().clearPrice()).build());
        assertThatThrownBy(() -> provider.getLastPrice(stock)).isInstanceOf(PriceException.class);
        transport.responses.add(GetLastPricesResponse.newBuilder().addLastPrices(price().toBuilder().setInstrumentUid("wrong")).build());
        assertThatThrownBy(() -> provider.getLastPrice(stock)).isInstanceOf(PriceException.class);
    }
    @Test void dailyRequestPreservesIncompleteAndMissingDaysAndUsesExchangeOnly() {
        transport.responses.add(GetCandlesResponse.newBuilder().addCandles(candle(false)).build());
        var result=provider.getDailyCandles(stock,NOW.minus(Duration.ofDays(30)),NOW);
        assertThat(result).hasSize(1);
        assertThat(result.getFirst().complete()).isFalse();
        assertThat(result.getFirst().volume()).isEqualTo(4_000_000_000L);
        assertThat(transport.requests.getFirst()).isInstanceOfSatisfying(GetCandlesRequest.class,r -> {
            assertThat(r.getInterval()).isEqualTo(CandleInterval.CANDLE_INTERVAL_DAY);
            assertThat(r.getInstrumentId()).isEqualTo(UID);
            assertThat(r.getCandleSourceType()).isEqualTo(GetCandlesRequest.CandleSource.CANDLE_SOURCE_EXCHANGE);
        });
        transport.responses.add(GetCandlesResponse.getDefaultInstance());
        assertThat(provider.getDailyCandles(stock,NOW.minusSeconds(1),NOW)).isEmpty();
    }
    @Test void unknownCurrencyAndInvalidTimestampAreExplicitValidationFailures() {
        var unknown=new ru.ba6kir.tradelab.market.domain.StockInstrument(UID,"SBER","TQBR","name",null,null,null,"MOEX",10,null,true,NOW,"test");
        transport.responses.add(GetLastPricesResponse.newBuilder().addLastPrices(price()).build());
        assertThatThrownBy(() -> provider.getLastPrice(unknown)).isInstanceOf(PriceException.class);
        transport.responses.add(GetLastPricesResponse.newBuilder().addLastPrices(price().toBuilder()
                .setTime(Timestamp.newBuilder().setSeconds(Long.MAX_VALUE))).build());
        assertThatThrownBy(() -> provider.getLastPrice(stock)).isInstanceOf(PriceException.class);
    }
    @Test void rejectsInvalidWholeCandleBatchAndOutOfRangeTime() {
        transport.responses.add(GetCandlesResponse.newBuilder().addCandles(candle(true))
                .addCandles(candle(false).toBuilder().clearClose()).build());
        assertThatThrownBy(() -> provider.getDailyCandles(stock,NOW.minus(Duration.ofDays(1)),NOW)).isInstanceOf(PriceException.class);
        transport.responses.add(GetCandlesResponse.newBuilder().addCandles(candle(true)).build());
        assertThatThrownBy(() -> provider.getDailyCandles(stock,NOW.minusSeconds(60),NOW)).isInstanceOf(PriceException.class);
    }
    @Test void sharesBoundedRetriesAuthenticationAndRateLimitPolicy() {
        transport.responses.add(Status.UNAVAILABLE);
        transport.responses.add(GetLastPricesResponse.newBuilder().addLastPrices(price()).build());
        assertThat(provider.getLastPrice(stock)).isPresent();
        assertThat(transport.requests).hasSize(2);
        transport.responses.add(Status.UNAUTHENTICATED.withDescription("secret-not-to-log"));
        assertThatThrownBy(() -> provider.getLastPrice(stock)).isInstanceOfSatisfying(MarketException.class,e -> {
            assertThat(e.reason()).isEqualTo(MarketException.Reason.AUTHENTICATION); assertThat(e).hasNoCause();
        });
        assertThat(transport.requests).hasSize(3);
        transport.responses.add(Status.RESOURCE_EXHAUSTED);
        assertThatThrownBy(() -> provider.getLastPrice(stock)).isInstanceOf(MarketException.class);
        assertThatThrownBy(() -> provider.getDailyCandles(stock,NOW.minusSeconds(1),NOW)).isInstanceOf(MarketException.class);
        assertThat(transport.requests).hasSize(4);
    }
}
