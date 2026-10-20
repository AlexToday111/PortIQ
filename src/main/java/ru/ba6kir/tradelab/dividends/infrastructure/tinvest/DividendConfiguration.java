package ru.ba6kir.tradelab.dividends.infrastructure.tinvest;

import java.time.Clock;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import ru.ba6kir.tradelab.market.application.InstrumentService;
import ru.ba6kir.tradelab.market.application.MarketException;
import ru.ba6kir.tradelab.market.application.price.MarketPriceService;
import ru.ba6kir.tradelab.market.infrastructure.tinvest.*;
import ru.ba6kir.tradelab.dividends.application.port.*;
import ru.ba6kir.tradelab.dividends.application.service.DividendService;
import ru.ba6kir.tradelab.dividends.infrastructure.persistence.JdbcDividendStore;

@Configuration(proxyBeanMethods=false)
public class DividendConfiguration {
    @Bean DividendStore dividendStore(JdbcTemplate jdbc,PlatformTransactionManager manager,ObjectMapper json) {
        return new JdbcDividendStore(jdbc,manager,json);
    }
    @Bean @ConditionalOnProperty(prefix="tradelab.invest",name="enabled",havingValue="true")
    DividendProvider dividendProvider(TInvestStockInstrumentProvider provider) { return new TInvestDividendAdapter(provider,Clock.systemUTC()); }
    @Bean @ConditionalOnProperty(prefix="tradelab.invest",name="enabled",havingValue="false",matchIfMissing=true)
    DividendProvider disabledDividendProvider() { return (uid,from,to) -> { throw new MarketException(MarketException.Reason.DISABLED); }; }
    @Bean DividendService dividendService(InstrumentService instruments,MarketPriceService prices,DividendProvider provider,DividendStore store,
            DividendProperties properties,MarketDataProperties market,InvestProperties invest) {
        return new DividendService(instruments,prices,provider,store,Clock.systemUTC(),properties.cacheTtl(),properties.refreshCooldown(),
                properties.lookbackYears(),properties.forwardDays(),market.trackedTickers(),invest.enabled());
    }
}
