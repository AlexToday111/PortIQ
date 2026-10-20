package ru.ba6kir.tradelab.market.infrastructure.tinvest;

import java.time.Clock;
import java.util.Properties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import ru.ba6kir.tradelab.market.application.price.*;
import ru.ba6kir.tradelab.market.infrastructure.persistence.JdbcMarketPriceStore;
import ru.ba6kir.tradelab.market.application.*;
import ru.ba6kir.tradelab.market.infrastructure.persistence.JdbcInstrumentStore;
import ru.ttech.piapi.core.connector.ConnectorConfiguration;
import ru.ttech.piapi.core.connector.ServiceStubFactory;
import ru.tinkoff.piapi.contract.v1.InstrumentsServiceGrpc;

@Configuration(proxyBeanMethods = false)
public class MarketConfiguration {
    @Bean
    MarketPriceStore marketPriceStore(JdbcTemplate jdbc, PlatformTransactionManager manager) {
        return new JdbcMarketPriceStore(jdbc, manager, TInvestMarketPriceProvider.SOURCE);
    }

    @Bean
    @ConditionalOnProperty(prefix="tradelab.invest", name="enabled", havingValue="true")
    MarketPriceProvider marketPriceProvider(TInvestStockInstrumentProvider instruments, InvestProperties properties) {
        return new TInvestMarketPriceProvider(instruments.marketDataStub(), instruments.rpc(), properties, Clock.systemUTC());
    }

    @Bean
    @ConditionalOnProperty(prefix="tradelab.invest", name="enabled", havingValue="false", matchIfMissing=true)
    MarketPriceProvider disabledMarketPriceProvider() {
        return new MarketPriceProvider() {
            public java.util.Optional<ru.ba6kir.tradelab.market.domain.price.MarketQuote> getLastPrice(
                    ru.ba6kir.tradelab.market.domain.StockInstrument instrument) {
                throw new MarketException(MarketException.Reason.DISABLED);
            }
            public java.util.List<ru.ba6kir.tradelab.market.domain.price.DailyCandle> getDailyCandles(
                    ru.ba6kir.tradelab.market.domain.StockInstrument instrument, java.time.Instant from, java.time.Instant to) {
                throw new MarketException(MarketException.Reason.DISABLED);
            }
        };
    }

    @Bean
    MarketPriceService marketPriceService(InstrumentService instruments, MarketPriceProvider provider, MarketPriceStore store,
                                         InvestProperties invest, MarketDataProperties data) {
        return new MarketPriceService(instruments, provider, store, Clock.systemUTC(), data.refreshCooldown(),
                data.historyDays(), data.trackedTickers(), invest.enabled());
    }
    @Bean
    InstrumentStore instrumentStore(JdbcTemplate jdbc) { return new JdbcInstrumentStore(jdbc); }

    @Bean(destroyMethod = "close")
    @ConditionalOnProperty(prefix = "tradelab.invest", name = "enabled", havingValue = "true")
    TInvestStockInstrumentProvider stockInstrumentProvider(InvestProperties properties) {
        var sdk = new Properties();
        sdk.setProperty("token", properties.token());
        sdk.setProperty("app.name", "ba6kir.tradelab-portfolio");
        sdk.setProperty("target", "invest-public-api.tbank.ru:443");
        sdk.setProperty("sandbox.enabled", "false");
        sdk.setProperty("connection.timeout", Long.toString(properties.timeout().toMillis()));
        sdk.setProperty("grpc.debug", "false");
        var factory = ServiceStubFactory.create(ConnectorConfiguration.loadFromProperties(sdk));
        try {
            var stub = factory.newSyncService(InstrumentsServiceGrpc::newBlockingStub).getStub();
            return new TInvestStockInstrumentProvider(stub, factory.getChannel(), properties, Clock.systemUTC());
        } catch (RuntimeException ignored) {
            factory.getChannel().shutdownNow();
            throw new IllegalStateException("Cannot initialize T-Invest metadata client");
        }
    }

    @Bean
    @ConditionalOnProperty(prefix = "tradelab.invest", name = "enabled", havingValue = "false", matchIfMissing = true)
    StockInstrumentProvider disabledStockInstrumentProvider() {
        return new StockInstrumentProvider() {
            public java.util.List<ru.ba6kir.tradelab.market.domain.StockInstrument> findShares(String query) {
                throw new MarketException(MarketException.Reason.DISABLED);
            }
            public java.util.Optional<ru.ba6kir.tradelab.market.domain.StockInstrument> findByUid(String uid) {
                throw new MarketException(MarketException.Reason.DISABLED);
            }
        };
    }

    @Bean
    InstrumentService instrumentService(StockInstrumentProvider provider, InstrumentStore store, InvestProperties properties) {
        return new InstrumentService(provider, store, Clock.systemUTC(), properties.metadataTtl(), properties.enabled());
    }
}
