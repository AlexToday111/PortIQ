package ru.ba6kir.tradelab.portfolio.telegram;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.*;
import ru.ba6kir.tradelab.analysis.scoring.application.service.ScoringFingerprint;
import ru.ba6kir.tradelab.portfolio.application.service.*;

@Configuration(proxyBeanMethods=false)
@ConditionalOnProperty(prefix="tradelab.telegram",name="enabled",havingValue="true")
public class PortfolioTelegramConfiguration {
    @Bean PortfolioCommand portfolioCommand(PortfolioService p,PortfolioTransactionService t,PortfolioSynchronizationService s,ScoringFingerprint h){return new PortfolioCommand("portfolio",p,t,s,h);}
    @Bean PortfolioCommand positionsCommand(PortfolioService p,PortfolioTransactionService t,PortfolioSynchronizationService s,ScoringFingerprint h){return new PortfolioCommand("positions",p,t,s,h);}
    @Bean PortfolioCommand positionCommand(PortfolioService p,PortfolioTransactionService t,PortfolioSynchronizationService s,ScoringFingerprint h){return new PortfolioCommand("position",p,t,s,h);}
    @Bean PortfolioCommand transactionsCommand(PortfolioService p,PortfolioTransactionService t,PortfolioSynchronizationService s,ScoringFingerprint h){return new PortfolioCommand("transactions",p,t,s,h);}
    @Bean PortfolioCommand tradeCommand(PortfolioService p,PortfolioTransactionService t,PortfolioSynchronizationService s,ScoringFingerprint h){return new PortfolioCommand("trade",p,t,s,h);}
    @Bean PortfolioCommand cashCommand(PortfolioService p,PortfolioTransactionService t,PortfolioSynchronizationService s,ScoringFingerprint h){return new PortfolioCommand("cash",p,t,s,h);}
    @Bean PortfolioCommand incomeCommand(PortfolioService p,PortfolioTransactionService t,PortfolioSynchronizationService s,ScoringFingerprint h){return new PortfolioCommand("income",p,t,s,h);}
    @Bean PortfolioCommand openingCommand(PortfolioService p,PortfolioTransactionService t,PortfolioSynchronizationService s,ScoringFingerprint h){return new PortfolioCommand("opening",p,t,s,h);}
    @Bean PortfolioCommand reverseCommand(PortfolioService p,PortfolioTransactionService t,PortfolioSynchronizationService s,ScoringFingerprint h){return new PortfolioCommand("reverse",p,t,s,h);}
    @Bean PortfolioCommand confirmCommand(PortfolioService p,PortfolioTransactionService t,PortfolioSynchronizationService s,ScoringFingerprint h){return new PortfolioCommand("confirm",p,t,s,h);}
    @Bean PortfolioCommand accountsCommand(PortfolioService p,PortfolioTransactionService t,PortfolioSynchronizationService s,ScoringFingerprint h){return new PortfolioCommand("accounts",p,t,s,h);}
    @Bean PortfolioCommand syncCommand(PortfolioService p,PortfolioTransactionService t,PortfolioSynchronizationService s,ScoringFingerprint h){return new PortfolioCommand("sync",p,t,s,h);}
    @Bean PortfolioCommand reconcileCommand(PortfolioService p,PortfolioTransactionService t,PortfolioSynchronizationService s,ScoringFingerprint h){return new PortfolioCommand("reconcile",p,t,s,h);}
    @Bean PortfolioCommand portstatusCommand(PortfolioService p,PortfolioTransactionService t,PortfolioSynchronizationService s,ScoringFingerprint h){return new PortfolioCommand("portstatus",p,t,s,h);}
}
