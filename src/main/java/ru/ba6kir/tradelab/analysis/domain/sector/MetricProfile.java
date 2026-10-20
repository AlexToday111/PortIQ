package ru.ba6kir.tradelab.analysis.domain.sector;

import java.util.*;
import ru.ba6kir.tradelab.analysis.domain.metric.*;
import ru.ba6kir.tradelab.fundamentals.domain.model.QualityStatus;
import static ru.ba6kir.tradelab.analysis.domain.metric.AnalysisMetric.*;
import static ru.ba6kir.tradelab.analysis.domain.sector.SectorClassification.*;

public record MetricProfile(String name,Set<AnalysisMetric> required,Set<AnalysisMetric> optional) {
    public enum Readiness { READY,PARTIAL,INSUFFICIENT_DATA,STALE_DATA,UNSUPPORTED_PROFILE }
    public MetricProfile {
        required=Set.copyOf(required); optional=Set.copyOf(optional);
        if (required.size()<2 || required.stream().map(AnalysisMetric::family).distinct().count()<2
                || !Collections.disjoint(required,optional)) throw new IllegalArgumentException("Profiles require multiple metric families and disjoint sets");
    }
    public Set<AnalysisMetric> metrics() { var all=EnumSet.copyOf(required); all.addAll(optional); return Set.copyOf(all); }
    public Readiness readiness(List<FinancialObservation> rows,boolean classificationFresh) {
        var relevant=rows.stream().filter(o -> metrics().contains(o.metricCode())).toList();
        if (!classificationFresh || relevant.stream().anyMatch(o -> o.value()!=null && o.qualityStatus().contains(QualityStatus.STALE))) return Readiness.STALE_DATA;
        var usable=EnumSet.noneOf(AnalysisMetric.class); relevant.stream().filter(FinancialObservation::usable).forEach(o -> usable.add(o.metricCode()));
        if (usable.containsAll(required)) return Readiness.READY;
        return usable.isEmpty()?Readiness.INSUFFICIENT_DATA:Readiness.PARTIAL;
    }
    public static MetricProfile defaults(SectorClassification c) {
        if (c.sector()==Sector.UNKNOWN || c.businessType()==BusinessType.UNKNOWN || c.businessType()==BusinessType.NON_BANK_FINANCIAL) return null;
        if (c.businessType()==BusinessType.BANK) return new MetricProfile("BANK",Set.of(PB,ROE),Set.of(PE,ROA,EARNINGS_GROWTH,
                CAPITAL_ADEQUACY,ASSET_QUALITY,DIVIDEND_YIELD,DIVIDEND_HISTORY,RECORDED_DIVIDEND_EVENTS));
        if (c.sector()==Sector.OIL_AND_GAS || c.sector()==Sector.METALS_AND_MINING)
            return new MetricProfile(c.sector().name(),Set.of(EV_EBITDA,NET_DEBT_EBITDA,FREE_CASH_FLOW),Set.of(PE,ROIC,NET_MARGIN,DIVIDEND_YIELD,DIVIDEND_HISTORY));
        return new MetricProfile("COMMON_NON_FINANCIAL",Set.of(PE,NET_MARGIN),Set.of(ROIC,CURRENT_RATIO,NET_DEBT_EBITDA,FREE_CASH_FLOW,
                REVENUE_GROWTH_1Y,EARNINGS_YIELD,DIVIDEND_HISTORY));
    }
}
