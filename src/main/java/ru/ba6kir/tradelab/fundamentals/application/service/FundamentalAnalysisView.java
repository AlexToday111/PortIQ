package ru.ba6kir.tradelab.fundamentals.application.service;

import java.util.*;
import ru.ba6kir.tradelab.market.domain.StockInstrument;
import ru.ba6kir.tradelab.fundamentals.domain.model.*;
import ru.ba6kir.tradelab.fundamentals.application.port.FundamentalsStore.RequestState;

public record FundamentalAnalysisView(StockInstrument instrument,AssetIdentity identity,FundamentalSnapshot snapshot,
                                      boolean cached,boolean retrievalStale,boolean reportingStale,boolean mappingStale,
                                      String warning,RequestState requestState) {
    public record Assessment(MetricValue observation,Set<QualityStatus> qualities) { }
    public List<Assessment> metrics() {
        if (snapshot==null) return List.of();
        return snapshot.metrics().stream().map(metric -> {
            var flags=EnumSet.of(metric.quality());
            if ((retrievalStale || reportingStale || mappingStale) && metric.value()!=null) flags.add(QualityStatus.STALE);
            return new Assessment(metric,Set.copyOf(flags));
        }).toList();
    }
    /** Safe input for future consumers; no scores or averages are calculated here. */
    public List<MetricValue> usableMetrics() {
        return metrics().stream().filter(m -> m.qualities().equals(Set.of(QualityStatus.VALID)))
                .map(Assessment::observation).toList();
    }
    public long availableCount() { return metrics().stream().filter(m -> m.observation().value()!=null).count(); }
    public long missingCount() { return metrics().stream().filter(m -> m.qualities().contains(QualityStatus.MISSING)
            || m.qualities().contains(QualityStatus.UNSUPPORTED)).count(); }
    public long suspectCount() { return metrics().stream().filter(m -> m.qualities().contains(QualityStatus.SUSPECT)).count(); }
}
