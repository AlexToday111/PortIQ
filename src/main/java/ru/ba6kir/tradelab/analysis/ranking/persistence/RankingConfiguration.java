package ru.ba6kir.tradelab.analysis.ranking.persistence;

import java.time.Clock;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import ru.ba6kir.tradelab.analysis.application.service.FinancialMetricService;
import ru.ba6kir.tradelab.analysis.infrastructure.configuration.AnalysisProperties;
import ru.ba6kir.tradelab.analysis.ranking.application.*;
import ru.ba6kir.tradelab.analysis.reporting.application.*;
import ru.ba6kir.tradelab.analysis.scoring.application.service.*;
import ru.ba6kir.tradelab.analysis.scoring.infrastructure.configuration.ScoringProperties;
import ru.ba6kir.tradelab.dividends.application.service.DividendService;
import ru.ba6kir.tradelab.market.application.InstrumentService;
import ru.ba6kir.tradelab.market.application.price.MarketPriceService;
import ru.ba6kir.tradelab.market.infrastructure.tinvest.MarketDataProperties;

@Configuration(proxyBeanMethods=false)
public class RankingConfiguration {
    @Bean RankingStore rankingStore(JdbcTemplate jdbc,ObjectMapper json,PlatformTransactionManager manager) { return new JdbcRankingStore(jdbc,json,manager); }
    @Bean AnalysisSnapshotService analysisSnapshotService(InstrumentService instruments,MarketPriceService prices,DividendService dividends,
            FinancialMetricService metrics,ScoringResultRepository scores,PlatformTransactionManager manager) {
        return new CachedAnalysisSnapshotService(instruments,prices,dividends,metrics,scores,manager);
    }
    @Bean RankingUniverseService rankingUniverseService(InstrumentService instruments,MarketDataProperties market,RankingProperties ranking) {
        return new RankingUniverseService(instruments,market.trackedTickers(),ranking.instrumentUids());
    }
    @Bean RankingEligibilityService rankingEligibilityService(ScoringProperties scoring,RankingProperties ranking,AnalysisProperties analysis,ScoringFingerprint hashes) {
        return new RankingEligibilityService(scoring.model(),ranking.policy(analysis.classificationMaxAge()),hashes);
    }
    @Bean StockRankingService stockRankingService(RankingStore store,AnalysisSnapshotService snapshots,RankingUniverseService universe,
            ScoringProperties scoring,RankingProperties ranking,AnalysisProperties analysis,ScoringFingerprint hashes) {
        return new StockRankingService(store,snapshots,universe::resolve,universe::identifiers,scoring.model(),ranking.policy(analysis.classificationMaxAge()),hashes,Clock.systemUTC());
    }
    @Bean AnalysisExplanationService analysisExplanationService() { return new AnalysisExplanationService(); }
    @Bean StockAnalysisService stockAnalysisService(InstrumentService instruments,AnalysisSnapshotService snapshots,AnalysisExplanationService explanations,RankingEligibilityService eligibility) {
        return new StockAnalysisService(instruments,snapshots,explanations,eligibility,Clock.systemUTC());
    }
}
