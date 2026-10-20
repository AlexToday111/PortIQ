package ru.ba6kir.tradelab.analysis.scoring.infrastructure.configuration;

import java.time.*;
import java.util.*;
import org.springframework.transaction.*;
import org.springframework.transaction.support.TransactionTemplate;
import ru.ba6kir.tradelab.analysis.application.service.FinancialMetricService;
import ru.ba6kir.tradelab.analysis.scoring.application.service.ScoringInputProvider;
import ru.ba6kir.tradelab.analysis.scoring.domain.model.ScoringInput;
import ru.ba6kir.tradelab.fundamentals.application.service.FundamentalsService;
import ru.ba6kir.tradelab.dividends.application.service.DividendService;
import ru.ba6kir.tradelab.dividends.domain.model.DividendEvent;

public class CachedScoringInputAdapter implements ScoringInputProvider {
    private final FinancialMetricService metrics; private final FundamentalsService fundamentals; private final DividendService dividends; private final TransactionTemplate tx;
    public CachedScoringInputAdapter(FinancialMetricService metrics,FundamentalsService fundamentals,DividendService dividends,PlatformTransactionManager manager) {
        this.metrics=metrics;this.fundamentals=fundamentals;this.dividends=dividends;this.tx=new TransactionTemplate(manager);
        tx.setReadOnly(true);tx.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }
    @Override public ScoringInput instrument(String uid) { return Objects.requireNonNull(tx.execute(s -> capture(uid))); }
    @Override public ScoringInput asset(String uid) {
        return Objects.requireNonNull(tx.execute(s -> {
            var ids=fundamentals.cachedInstrumentUids(uid);
            if (ids.size()!=1) throw new IllegalStateException(ids.isEmpty()?"ASSET_INSTRUMENT_NOT_FOUND":"ASSET_INSTRUMENT_AMBIGUOUS");
            var input=capture(ids.getFirst());
            if (!Objects.equals(uid,input.assetUid())) throw new IllegalStateException("ASSET_MAPPING_MISMATCH");
            return input;
        }));
    }
    private ScoringInput capture(String uid) {
        var view=metrics.cached(uid); var stock=view.sector().input().instrument();
        var identity=view.sector().input().identity(); ScoringInput.DividendEvidence evidence=null;
        try {
            var div=dividends.cached(uid);
            var recorded=div.events().stream().filter(o -> o.event().recordDate()!=null && !o.event().recordDate().isAfter(div.asOf())
                    && div.state().from()!=null && !o.event().recordDate().isBefore(div.state().from())
                    && div.state().to()!=null && !o.event().recordDate().isAfter(div.state().to())
                    && o.event().status(div.asOf())==DividendEvent.Status.HISTORICAL_RECORDED).toList();
            boolean reliable=!div.stale() && div.warning()==null && div.trailingSum().value()!=null
                    && div.events().stream().noneMatch(o -> o.event().recordDate()==null)
                    && recorded.stream().allMatch(o -> !o.ambiguous() && o.event().usable() && o.event().providerCreatedAt()!=null
                        && "Regular Cash".equalsIgnoreCase(o.event().dividendType()) && !o.lastSeen().isBefore(div.state().lastSuccess()));
            evidence=new ScoringInput.DividendEvidence(reliable,div.state().from(),div.state().to(),div.asOf(),recorded.size(),
                    (int)recorded.stream().map(o -> o.event().recordDate().getYear()).distinct().count(),
                    recorded.stream().map(o -> o.event().contentHash()).sorted().toList(),div.state().lastSuccess(),reliable?null:"UNRELIABLE_CACHED_DIVIDENDS");
        } catch (RuntimeException ignored) { /* Missing evidence remains absent; no network fallback. */ }
        return new ScoringInput(uid,identity==null?null:identity.assetUid(),stock.name(),view.sector().classification(),view.sector().fresh(),
                view.observations().stream().sorted(Comparator.comparing(ru.ba6kir.tradelab.analysis.domain.metric.FinancialObservation::id)).toList(),
                evidence,stock.tradingStatus(),stock.apiTradeAvailable(),stock.updatedAt(),view.warnings());
    }
}
