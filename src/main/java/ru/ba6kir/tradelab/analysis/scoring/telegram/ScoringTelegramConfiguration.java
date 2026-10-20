package ru.ba6kir.tradelab.analysis.scoring.telegram;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.*;
import ru.ba6kir.tradelab.market.application.InstrumentService;
import ru.ba6kir.tradelab.analysis.scoring.application.service.FundamentalScoringService;

@Configuration(proxyBeanMethods=false)
@ConditionalOnProperty(prefix="tradelab.telegram",name="enabled",havingValue="true")
public class ScoringTelegramConfiguration {
    @Bean ScoreCommand scoreCommand(FundamentalScoringService s,InstrumentService i) { return new ScoreCommand("score",s,i); }
    @Bean ScoreCommand scoreinfoCommand(FundamentalScoringService s,InstrumentService i) { return new ScoreCommand("scoreinfo",s,i); }
    @Bean ScoreCommand scorehistoryCommand(FundamentalScoringService s,InstrumentService i) { return new ScoreCommand("scorehistory",s,i); }
}
