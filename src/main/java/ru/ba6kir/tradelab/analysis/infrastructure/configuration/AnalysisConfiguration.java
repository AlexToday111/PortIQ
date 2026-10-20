package ru.ba6kir.tradelab.analysis.infrastructure.configuration;

import java.time.Clock;
import java.util.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import ru.ba6kir.tradelab.analysis.application.port.AnalysisStore;
import ru.ba6kir.tradelab.analysis.application.service.*;
import ru.ba6kir.tradelab.analysis.domain.sector.MetricProfile;
import ru.ba6kir.tradelab.analysis.infrastructure.persistence.JdbcAnalysisStore;
import ru.ba6kir.tradelab.fundamentals.application.service.FundamentalsService;
import ru.ba6kir.tradelab.fundamentals.infrastructure.tinvest.FundamentalsProperties;
import ru.ba6kir.tradelab.dividends.application.service.DividendService;

@Configuration(proxyBeanMethods=false)
public class AnalysisConfiguration {
    @Bean AnalysisStore analysisStore(JdbcTemplate jdbc,PlatformTransactionManager manager,ObjectMapper json) { return new JdbcAnalysisStore(jdbc,manager,json); }
    @Bean SectorClassificationService sectorClassificationService(AnalysisStore store,AnalysisProperties properties) {
        return new SectorClassificationService(store,properties.classifications().stream().map(AnalysisProperties.Seed::classification).toList(),Clock.systemUTC(),properties.classificationMaxAge());
    }
    @Bean FinancialMetricService financialMetricService(FundamentalsService fundamentals,DividendService dividends,SectorClassificationService sectors,AnalysisStore store,AnalysisProperties properties) {
        var profiles=new HashMap<String,MetricProfile>(); properties.profiles().forEach(p -> profiles.put(p.sector()+"/"+p.businessType(),p.profile()));
        return new FinancialMetricService(fundamentals,dividends,sectors,store,Clock.systemUTC(),profiles);
    }
    @Bean SectorComparisonService sectorComparisonService(FinancialMetricService metrics,FundamentalsService fundamentals,SectorClassificationService sectors,
            AnalysisProperties properties,FundamentalsProperties fundamentalsProperties) {
        return new SectorComparisonService(metrics,fundamentals,sectors,Clock.systemUTC(),fundamentalsProperties.snapshotTtl(),fundamentalsProperties.reportingMaxAge(),properties.minimumPeers());
    }
}
