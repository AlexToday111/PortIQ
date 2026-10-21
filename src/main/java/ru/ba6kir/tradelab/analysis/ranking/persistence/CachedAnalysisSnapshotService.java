package ru.ba6kir.tradelab.analysis.ranking.persistence;

import java.time.Instant;
import java.util.*;
import org.springframework.transaction.*;
import org.springframework.transaction.support.TransactionTemplate;
import ru.ba6kir.tradelab.analysis.application.service.FinancialMetricService;
import ru.ba6kir.tradelab.analysis.ranking.domain.RankingRun.Member;
import ru.ba6kir.tradelab.analysis.reporting.application.AnalysisSnapshotService;
import ru.ba6kir.tradelab.analysis.scoring.application.service.ScoringResultRepository;
import ru.ba6kir.tradelab.dividends.application.service.DividendService;
import ru.ba6kir.tradelab.market.application.InstrumentService;
import ru.ba6kir.tradelab.market.application.price.MarketPriceService;

public class CachedAnalysisSnapshotService implements AnalysisSnapshotService {
    private final InstrumentService instruments;private final MarketPriceService prices;private final DividendService dividends;
    private final FinancialMetricService metrics;private final ScoringResultRepository scores;private final TransactionTemplate tx;
    public CachedAnalysisSnapshotService(InstrumentService instruments,MarketPriceService prices,DividendService dividends,
            FinancialMetricService metrics,ScoringResultRepository scores,PlatformTransactionManager manager) {
        this.instruments=instruments;this.prices=prices;this.dividends=dividends;this.metrics=metrics;this.scores=scores;
        tx=new TransactionTemplate(manager);tx.setReadOnly(true);tx.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }
    @Override public List<StockSnapshot> capture(List<Member> members,Instant asOf) {
        return Objects.requireNonNull(tx.execute(status -> members.stream().map(this::capture).toList()));
    }
    private StockSnapshot capture(Member member) {
        if (member.instrumentUid()==null) return new StockSnapshot(member,null,null,null,null,null,List.of(),null,List.of());
        var instrument=instruments.getCached(member.instrumentUid()).orElse(null);
        if (instrument==null) return new StockSnapshot(member,null,null,null,null,null,List.of(),null,List.of("MISSING_CACHED_INSTRUMENT"));
        var score=scores.latest(instrument.uid()).orElse(null);
        var model=score==null?null:scores.model(score.configurationFingerprint()).orElse(null);
        var view=metrics.cached(instrument.uid());var warnings=new ArrayList<>(view.warnings());
        if (score!=null && (view.sector().input().identity()==null
                || !Objects.equals(score.assetUid(),view.sector().input().identity().assetUid()))) warnings.add("CURRENT_ASSET_MAPPING_MISMATCH");
        var dividendView=dividends.cached(instrument.uid());
        return new StockSnapshot(member,instrument,score,model,prices.cachedQuote(instrument.uid()).orElse(null),dividendView,
                score!=null && score.input()!=null?score.input().observations():view.observations(),view.sector().classification(),warnings);
    }
}
