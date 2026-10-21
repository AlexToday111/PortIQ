package ru.ba6kir.tradelab.analysis.reporting.application;

import java.time.*;
import java.util.*;
import ru.ba6kir.tradelab.analysis.ranking.domain.RankingRun;
import ru.ba6kir.tradelab.analysis.ranking.application.RankingEligibilityService;
import ru.ba6kir.tradelab.analysis.reporting.domain.AnalysisExplanation;
import ru.ba6kir.tradelab.market.application.*;

public class StockAnalysisService {
    public record Report(Instant generatedAt,AnalysisSnapshotService.StockSnapshot snapshot,List<AnalysisExplanation> explanations,RankingRun.Entry eligibility) {
        public Report { explanations=List.copyOf(explanations); }
    }
    private final InstrumentService instruments;private final AnalysisSnapshotService snapshots;private final AnalysisExplanationService explanations;
    private final RankingEligibilityService eligibility;private final Clock clock;
    public StockAnalysisService(InstrumentService instruments,AnalysisSnapshotService snapshots,AnalysisExplanationService explanations,RankingEligibilityService eligibility,Clock clock) {
        this.instruments=instruments;this.snapshots=snapshots;this.explanations=explanations;this.eligibility=eligibility;this.clock=clock;
    }
    public Report analyze(String identifier) {
        String uid=identifier!=null && identifier.length()==36?InstrumentService.validateUid(identifier):instruments.resolve(identifier).uid();
        var now=clock.instant();var member=new RankingRun.Member("uid:"+uid,identifier,uid,null);
        var captured=snapshots.capture(List.of(member),now);
        if (captured.size()!=1 || captured.getFirst().instrument()==null) throw new MarketException(MarketException.Reason.NOT_FOUND);
        var s=captured.getFirst();return new Report(now,s,explanations.explain(s.score(),s.model()),eligibility.assess(s,now));
    }
}
