package ru.ba6kir.tradelab.market;

import java.math.BigDecimal;
import java.time.*;
import java.util.List;
import org.junit.jupiter.api.Test;
import ru.ba6kir.tradelab.market.domain.price.*;
import ru.ba6kir.tradelab.market.infrastructure.tinvest.QuotationDecimals;
import ru.ba6kir.tradelab.market.infrastructure.tinvest.TInvestMarketPriceProvider;
import ru.tinkoff.piapi.contract.v1.*;
import static org.assertj.core.api.Assertions.*;
import static ru.ba6kir.tradelab.market.InstrumentServiceTest.*;

class PriceDomainTest {
    public static DailyCandle candle(Instant time, String close, boolean complete) {
        var p=new BigDecimal(close);
        return new DailyCandle(UID,time,time.atZone(DailyCandle.TRADING_ZONE).toLocalDate(),p,p,p,p,5_000_000_000L,
                complete,TInvestMarketPriceProvider.SOURCE,NOW);
    }
    public static MarketQuote quote(Instant time) {
        return new MarketQuote(UID,new BigDecimal("123.123456789"),"rub",time,NOW,TInvestMarketPriceProvider.SOURCE);
    }
    @Test void exactSignedUnitsAndNanosIncludingLimits() {
        assertThat(QuotationDecimals.from(123,123456789)).isEqualByComparingTo("123.123456789");
        assertThat(QuotationDecimals.from(-123,-123456789)).isEqualByComparingTo("-123.123456789");
        assertThat(QuotationDecimals.from(0,-1)).isEqualByComparingTo("-0.000000001");
        assertThat(QuotationDecimals.from(0,999999999)).isEqualByComparingTo("0.999999999");
        assertThat(QuotationDecimals.from(Long.MAX_VALUE,999999999)).isEqualByComparingTo("9223372036854775807.999999999");
        assertThat(QuotationDecimals.from(Long.MIN_VALUE,-999999999)).isEqualByComparingTo("-9223372036854775808.999999999");
        assertThat(QuotationDecimals.from(Quotation.newBuilder().setUnits(1).setNano(1).build())).isEqualByComparingTo("1.000000001");
        assertThat(QuotationDecimals.from(MoneyValue.newBuilder().setCurrency("rub").setUnits(-1).setNano(-1).build()))
                .isEqualByComparingTo("-1.000000001");
    }
    @Test void rejectsMalformedNanounits() {
        assertThatThrownBy(() -> QuotationDecimals.from(1,-1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> QuotationDecimals.from(-1,1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> QuotationDecimals.from(0,1_000_000_000)).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void summaryUsesOnlyActualFinalizedObservationsAndSkipsWeekendAndIncomplete() {
        var friday=candle(Instant.parse("2026-10-16T00:00:00Z"),"100.123456789",true);
        var monday=candle(Instant.parse("2026-10-19T00:00:00Z"),"110.123456789",true);
        var today=candle(Instant.parse("2026-10-20T00:00:00Z"),"999",false);
        var summary=HistorySummary.calculate(List.of(today,monday,friday)).orElseThrow();
        assertThat(summary.finalizedCount()).isEqualTo(2);
        assertThat(summary.firstDate()).isEqualTo(LocalDate.parse("2026-10-16"));
        assertThat(summary.lastDate()).isEqualTo(LocalDate.parse("2026-10-19"));
        assertThat(summary.absoluteChange()).isEqualByComparingTo("10");
        assertThat(summary.percentChange()).isEqualByComparingTo("9.98767");
        assertThat(HistorySummary.calculate(List.of(today))).isEmpty();
        assertThat(HistorySummary.calculate(List.of())).isEmpty();
    }
    @Test void noDivisionByZeroOrNegativeStockPricesAndNoInvalidOhlc() {
        assertThatThrownBy(() -> candle(NOW,"0",true)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> candle(NOW,"-1",true)).isInstanceOf(IllegalArgumentException.class);
        var one=candle(NOW,"2",true);
        assertThat(HistorySummary.calculate(List.of(one)).orElseThrow().percentChange()).isEqualByComparingTo("0");
        assertThatThrownBy(() -> new DailyCandle(UID,NOW,NOW.atZone(DailyCandle.TRADING_ZONE).toLocalDate(),
                BigDecimal.TEN,BigDecimal.ONE,BigDecimal.ONE,BigDecimal.ONE,1,true,"test",NOW)).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void tradingDateUsesMoscowWithoutChangingStoredInstant() {
        var time=Instant.parse("2026-10-19T22:00:00Z");
        var c=candle(time,"1",false);
        assertThat(c.tradingDate()).isEqualTo(LocalDate.parse("2026-10-20"));
        assertThat(c.candleTime()).isEqualTo(time);
        assertThatThrownBy(() -> quote(NOW.plusSeconds(61))).isInstanceOf(IllegalArgumentException.class);
    }
}
