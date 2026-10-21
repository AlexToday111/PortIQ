package ru.ba6kir.tradelab.portfolio.infrastructure.persistence;

import java.time.Clock;
import java.util.List;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import ru.ba6kir.tradelab.analysis.scoring.application.service.ScoringFingerprint;
import ru.ba6kir.tradelab.market.application.InstrumentService;
import ru.ba6kir.tradelab.market.application.price.MarketPriceService;
import ru.ba6kir.tradelab.market.infrastructure.tinvest.*;
import ru.ba6kir.tradelab.portfolio.application.port.*;
import ru.ba6kir.tradelab.portfolio.application.service.*;
import ru.ba6kir.tradelab.portfolio.infrastructure.tinvest.TInvestBrokerPortfolioProvider;

@Configuration(proxyBeanMethods=false)
public class PortfolioConfiguration {
    @Bean PortfolioStore portfolioStore(JdbcTemplate jdbc,ObjectMapper json,PlatformTransactionManager manager){return new JdbcPortfolioStore(jdbc,json,manager,Clock.systemUTC());}
    @Bean PortfolioTransactionService portfolioTransactionService(PortfolioStore store,InstrumentService instruments){return new PortfolioTransactionService(store,instruments,Clock.systemUTC());}
    @Bean PortfolioValuationService portfolioValuationService(PortfolioStore store,InstrumentService instruments,MarketPriceService prices,PortfolioProperties properties){return new PortfolioValuationService(store,instruments,prices,Clock.systemUTC(),properties.quoteMaxAge());}
    @Bean PortfolioService portfolioService(PortfolioStore store,PortfolioValuationService valuation,InstrumentService instruments){return new PortfolioService(store,valuation,instruments,Clock.systemUTC());}
    @Bean @ConditionalOnProperty(prefix="tradelab.portfolio",name="broker-sync-enabled",havingValue="true")
    BrokerPortfolioProvider brokerPortfolioProvider(TInvestStockInstrumentProvider transport){return new TInvestBrokerPortfolioProvider(transport,Clock.systemUTC());}
    @Bean @ConditionalOnProperty(prefix="tradelab.portfolio",name="broker-sync-enabled",havingValue="false",matchIfMissing=true)
    BrokerPortfolioProvider disabledBrokerPortfolioProvider(){return new BrokerPortfolioProvider(){
        public List<BrokerAccount> accounts(){throw new IllegalArgumentException("BROKER_SYNC_DISABLED");}public Snapshot snapshot(String id){throw new IllegalArgumentException("BROKER_SYNC_DISABLED");}
        public Page operations(String id,java.time.Instant from,java.time.Instant to,String cursor,int limit){throw new IllegalArgumentException("BROKER_SYNC_DISABLED");}
    };}
    @Bean PortfolioSynchronizationService portfolioSynchronizationService(PortfolioStore store,BrokerPortfolioProvider broker,ScoringFingerprint hashes,PortfolioProperties properties){
        return new PortfolioSynchronizationService(store,broker,hashes,Clock.systemUTC(),properties.brokerSyncEnabled(),properties.syncCooldown(),properties.maxPages());
    }
}
