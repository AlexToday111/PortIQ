package ru.ba6kir.tradelab.analysis.telegram;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.*;
import ru.ba6kir.tradelab.analysis.ranking.application.StockRankingService;
import ru.ba6kir.tradelab.analysis.reporting.application.StockAnalysisService;

@Configuration(proxyBeanMethods=false)
@ConditionalOnProperty(prefix="tradelab.telegram",name="enabled",havingValue="true")
public class StockResearchTelegramConfiguration {
    @Bean StockResearchCommand analyzeCommand(StockAnalysisService a,StockRankingService r) { return new StockResearchCommand("analyze",a,r); }
    @Bean StockResearchCommand whyCommand(StockAnalysisService a,StockRankingService r) { return new StockResearchCommand("why",a,r); }
    @Bean StockResearchCommand topCommand(StockAnalysisService a,StockRankingService r) { return new StockResearchCommand("top",a,r); }
    @Bean StockResearchCommand rankstatusCommand(StockAnalysisService a,StockRankingService r) { return new StockResearchCommand("rankstatus",a,r); }
    @Bean StockResearchCommand rankrefreshCommand(StockAnalysisService a,StockRankingService r) { return new StockResearchCommand("rankrefresh",a,r); }
}
