package ru.ba6kir.tradelab.fundamentals.infrastructure.tinvest;

import java.time.*;
import java.util.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import ru.ba6kir.tradelab.market.application.*;
import ru.ba6kir.tradelab.market.infrastructure.tinvest.*;
import ru.ba6kir.tradelab.fundamentals.application.port.*;
import ru.ba6kir.tradelab.fundamentals.application.service.*;
import ru.ba6kir.tradelab.fundamentals.domain.model.*;
import ru.ba6kir.tradelab.fundamentals.domain.validation.FundamentalNormalizer;
import ru.ba6kir.tradelab.fundamentals.infrastructure.persistence.JdbcFundamentalsStore;

@Configuration(proxyBeanMethods=false)
public class FundamentalsConfiguration {
    @Bean FundamentalsStore fundamentalsStore(JdbcTemplate jdbc,PlatformTransactionManager manager,ObjectMapper json) {
        return new JdbcFundamentalsStore(jdbc,manager,json);
    }
    @Bean @ConditionalOnProperty(prefix="tradelab.invest",name="enabled",havingValue="true")
    TInvestFundamentalsAdapter fundamentalsAdapter(TInvestStockInstrumentProvider provider) {
        return new TInvestFundamentalsAdapter(provider,Clock.systemUTC());
    }
    @Bean @ConditionalOnProperty(prefix="tradelab.invest",name="enabled",havingValue="false",matchIfMissing=true)
    AssetIdentityResolver disabledAssetResolver() { return uid -> { throw new MarketException(MarketException.Reason.DISABLED); }; }
    @Bean @ConditionalOnProperty(prefix="tradelab.invest",name="enabled",havingValue="false",matchIfMissing=true)
    FundamentalsProvider disabledFundamentalsProvider() {
        return new FundamentalsProvider() {
            public Optional<FundamentalData> fetch(String uid) { throw new MarketException(MarketException.Reason.DISABLED); }
            public List<FinancialReportEvent> fetchReports(String uid,Instant from,Instant to) { throw new MarketException(MarketException.Reason.DISABLED); }
        };
    }
    @Bean FundamentalsService fundamentalsService(InstrumentService instruments,AssetIdentityResolver resolver,FundamentalsProvider provider,
            FundamentalsStore store,FundamentalsProperties properties,InvestProperties invest) {
        return new FundamentalsService(instruments,resolver,provider,store,new FundamentalNormalizer(),Clock.systemUTC(),properties.snapshotTtl(),
                properties.reportingMaxAge(),properties.refreshCooldown(),properties.reportWindowDays(),invest.enabled());
    }
}
