package ru.ba6kir.tradelab.analysis.application.service;

import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import org.slf4j.*;
import ru.ba6kir.tradelab.analysis.application.port.AnalysisStore;
import ru.ba6kir.tradelab.analysis.domain.metric.*;
import ru.ba6kir.tradelab.analysis.domain.sector.*;
import ru.ba6kir.tradelab.dividends.application.service.DividendService;
import ru.ba6kir.tradelab.dividends.domain.model.DividendEvent;
import ru.ba6kir.tradelab.fundamentals.application.service.*;
import ru.ba6kir.tradelab.fundamentals.domain.model.QualityStatus;
import static ru.ba6kir.tradelab.analysis.domain.metric.FinancialObservation.*;

public class FinancialMetricService {
    private static final Logger log=LoggerFactory.getLogger(FinancialMetricService.class);
    public record SectorView(FundamentalAnalysisView input,SectorClassification classification,boolean fresh) {}
    public record View(SectorView sector,MetricProfile profile,MetricProfile.Readiness readiness,List<FinancialObservation> observations,List<String> warnings,Instant calculatedAt) {}
    private final FundamentalsService fundamentals; private final DividendService dividends; private final SectorClassificationService sectors;
    private final AnalysisStore store; private final Clock clock; private final Map<String,MetricProfile> profiles;
    private final FinancialMetricNormalizer normalizer=new FinancialMetricNormalizer();
    public FinancialMetricService(FundamentalsService fundamentals,DividendService dividends,SectorClassificationService sectors,AnalysisStore store,
            Clock clock,Map<String,MetricProfile> profiles) {
        this.fundamentals=fundamentals;this.dividends=dividends;this.sectors=sectors;this.store=store;this.clock=clock;this.profiles=Map.copyOf(profiles);
    }
    public SectorView sector(String identifier) {
        var input=fundamentals.getLatest(identifier);
        var c=input.identity()==null?null:sectors.classify(input.identity());
        return new SectorView(input,c,c!=null && sectors.fresh(c) && !input.mappingStale());
    }
    public MetricProfile profile(SectorClassification c) {
        return c==null?null:profiles.getOrDefault(c.sector()+"/"+c.businessType(),MetricProfile.defaults(c));
    }
    public View get(String identifier) {
        return build(sector(identifier),false);
    }
    public View cached(String uid) {
        var input=fundamentals.cachedInstrument(uid);
        var c=input.identity()==null?null:sectors.classify(input.identity(),false);
        return build(new SectorView(input,c,c!=null && sectors.fresh(c) && !input.mappingStale()),true);
    }
    private View build(SectorView sector,boolean localOnly) {
        var input=sector.input(); var warnings=new ArrayList<String>();
        if (input.warning()!=null) warnings.add(input.warning());
        if (input.snapshot()==null) warnings.add("NO_FUNDAMENTAL_SNAPSHOT");
        var observations=new ArrayList<>(normalizer.normalize(input));
        if (input.snapshot()!=null) {
            var derived=new DerivedMetricCalculator().calculate(input.snapshot().assetUid(),input.snapshot().id(),observations,clock.instant());
            if (!localOnly) store.saveDerived(derived); observations.addAll(derived);
        }
        try {
            var div=localOnly?dividends.cached(input.instrument().uid()):dividends.get(input.instrument().uid(),1);
            if (input.identity()!=null) observations.addAll(dividends(input.identity().assetUid(),div));
        } catch (RuntimeException e) { warnings.add("DIVIDENDS_"+FundamentalsService.code(e)); log.warn("Analysis dividend inputs unavailable reason={}",FundamentalsService.code(e)); }
        var profile=profile(sector.classification());
        var readiness=profile==null?MetricProfile.Readiness.UNSUPPORTED_PROFILE:profile.readiness(observations,sector.fresh());
        return new View(sector,profile,readiness,List.copyOf(observations),List.copyOf(warnings),clock.instant());
    }
    private List<FinancialObservation> dividends(String asset,DividendService.View v) {
        var result=new ArrayList<FinancialObservation>(); var yield=v.trailingYield();
        var hist=v.events().stream().filter(o -> o.event().recordDate()!=null && !o.event().recordDate().isAfter(v.asOf())
                && v.state().from()!=null && !o.event().recordDate().isBefore(v.state().from())
                && v.state().to()!=null && !o.event().recordDate().isAfter(v.state().to())
                && o.event().status(v.asOf())==DividendEvent.Status.HISTORICAL_RECORDED).toList();
        boolean uncertain=v.stale() || v.warning()!=null || v.events().stream().anyMatch(o -> o.event().recordDate()==null)
                || hist.stream().anyMatch(o -> o.ambiguous() || !o.event().usable() || o.event().providerCreatedAt()==null);
        for (var code:List.of(AnalysisMetric.DIVIDEND_YIELD,AnalysisMetric.RECORDED_DIVIDEND_EVENTS,AnalysisMetric.DIVIDEND_HISTORY)) {
            BigDecimal value=code==AnalysisMetric.DIVIDEND_YIELD?yield.value():uncertain || hist.isEmpty()?null:BigDecimal.valueOf(hist.size());
            String reason=value==null?code==AnalysisMetric.DIVIDEND_YIELD?yield.reason():"NO_RELIABLE_OBSERVED_HISTORY":null;
            result.add(new FinancialObservation("dividends:"+v.instrument().uid()+":"+v.state().lastSuccess()+":"+code,asset,null,code,value,code.unit(),null,
                    code==AnalysisMetric.DIVIDEND_YIELD?ObservationType.TTM:ObservationType.UNKNOWN,
                    code==AnalysisMetric.DIVIDEND_YIELD?"RECORD_DATES_365D":"RECORDED_HISTORY",code==AnalysisMetric.DIVIDEND_YIELD?v.asOf().minusDays(364):v.state().from(),
                    v.asOf(),v.price()==null?null:v.price().date().atStartOfDay(ZoneOffset.UTC).toInstant(),v.state().lastSuccess(),"T_INVEST_DIVIDENDS",Origin.DERIVED,
                    Set.of(value==null?QualityStatus.MISSING:QualityStatus.VALID),reason,
                    code==AnalysisMetric.DIVIDEND_YIELD?"DividendAnalytics.trailingYield provider-defined recorded amount / saved same-currency reference; not received income"
                            :"Count/history of recorded non-cancelled observations; not proof of receipts",v.events().stream().map(o -> o.event().contentHash()).sorted().toList(),v.instrument().uid()));
        }
        return result;
    }
}
