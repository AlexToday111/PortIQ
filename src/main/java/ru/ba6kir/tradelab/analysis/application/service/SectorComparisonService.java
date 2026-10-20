package ru.ba6kir.tradelab.analysis.application.service;

import java.time.*;
import java.util.*;
import ru.ba6kir.tradelab.analysis.domain.comparison.SectorComparison;
import ru.ba6kir.tradelab.fundamentals.application.service.FundamentalsService;

public class SectorComparisonService {
    public record View(FinancialMetricService.View analysis,List<SectorComparison.Result> comparisons,List<String> warnings) {}
    private final FinancialMetricService metrics; private final FundamentalsService fundamentals; private final SectorClassificationService sectors;
    private final Clock clock; private final Duration ttl,reportingAge; private final int minimum;
    public SectorComparisonService(FinancialMetricService metrics,FundamentalsService fundamentals,SectorClassificationService sectors,
            Clock clock,Duration ttl,Duration reportingAge,int minimum) {
        this.metrics=metrics;this.fundamentals=fundamentals;this.sectors=sectors;this.clock=clock;this.ttl=ttl;this.reportingAge=reportingAge;this.minimum=minimum;
    }
    public View compare(String identifier) {
        var target=metrics.get(identifier); var classification=target.sector().classification();
        if (classification==null || target.profile()==null) return new View(target,List.of(),List.of("UNSUPPORTED_PROFILE"));
        var peers=new ArrayList<SectorComparison.Company>(); var warnings=new ArrayList<String>();
        for (var c:sectors.universe()) {
            try {
                var snapshot=fundamentals.cachedAsset(c.assetUid()).orElse(null);
                if (snapshot==null) { peers.add(new SectorComparison.Company(c,List.of(),sectors.fresh(c),metrics.profile(c))); continue; }
                boolean stale=snapshot.lastFetchedAt()==null || snapshot.lastFetchedAt().isAfter(clock.instant()) || !snapshot.lastFetchedAt().plus(ttl).isAfter(clock.instant())
                        || snapshot.periodEnd()!=null && snapshot.periodEnd().plus(reportingAge).isBefore(clock.instant());
                var normalized=new ArrayList<>(new FinancialMetricNormalizer().normalize(snapshot,stale));
                normalized.addAll(new ru.ba6kir.tradelab.analysis.domain.metric.DerivedMetricCalculator().calculate(c.assetUid(),snapshot.id(),normalized,clock.instant()));
                peers.add(new SectorComparison.Company(c,List.copyOf(normalized),sectors.fresh(c),metrics.profile(c)));
            } catch (RuntimeException e) { warnings.add(c.assetUid()+": "+FundamentalsService.code(e)); peers.add(new SectorComparison.Company(c,List.of(),false,metrics.profile(c))); }
        }
        var company=new SectorComparison.Company(classification,target.observations(),target.sector().fresh(),target.profile());
        var calculator=new SectorComparison();
        var results=target.observations().stream().filter(o -> target.profile().metrics().contains(o.metricCode()))
                .map(o -> calculator.compare(o,company,peers,minimum,clock.instant())).toList();
        return new View(target,results,List.copyOf(warnings));
    }
}
