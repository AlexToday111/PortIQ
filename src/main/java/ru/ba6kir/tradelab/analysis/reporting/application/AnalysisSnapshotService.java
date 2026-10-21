package ru.ba6kir.tradelab.analysis.reporting.application;

import java.time.Instant;
import java.util.*;
import ru.ba6kir.tradelab.analysis.domain.metric.FinancialObservation;
import ru.ba6kir.tradelab.analysis.domain.sector.SectorClassification;
import ru.ba6kir.tradelab.analysis.ranking.domain.RankingRun.Member;
import ru.ba6kir.tradelab.analysis.scoring.domain.model.ScoringResult;
import ru.ba6kir.tradelab.analysis.scoring.domain.profile.ScoringModel;
import ru.ba6kir.tradelab.dividends.application.service.DividendService;
import ru.ba6kir.tradelab.market.domain.StockInstrument;
import ru.ba6kir.tradelab.market.domain.price.MarketQuote;

/** A single read-only database snapshot, with an explicit evaluation cutoff. No provider calls. */
public interface AnalysisSnapshotService {
    record StockSnapshot(Member member,StockInstrument instrument,ScoringResult score,ScoringModel model,MarketQuote quote,
            DividendService.View dividends,List<FinancialObservation> observations,SectorClassification classification,List<String> warnings) {
        public StockSnapshot { observations=List.copyOf(observations);warnings=List.copyOf(warnings); }
    }
    List<StockSnapshot> capture(List<Member> members,Instant asOf);
}
