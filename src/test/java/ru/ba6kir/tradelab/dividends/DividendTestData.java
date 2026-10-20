package ru.ba6kir.tradelab.dividends;

import java.time.*;
import com.google.protobuf.Timestamp;
import ru.tinkoff.piapi.contract.v1.*;
import ru.ba6kir.tradelab.market.domain.StockInstrument;
import ru.ba6kir.tradelab.dividends.domain.model.DividendEvent;
import ru.ba6kir.tradelab.dividends.domain.model.DividendEvent.Observation;
import ru.ba6kir.tradelab.dividends.infrastructure.tinvest.TInvestDividendAdapter;

final class DividendTestData {
    static final String UID="11111111-1111-1111-1111-111111111111", UID2="22222222-2222-2222-2222-222222222222";
    static final Instant NOW=Instant.parse("2026-10-20T12:00:00Z");
    static final Clock CLOCK=Clock.fixed(NOW,ZoneOffset.UTC);
    static final LocalDate TODAY=LocalDate.of(2026,10,20);
    static StockInstrument stock(String uid) { return new StockInstrument(uid,uid.equals(UID)?"SBER":"SBERP","TQBR","Synthetic test share",null,null,
            "RUB","MOEX",10,"NORMAL_TRADING",true,NOW,"T_INVEST_PRODUCTION"); }
    static Timestamp stamp(String iso) { var t=Instant.parse(iso); return Timestamp.newBuilder().setSeconds(t.getEpochSecond()).setNanos(t.getNano()).build(); }
    static Timestamp day(LocalDate d) { return stamp(d+"T00:00:00Z"); }
    static Dividend.Builder raw() { return Dividend.newBuilder().setDividendNet(MoneyValue.newBuilder().setCurrency("rub").setUnits(10).setNano(500000000))
            .setDeclaredDate(day(TODAY.minusDays(20))).setLastBuyDate(day(TODAY.minusDays(12))).setRecordDate(day(TODAY.minusDays(10)))
            .setPaymentDate(day(TODAY.minusDays(5))).setDividendType("Regular Cash").setRegularity("Annual")
            .setCreatedAt(stamp("2026-09-30T12:00:00.123456789Z")); }
    static DividendEvent event(Dividend.Builder d) { return TInvestDividendAdapter.normalize(UID,d.build(),NOW); }
    static DividendEvent event() { return event(raw()); }
    static Observation observation(DividendEvent e) { return new Observation(e,false,NOW,NOW); }
}
