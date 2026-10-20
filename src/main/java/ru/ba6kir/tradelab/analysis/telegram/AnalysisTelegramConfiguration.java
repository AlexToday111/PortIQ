package ru.ba6kir.tradelab.analysis.telegram;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.*;
import ru.ba6kir.tradelab.analysis.application.service.*;

@Configuration(proxyBeanMethods=false)
@ConditionalOnProperty(prefix="tradelab.telegram",name="enabled",havingValue="true")
public class AnalysisTelegramConfiguration {
    @Bean AnalysisCommand sectorCommand(FinancialMetricService metrics,SectorComparisonService comparisons) { return new AnalysisCommand("sector",metrics,comparisons); }
    @Bean AnalysisCommand metricsCommand(FinancialMetricService metrics,SectorComparisonService comparisons) { return new AnalysisCommand("metrics",metrics,comparisons); }
    @Bean AnalysisCommand compareCommand(FinancialMetricService metrics,SectorComparisonService comparisons) { return new AnalysisCommand("compare",metrics,comparisons); }
}
