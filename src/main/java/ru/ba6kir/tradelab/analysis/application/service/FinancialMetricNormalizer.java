package ru.ba6kir.tradelab.analysis.application.service;

import java.time.*;
import java.util.*;
import ru.ba6kir.tradelab.analysis.domain.metric.*;
import ru.ba6kir.tradelab.fundamentals.application.service.FundamentalAnalysisView;
import ru.ba6kir.tradelab.fundamentals.domain.model.*;
import static ru.ba6kir.tradelab.analysis.domain.metric.FinancialObservation.*;

public class FinancialMetricNormalizer {
    public List<FinancialObservation> normalize(FundamentalAnalysisView view) {
        return view.snapshot()==null?List.of():normalize(view.snapshot(),view.retrievalStale() || view.reportingStale() || view.mappingStale() || view.warning()!=null);
    }
    public List<FinancialObservation> normalize(FundamentalSnapshot snapshot,boolean stale) {
        var result=new ArrayList<FinancialObservation>();
        for (var m:snapshot.metrics()) {
            var code=AnalysisMetric.valueOf(m.code().name());
            var unit=switch (m.unit()) { case MONEY -> AnalysisMetric.Unit.MONEY; case MONEY_PER_SHARE -> AnalysisMetric.Unit.PER_SHARE;
                case MULTIPLE -> AnalysisMetric.Unit.MULTIPLE; case RATIO -> AnalysisMetric.Unit.RATIO;
                case UNVERIFIED_RATE -> AnalysisMetric.Unit.UNVERIFIED_RATE; case COUNT -> AnalysisMetric.Unit.COUNT; };
            var type=m.reportingPeriod()==null?ObservationType.UNKNOWN:switch(m.reportingPeriod()) {
                case "TTM" -> ObservationType.TTM; case "FY" -> ObservationType.ANNUAL;
                case "MRQ" -> code==AnalysisMetric.TOTAL_DEBT?ObservationType.POINT_IN_TIME:ObservationType.QUARTERLY;
                default -> ObservationType.UNKNOWN;
            };
            var qualities=EnumSet.of(m.quality());
            if (stale && m.value()!=null) qualities.add(QualityStatus.STALE);
            String reason=(m.reason()==null?"":m.reason()+";")+"METRIC_REPORTING_DATES_UNKNOWN";
            // Snapshot fiscal dates describe source context, not dates of every TTM/MRQ/FY statistic.
            result.add(new FinancialObservation("fund:"+snapshot.id()+":"+code,snapshot.assetUid(),snapshot.id(),code,m.value(),unit,m.currency()==null?null:m.currency().toUpperCase(Locale.ROOT),
                    type,m.reportingPeriod(),null,null,snapshot.providerAsOf(),snapshot.lastFetchedAt(),snapshot.source(),Origin.PROVIDER,
                    qualities,reason,"provider:"+m.code().field(),List.of(),null));
        }
        for (var code:AnalysisMetric.values()) if (result.stream().noneMatch(o -> o.metricCode()==code))
            result.add(new FinancialObservation("missing:"+snapshot.id()+":"+code,snapshot.assetUid(),snapshot.id(),code,null,code.unit(),null,
                    ObservationType.UNKNOWN,null,null,null,null,snapshot.lastFetchedAt(),snapshot.source(),Origin.PROVIDER,
                    Set.of(QualityStatus.UNSUPPORTED),"NO_COLLECTED_SOURCE_INPUT",null,List.of(),null));
        return List.copyOf(result);
    }
}
