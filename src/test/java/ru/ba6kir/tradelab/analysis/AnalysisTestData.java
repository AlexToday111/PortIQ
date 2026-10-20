package ru.ba6kir.tradelab.analysis;

import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import ru.ba6kir.tradelab.analysis.domain.metric.*;
import ru.ba6kir.tradelab.analysis.domain.sector.*;
import ru.ba6kir.tradelab.fundamentals.domain.model.*;
import ru.ba6kir.tradelab.fundamentals.application.service.FundamentalAnalysisView;
import ru.ba6kir.tradelab.fundamentals.application.port.FundamentalsStore;
import ru.ba6kir.tradelab.market.domain.StockInstrument;
import static ru.ba6kir.tradelab.analysis.domain.metric.FinancialObservation.*;
import static ru.ba6kir.tradelab.analysis.domain.sector.SectorClassification.*;

final class AnalysisTestData {
    static final Instant NOW=Instant.parse("2026-10-20T12:00:00Z"); static final Clock CLOCK=Clock.fixed(NOW,ZoneOffset.UTC);
    static final String UID="11111111-1111-1111-1111-111111111111",UID2="22222222-2222-2222-2222-222222222222",ASSET=asset(0);
    static String asset(int i) { return String.format("aaaaaaaa-aaaa-aaaa-aaaa-%012d",i); }
    static StockInstrument stock(String uid) { return new StockInstrument(uid,"TEST","TQBR","Synthetic issuer",null,null,"RUB","MOEX",10,null,true,NOW,"SYNTHETIC"); }
    static AssetIdentity identity(String uid,String providerSector) { return new AssetIdentity(ASSET,"Synthetic asset","Synthetic issuer",null,uid,"SYNTHETIC",NOW,providerSector); }
    static SectorClassification classification(int i) { return new SectorClassification(asset(i),"issuer:"+i,Sector.OIL_AND_GAS,BusinessType.NON_FINANCIAL,
            Source.MANUAL_VERIFIED,NOW,false,"synthetic verified test evidence",null); }
    static FinancialObservation obs(AnalysisMetric code,String value) { return obs(ASSET,code,value,"RUB",ObservationType.TTM); }
    static FinancialObservation obs(String asset,AnalysisMetric code,String value,String currency,ObservationType type) {
        return new FinancialObservation("test:"+asset+":"+code,asset,1L,code,value==null?null:new BigDecimal(value),code.unit(),
                code.unit()==AnalysisMetric.Unit.MONEY?currency:null,type,type.name(),type==ObservationType.POINT_IN_TIME?null:LocalDate.of(2025,1,1),
                LocalDate.of(2025,12,31),NOW,NOW,"SYNTHETIC",Origin.PROVIDER,Set.of(value==null?QualityStatus.MISSING:QualityStatus.VALID),null,"test-definition:"+code,List.of(),null);
    }
    static FundamentalSnapshot snapshot(Long id) {
        return new FundamentalSnapshot(id,ASSET,"SYNTHETIC",NOW,NOW,null,Instant.parse("2025-01-01T00:00:00Z"),Instant.parse("2025-12-31T00:00:00Z"),"a".repeat(64),"PARTIAL",
                List.of(new MetricValue(MetricCode.PE,new BigDecimal("5"),MetricCode.Unit.MULTIPLE,null,"TTM",QualityStatus.VALID,null,"5"),
                        new MetricValue(MetricCode.ROE,new BigDecimal("0.25"),MetricCode.Unit.UNVERIFIED_RATE,null,null,QualityStatus.SUSPECT,"RATE_SCALE_UNDOCUMENTED","0.25"),
                        new MetricValue(MetricCode.NET_INCOME,new BigDecimal("-10"),MetricCode.Unit.MONEY,"RUB","TTM",QualityStatus.VALID,null,"-10"),
                        new MetricValue(MetricCode.REVENUE,new BigDecimal("100"),MetricCode.Unit.MONEY,"RUB","TTM",QualityStatus.VALID,null,"100")),Map.of());
    }
    static FundamentalAnalysisView input() { return new FundamentalAnalysisView(stock(UID),identity(UID,"energy"),snapshot(1L),true,false,false,false,null,new FundamentalsStore.RequestState(NOW,null,null)); }
}
