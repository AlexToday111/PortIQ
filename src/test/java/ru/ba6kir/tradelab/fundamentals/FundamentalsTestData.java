package ru.ba6kir.tradelab.fundamentals;

import java.time.*;
import java.util.*;
import ru.ba6kir.tradelab.market.domain.StockInstrument;
import ru.ba6kir.tradelab.fundamentals.domain.model.*;
import ru.ba6kir.tradelab.fundamentals.domain.validation.FundamentalNormalizer;

final class FundamentalsTestData {
    static final String UID="11111111-1111-1111-1111-111111111111",UID2="22222222-2222-2222-2222-222222222222";
    static final String ASSET="aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa",ASSET2="bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb";
    static final Instant NOW=Instant.parse("2026-10-20T12:00:00Z");
    static final String SOURCE="T_INVEST_PRODUCTION";
    static final Clock CLOCK=Clock.fixed(NOW,ZoneOffset.UTC);
    static StockInstrument stock(String uid) { return new StockInstrument(uid,uid.equals(UID)?"SBER":"SBERP","TQBR","Сбербанк",null,null,"rub","MOEX",10,null,true,NOW,SOURCE); }
    static AssetIdentity identity(String uid) { return new AssetIdentity(ASSET,"Актив Сбербанк","Компания Сбербанк",null,uid,SOURCE,NOW); }
    static FundamentalData data(String pe,Instant fetched) {
        var values=new EnumMap<MetricCode,FundamentalData.RawNumber>(MetricCode.class);
        values.put(MetricCode.PE,new FundamentalData.RawNumber(pe,false));
        values.put(MetricCode.REVENUE,new FundamentalData.RawNumber("1000.25",false));
        values.put(MetricCode.NET_INCOME,new FundamentalData.RawNumber("-100.5",false));
        values.put(MetricCode.FREE_CASH_FLOW,new FundamentalData.RawNumber("-10.25",false));
        values.put(MetricCode.ROE,new FundamentalData.RawNumber("0.25",false));
        values.put(MetricCode.TOTAL_DEBT,new FundamentalData.RawNumber("0.0",false));
        return new FundamentalData(ASSET,"USD",Instant.parse("2025-01-01T00:00:00Z"),Instant.parse("2025-12-31T00:00:00Z"),null,fetched,SOURCE,values);
    }
    static FundamentalSnapshot snapshot() { return new FundamentalNormalizer().normalize(data("5.25",NOW)); }
}
