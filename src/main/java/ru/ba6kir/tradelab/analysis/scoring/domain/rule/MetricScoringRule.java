package ru.ba6kir.tradelab.analysis.scoring.domain.rule;

import java.math.*;
import java.util.*;
import ru.ba6kir.tradelab.analysis.domain.metric.*;
import ru.ba6kir.tradelab.analysis.scoring.domain.model.ScoringResult.Pillar;

public record MetricScoringRule(String id,Pillar pillar,AnalysisMetric metric,AnalysisMetric.Unit unit,FinancialObservation.Origin origin,
        Direction direction,boolean required,BigDecimal weight,List<Point> points,String requiredMethodology,Transformation transformation) {
    public enum Direction { HIGHER,LOWER,HEALTHY_INTERVAL,NOT_SUITABLE }
    public enum Transformation { IDENTITY,DISTINCT_RECORDED_YEARS }
    public record Point(BigDecimal value,BigDecimal score) {}
    public MetricScoringRule {
        Objects.requireNonNull(id); Objects.requireNonNull(pillar); Objects.requireNonNull(metric); Objects.requireNonNull(unit);
        Objects.requireNonNull(direction); Objects.requireNonNull(origin); Objects.requireNonNull(transformation);
        points=List.copyOf(points);
        if (!id.matches("[A-Za-z0-9:._-]{1,160}") || unit!=metric.unit() || weight==null || weight.signum()<0 || weight.compareTo(BigDecimal.ONE)>0 || points.size()<2 || points.size()>100)
            throw new IllegalArgumentException("Invalid scoring rule");
        var expected=switch(pillar) { case Q -> AnalysisMetric.Family.PROFITABILITY; case F -> AnalysisMetric.Family.STABILITY;
            case V -> AnalysisMetric.Family.VALUATION; case D -> AnalysisMetric.Family.DIVIDENDS; };
        if (metric.family()!=expected) throw new IllegalArgumentException("Metric does not belong to the scoring pillar");
        for (int i=0;i<points.size();i++) {
            var p=points.get(i);
            if (p.value()==null || p.score()==null || p.score().signum()<0 || p.score().compareTo(BigDecimal.valueOf(100))>0
                    || i>0 && p.value().compareTo(points.get(i-1).value())<=0) throw new IllegalArgumentException("Invalid scoring breakpoints");
            if (i>0 && (direction==Direction.HIGHER && p.score().compareTo(points.get(i-1).score())<0
                    || direction==Direction.LOWER && p.score().compareTo(points.get(i-1).score())>0)) throw new IllegalArgumentException("Direction contradicts scoring points");
        }
        if (direction==Direction.NOT_SUITABLE && weight.signum()>0) throw new IllegalArgumentException("Unscorable metric cannot carry weight");
    }
    /** Bounded, piecewise-linear, explicit absolute thresholds; no peer estimates or extrapolation. */
    public BigDecimal score(BigDecimal value) {
        if (direction==Direction.NOT_SUITABLE) return null;
        if (value.compareTo(points.getFirst().value())<=0) return points.getFirst().score().setScale(6,RoundingMode.HALF_UP);
        for (int i=1;i<points.size();i++) if (value.compareTo(points.get(i).value())<=0) {
            var a=points.get(i-1); var b=points.get(i);
            return a.score().add(value.subtract(a.value()).multiply(b.score().subtract(a.score()))
                    .divide(b.value().subtract(a.value()),12,RoundingMode.HALF_UP)).setScale(6,RoundingMode.HALF_UP);
        }
        return points.getLast().score().setScale(6,RoundingMode.HALF_UP);
    }
}
