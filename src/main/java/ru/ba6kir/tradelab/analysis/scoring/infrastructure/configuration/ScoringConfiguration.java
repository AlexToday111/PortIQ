package ru.ba6kir.tradelab.analysis.scoring.infrastructure.configuration;

import java.time.Clock;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import ru.ba6kir.tradelab.analysis.application.service.FinancialMetricService;
import ru.ba6kir.tradelab.fundamentals.application.service.FundamentalsService;
import ru.ba6kir.tradelab.dividends.application.service.DividendService;
import ru.ba6kir.tradelab.analysis.scoring.application.service.*;
import ru.ba6kir.tradelab.analysis.scoring.infrastructure.persistence.JdbcScoringResultRepository;

@Configuration(proxyBeanMethods=false)
public class ScoringConfiguration {
    @Bean ScoringFingerprints scoringFingerprints(ObjectMapper json) { return new ScoringFingerprints(json); }
    @Bean ScoringInputProvider scoringInputProvider(FinancialMetricService metrics,FundamentalsService fundamentals,DividendService dividends,PlatformTransactionManager manager) {
        return new CachedScoringInputAdapter(metrics,fundamentals,dividends,manager);
    }
    @Bean ScoringResultRepository scoringResultRepository(JdbcTemplate jdbc,PlatformTransactionManager manager,ObjectMapper json,ScoringFingerprints fingerprints) {
        return new JdbcScoringResultRepository(jdbc,manager,json,fingerprints);
    }
    @Bean FundamentalScoringService fundamentalScoringService(ScoringInputProvider inputs,ScoringResultRepository repository,ScoringProperties properties,ScoringFingerprints fingerprints) {
        return new FundamentalScoringService(inputs,repository,properties.model(),fingerprints,Clock.systemUTC());
    }
}
