package ru.ba6kir.tradelab.research;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import ru.ba6kir.tradelab.analysis.domain.metric.*;
import ru.ba6kir.tradelab.analysis.domain.sector.SectorClassification;
import ru.ba6kir.tradelab.analysis.ranking.domain.*;
import ru.ba6kir.tradelab.analysis.reporting.application.AnalysisSnapshotService.StockSnapshot;
import ru.ba6kir.tradelab.analysis.scoring.domain.model.*;
import ru.ba6kir.tradelab.analysis.scoring.domain.profile.*;
import ru.ba6kir.tradelab.analysis.scoring.domain.rule.FundamentalScoringEngine;
import ru.ba6kir.tradelab.analysis.scoring.infrastructure.configuration.ScoringFingerprints;
import ru.ba6kir.tradelab.dividends.application.port.DividendStore;
import ru.ba6kir.tradelab.dividends.application.service.DividendService;
import ru.ba6kir.tradelab.dividends.domain.service.DividendAnalytics;
import ru.ba6kir.tradelab.fundamentals.domain.model.QualityStatus;
import ru.ba6kir.tradelab.market.domain.StockInstrument;
import ru.ba6kir.tradelab.market.domain.price.MarketQuote;
import static ru.ba6kir.tradelab.analysis.domain.metric.AnalysisMetric.*;
import static ru.ba6kir.tradelab.analysis.domain.metric.FinancialObservation.*;
import static ru.ba6kir.tradelab.analysis.scoring.domain.model.ScoringResult.Pillar;

/** Isolated synthetic research fixtures. Never registered as production data/providers. */
final class ResearchTestData {
    static final Instant NOW=Instant.parse("2026-10-21T12:00:00Z");static final Clock CLOCK=Clock.fixed(NOW,ZoneOffset.UTC);
    static final LocalDate TODAY=NOW.atOffset(ZoneOffset.UTC).toLocalDate(),END=LocalDate.of(2025,12,31);
    static final ScoringFingerprints HASHES=new ScoringFingerprints(new ObjectMapper().findAndRegisterModules());
    static String uid(int n) {return UUID.nameUUIDFromBytes(("synthetic-security-"+n).getBytes(StandardCharsets.UTF_8)).toString();}
    static String asset(int n) {return UUID.nameUUIDFromBytes(("synthetic-asset-"+n).getBytes(StandardCharsets.UTF_8)).toString();}
    static ScoringModel model() {return new ScoringModel("synthetic-model-1","synthetic-config-1",Map.of(Pillar.Q,new BigDecimal("0.35"),Pillar.F,new BigDecimal("0.25"),Pillar.V,new BigDecimal("0.25"),Pillar.D,new BigDecimal("0.15")),
            EnumSet.allOf(Pillar.class),Duration.ofDays(1),Duration.ofDays(548),1095,3,new BigDecimal("20"),new BigDecimal("6"),ScoringProfiles.defaults());}
    static RankingPolicy policy() {return new RankingPolicy("synthetic-ranking-1",Duration.ofHours(1),Duration.ofSeconds(60),Duration.ofDays(1),Duration.ofDays(3),Duration.ofDays(1),Duration.ofDays(365));}
    static StockInstrument instrument(int n) {return new StockInstrument(uid(n),"SYNTH"+n,"TQBR","SYNTHETIC TEST COMPANY "+n,null,null,"RUB","MOEX",1,"NORMAL_TRADING",true,NOW,"SYNTHETIC");}
    static FinancialObservation row(int n,AnalysisMetric metric,String value) {
        boolean div=metric==DIVIDEND_YIELD || metric==DIVIDEND_HISTORY,point=metric==CURRENT_RATIO || metric==CAPITAL_ADEQUACY || metric==ASSET_QUALITY;
        return new FinancialObservation("synthetic:"+n+":"+metric,asset(n),div?null:1L,metric,value==null?null:new BigDecimal(value),metric.unit(),metric.unit()==AnalysisMetric.Unit.MONEY?"RUB":null,
                metric==DIVIDEND_HISTORY?ObservationType.UNKNOWN:point?ObservationType.POINT_IN_TIME:ObservationType.TTM,
                metric==DIVIDEND_HISTORY?"RECORDED_HISTORY":metric==DIVIDEND_YIELD?"RECORD_DATES_365D":point?"PIT":"TTM",point?null:div?TODAY.minusDays(metric==DIVIDEND_YIELD?364:1096):LocalDate.of(2025,1,1),
                div?TODAY:END,NOW.minusSeconds(3600),NOW,"SYNTHETIC",div?Origin.DERIVED:Origin.PROVIDER,Set.of(value==null?QualityStatus.MISSING:QualityStatus.VALID),null,
                metric==CAPITAL_ADEQUACY?"verified:total-regulatory-capital-to-rwa-percent":metric==ASSET_QUALITY?"verified:npl-to-gross-loans-percent":"synthetic:verified:"+metric,List.of(),div?uid(n):null);
    }
    static ScoringInput input(int n,String profile) {
        var c=new SectorClassification(asset(n),"synthetic:issuer:"+n,profile.equals("BANK")?SectorClassification.Sector.FINANCIALS:profile.equals("OIL_AND_GAS")?SectorClassification.Sector.OIL_AND_GAS:
                profile.equals("METALS_AND_MINING")?SectorClassification.Sector.METALS_AND_MINING:SectorClassification.Sector.INDUSTRIALS,
                profile.equals("BANK")?SectorClassification.BusinessType.BANK:SectorClassification.BusinessType.NON_FINANCIAL,SectorClassification.Source.MANUAL_VERIFIED,NOW,false,"SYNTHETIC VERIFIED CLASSIFICATION",null);
        var values=Map.ofEntries(Map.entry(ROE,"20"),Map.entry(ROA,"2"),Map.entry(ROIC,"15"),Map.entry(NET_MARGIN,"15"),Map.entry(NET_DEBT_EBITDA,"1"),Map.entry(CURRENT_RATIO,"1.5"),
                Map.entry(CAPITAL_ADEQUACY,"16"),Map.entry(ASSET_QUALITY,"1"),Map.entry(PB,"1.2"),Map.entry(PE,"6"),Map.entry(PS,"0.7"),Map.entry(EV_EBITDA,"4"),Map.entry(NET_INCOME,"100"),Map.entry(FREE_CASH_FLOW,"20"),Map.entry(DIVIDEND_YIELD,"5"),Map.entry(DIVIDEND_HISTORY,"4"));
        return new ScoringInput(uid(n),asset(n),instrument(n).name(),c,true,values.entrySet().stream().map(e -> row(n,e.getKey(),e.getValue())).toList(),
                new ScoringInput.DividendEvidence(true,TODAY.minusDays(1096),TODAY.plusDays(365),TODAY,4,4,List.of("synthetic-event-2023","synthetic-event-2024","synthetic-event-2025","synthetic-event-2026"),NOW,null),"NORMAL_TRADING",true,NOW,List.of());
    }
    static ScoringInput rows(ScoringInput input,List<FinancialObservation> rows) {return new ScoringInput(input.instrumentUid(),input.assetUid(),input.name(),input.classification(),true,rows,input.dividends(),input.tradingStatus(),input.apiTradeAvailable(),input.metadataFetchedAt(),input.warnings());}
    static ScoringInput replace(ScoringInput input,AnalysisMetric code,FinancialObservation row) {var rows=new ArrayList<>(input.observations().stream().filter(o -> o.metricCode()!=code).toList());if(row!=null) rows.add(row);return rows(input,rows);}
    static ScoringResult scored(ScoringInput input,ScoringModel model,int id) {return new FundamentalScoringEngine().evaluate(input,model,HASHES.hash(model),HASHES.hash(input),NOW).withId(id);}
    static StockSnapshot snapshot(int n,String profile) {return snapshot(n,input(n,profile),model());}
    static StockSnapshot snapshot(int n,ScoringInput input,ScoringModel model) {
        var i=instrument(n);var d=new DividendService.View(i,List.of(),new DividendStore.State(TODAY.minusDays(1096),TODAY.plusDays(365),NOW,null,null),TODAY,false,null,
                new DividendAnalytics.Result(new BigDecimal("5"),"RUB",null),new DividendAnalytics.Result(new BigDecimal("5"),null,null),new DividendAnalytics.ReferencePrice(new BigDecimal("100"),"RUB",TODAY));
        return new StockSnapshot(new RankingRun.Member("uid:"+i.uid(),i.uid(),i.uid(),null),i,scored(input,model,n),model,new MarketQuote(i.uid(),new BigDecimal("100"),"RUB",NOW.minusSeconds(3600),NOW,"SYNTHETIC"),d,input.observations(),input.classification(),List.of());
    }
    static StockSnapshot withScore(StockSnapshot s,ScoringResult score,ScoringModel model) {return new StockSnapshot(s.member(),s.instrument(),score,model,s.quote(),s.dividends(),score==null?s.observations():score.input().observations(),s.classification(),s.warnings());}
    static StockSnapshot withQuote(StockSnapshot s,MarketQuote quote) {return new StockSnapshot(s.member(),s.instrument(),s.score(),s.model(),quote,s.dividends(),s.observations(),s.classification(),s.warnings());}
    static StockSnapshot withInstrument(StockSnapshot s,StockInstrument i) {return new StockSnapshot(s.member(),i,s.score(),s.model(),s.quote(),s.dividends(),s.observations(),s.classification(),s.warnings());}
}
