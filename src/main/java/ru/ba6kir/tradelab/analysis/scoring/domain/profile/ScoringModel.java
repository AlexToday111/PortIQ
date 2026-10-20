package ru.ba6kir.tradelab.analysis.scoring.domain.profile;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.*;
import ru.ba6kir.tradelab.analysis.domain.metric.AnalysisMetric;
import ru.ba6kir.tradelab.analysis.scoring.domain.model.ScoringResult.Pillar;
import ru.ba6kir.tradelab.analysis.scoring.domain.rule.MetricScoringRule;

public record ScoringModel(String modelVersion,String configurationVersion,Map<Pillar,BigDecimal> weights,Set<Pillar> mandatoryPillars,
        Duration observationMaxAge,Duration financialPeriodMaxAge,int minimumDividendDays,int minimumDividendYears,
        BigDecimal extremeYield,BigDecimal highLeverage,Map<String,Profile> profiles) {
    public record Profile(String id,List<MetricScoringRule> rules,Set<AnalysisMetric> requiredContext,Set<AnalysisMetric> optionalContext) {
        public Profile {
            rules=List.copyOf(rules); requiredContext=Set.copyOf(requiredContext); optionalContext=Set.copyOf(optionalContext);
            if (id==null || rules.stream().map(MetricScoringRule::id).distinct().count()!=rules.size()
                    || rules.stream().map(r -> r.pillar()+":"+r.metric()).distinct().count()!=rules.size()) throw new IllegalArgumentException("Duplicate scoring rules/metric definitions");
            if (id.equals("BANK") && rules.stream().anyMatch(r -> !Set.of(AnalysisMetric.ROE,AnalysisMetric.ROA,AnalysisMetric.CAPITAL_ADEQUACY,
                    AnalysisMetric.ASSET_QUALITY,AnalysisMetric.PE,AnalysisMetric.PB,AnalysisMetric.DIVIDEND_YIELD,AnalysisMetric.DIVIDEND_HISTORY).contains(r.metric())))
                throw new IllegalArgumentException("Unsupported bank scoring metric");
            if (id.equals("BANK") && rules.stream().anyMatch(r -> r.metric()==AnalysisMetric.CAPITAL_ADEQUACY
                    && !"verified:total-regulatory-capital-to-rwa-percent".equals(r.requiredMethodology())
                    || r.metric()==AnalysisMetric.ASSET_QUALITY && !"verified:npl-to-gross-loans-percent".equals(r.requiredMethodology())))
                throw new IllegalArgumentException("Verified bank measure definitions cannot be removed from scoring rules");
            for (var p:Pillar.values()) if (rules.stream().filter(r -> r.pillar()==p).map(MetricScoringRule::weight).reduce(BigDecimal.ZERO,BigDecimal::add).compareTo(BigDecimal.ONE)!=0)
                throw new IllegalArgumentException("Metric weights must sum to one within each pillar");
        }
    }
    public ScoringModel {
        weights=Collections.unmodifiableMap(new EnumMap<>(weights)); mandatoryPillars=Set.copyOf(mandatoryPillars); profiles=Map.copyOf(profiles);
        if (modelVersion==null || !modelVersion.matches("[A-Za-z0-9._-]{1,80}") || configurationVersion==null || !configurationVersion.matches("[A-Za-z0-9._-]{1,80}"))
            throw new IllegalArgumentException("Explicit model/configuration version required");
        if (weights.size()!=4 || weights.values().stream().anyMatch(v -> v==null || v.signum()<0 || v.compareTo(BigDecimal.ONE)>0)
                || weights.values().stream().reduce(BigDecimal.ZERO,BigDecimal::add).compareTo(BigDecimal.ONE)!=0 || mandatoryPillars.isEmpty())
            throw new IllegalArgumentException("Four pillar weights must sum to one");
        for (var p:Pillar.values()) if (weights.get(p).signum()>0 != mandatoryPillars.contains(p))
            throw new IllegalArgumentException("Every weighted pillar must remain mandatory; disabled pillars require explicit zero weight");
        if (observationMaxAge==null || observationMaxAge.isNegative() || observationMaxAge.isZero() || observationMaxAge.compareTo(Duration.ofDays(30))>0
                || financialPeriodMaxAge==null || financialPeriodMaxAge.compareTo(Duration.ofDays(30))<0 || financialPeriodMaxAge.compareTo(Duration.ofDays(3650))>0
                || minimumDividendDays<365 || minimumDividendDays>1827 || minimumDividendYears<1 || minimumDividendYears>5
                || extremeYield==null || extremeYield.signum()<=0 || highLeverage==null || highLeverage.signum()<=0)
            throw new IllegalArgumentException("Invalid scoring evidence policy");
    }
}
