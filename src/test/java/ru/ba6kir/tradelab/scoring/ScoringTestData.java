package ru.ba6kir.tradelab.scoring;

import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import ru.ba6kir.tradelab.analysis.domain.metric.*;
import ru.ba6kir.tradelab.analysis.domain.sector.SectorClassification;
import ru.ba6kir.tradelab.analysis.scoring.domain.model.*;
import ru.ba6kir.tradelab.analysis.scoring.domain.profile.*;
import ru.ba6kir.tradelab.analysis.scoring.domain.rule.FundamentalScoringEngine;
import ru.ba6kir.tradelab.fundamentals.domain.model.QualityStatus;
import static ru.ba6kir.tradelab.analysis.domain.metric.AnalysisMetric.*;
import static ru.ba6kir.tradelab.analysis.domain.metric.FinancialObservation.*;
import static ru.ba6kir.tradelab.analysis.scoring.domain.model.ScoringResult.Pillar;

/** Entirely synthetic, explicitly dated/defined fixtures; no production financial values. */
final class ScoringTestData {
    static final String UID="11111111-1111-1111-1111-111111111111",UID2="22222222-2222-2222-2222-222222222222",ASSET="aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";
    static final Instant NOW=Instant.parse("2026-10-20T12:00:00Z"); static final Clock CLOCK=Clock.fixed(NOW,ZoneOffset.UTC);
    static final LocalDate TODAY=LocalDate.of(2026,10,20),END=LocalDate.of(2025,12,31);
    static Map<Pillar,BigDecimal> weights() { return Map.of(Pillar.Q,new BigDecimal("0.35"),Pillar.F,new BigDecimal("0.25"),Pillar.V,new BigDecimal("0.25"),Pillar.D,new BigDecimal("0.15")); }
    static ScoringModel model() { return new ScoringModel("synthetic-model-1","synthetic-config-1",weights(),EnumSet.allOf(Pillar.class),Duration.ofDays(1),Duration.ofDays(548),1095,3,new BigDecimal("20"),new BigDecimal("6"),ScoringProfiles.defaults()); }
    static SectorClassification classification(String profile) {
        return new SectorClassification(ASSET,"synthetic:issuer",profile.equals("BANK")?SectorClassification.Sector.FINANCIALS:
                profile.equals("OIL_AND_GAS")?SectorClassification.Sector.OIL_AND_GAS:profile.equals("METALS_AND_MINING")?SectorClassification.Sector.METALS_AND_MINING:SectorClassification.Sector.INDUSTRIALS,
                profile.equals("BANK")?SectorClassification.BusinessType.BANK:SectorClassification.BusinessType.NON_FINANCIAL,
                SectorClassification.Source.MANUAL_VERIFIED,NOW,false,"synthetic classification evidence",null);
    }
    static FinancialObservation observation(AnalysisMetric code,String value) {
        boolean div=code==DIVIDEND_HISTORY || code==DIVIDEND_YIELD;
        boolean point=code==CURRENT_RATIO || code==CAPITAL_ADEQUACY || code==ASSET_QUALITY;
        String method=code==CAPITAL_ADEQUACY?"verified:total-regulatory-capital-to-rwa-percent":code==ASSET_QUALITY?"verified:npl-to-gross-loans-percent":"synthetic:verified:"+code;
        return new FinancialObservation("synthetic:"+code,ASSET,div?null:1L,code,value==null?null:new BigDecimal(value),code.unit(),code.unit()==AnalysisMetric.Unit.MONEY?"RUB":null,
                code==DIVIDEND_HISTORY?ObservationType.UNKNOWN:point?ObservationType.POINT_IN_TIME:ObservationType.TTM,
                code==DIVIDEND_HISTORY?"RECORDED_HISTORY":code==DIVIDEND_YIELD?"RECORD_DATES_365D":point?"PIT":"TTM",
                point?null:div?TODAY.minusDays(code==DIVIDEND_YIELD?364:1096):LocalDate.of(2025,1,1),div?TODAY:END,
                NOW.minusSeconds(3600),NOW,"SYNTHETIC",div?Origin.DERIVED:Origin.PROVIDER,Set.of(value==null?QualityStatus.MISSING:QualityStatus.VALID),null,method,List.of(),div?UID:null);
    }
    static ScoringInput input(String profile) {
        var rows=new ArrayList<FinancialObservation>();
        Map<AnalysisMetric,String> values=Map.ofEntries(Map.entry(ROE,"20"),Map.entry(ROA,"2"),Map.entry(ROIC,"15"),Map.entry(NET_MARGIN,"15"),
                Map.entry(NET_DEBT_EBITDA,"1"),Map.entry(CURRENT_RATIO,"1.5"),Map.entry(CAPITAL_ADEQUACY,"16"),Map.entry(ASSET_QUALITY,"1"),
                Map.entry(PB,"1.2"),Map.entry(PE,"6"),Map.entry(PS,"0.7"),Map.entry(EV_EBITDA,"4"),Map.entry(NET_INCOME,"100"),
                Map.entry(FREE_CASH_FLOW,"20"),Map.entry(DIVIDEND_YIELD,"5"),Map.entry(DIVIDEND_HISTORY,"4"));
        values.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(e -> rows.add(observation(e.getKey(),e.getValue())));
        return new ScoringInput(UID,ASSET,"SYNTHETIC TEST COMPANY",classification(profile),true,rows,
                new ScoringInput.DividendEvidence(true,TODAY.minusDays(1096),TODAY.plusDays(365),TODAY,4,4,
                        List.of("synthetic-event-2023","synthetic-event-2024","synthetic-event-2025","synthetic-event-2026"),NOW,null),
                "NORMAL_TRADING",true,NOW,List.of());
    }
    static ScoringInput withRows(ScoringInput input,List<FinancialObservation> rows) { return new ScoringInput(input.instrumentUid(),input.assetUid(),input.name(),input.classification(),input.classificationFresh(),rows,input.dividends(),input.tradingStatus(),input.apiTradeAvailable(),input.metadataFetchedAt(),input.warnings()); }
    static ScoringInput replace(ScoringInput input,AnalysisMetric code,FinancialObservation row) { var rows=new ArrayList<>(input.observations().stream().filter(o -> o.metricCode()!=code).toList()); if (row!=null) rows.add(row); return withRows(input,rows); }
    static FinancialObservation dated(FinancialObservation o,LocalDate end,Instant fetched) { return new FinancialObservation(o.id(),o.assetUid(),o.snapshotId(),o.metricCode(),o.value(),o.unit(),o.currency(),o.observationType(),o.reportingPeriod(),o.periodStart(),end,o.providerAsOfDate(),fetched,o.source(),o.origin(),o.qualityStatus(),o.qualityReason(),o.methodology(),o.inputIds(),o.instrumentUid()); }
    static ScoringResult evaluate(ScoringInput input) { return new FundamentalScoringEngine().evaluate(input,model(),"a".repeat(64),"b".repeat(64),NOW); }
}
