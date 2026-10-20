package ru.ba6kir.tradelab.dividends;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import com.fasterxml.jackson.databind.ObjectMapper;
import ru.ba6kir.tradelab.market.application.InstrumentService;
import ru.ba6kir.tradelab.market.application.price.MarketPriceService;
import ru.ba6kir.tradelab.market.infrastructure.tinvest.*;
import ru.ba6kir.tradelab.dividends.infrastructure.tinvest.*;
import ru.ba6kir.tradelab.dividends.telegram.*;
import ru.ba6kir.tradelab.telegram.command.TelegramCommand;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class DividendConfigurationTest {
    final ApplicationContextRunner runner=new ApplicationContextRunner().withInitializer(new ConfigDataApplicationContextInitializer())
            .withUserConfiguration(Config.class).withBean(JdbcTemplate.class,() -> mock(JdbcTemplate.class))
            .withBean(PlatformTransactionManager.class,() -> mock(PlatformTransactionManager.class)).withBean(ObjectMapper.class,ObjectMapper::new)
            .withBean(InstrumentService.class,() -> mock(InstrumentService.class)).withBean(MarketPriceService.class,() -> mock(MarketPriceService.class))
            .withPropertyValues("INVEST_API_ENABLED=false","INVEST_API_TOKEN=");
    @Test void disabledApiStartsWithoutCredentialsAndFourCommandsExplainConfiguration() {
        runner.withUserConfiguration(DividendTelegramConfiguration.class).withPropertyValues("TELEGRAM_ENABLED=true").run(c -> {
            assertThat(c).hasNotFailed(); assertThat(c.getBeansOfType(TelegramCommand.class)).hasSize(4);
            c.getBeansOfType(TelegramCommand.class).values().forEach(command -> assertThat(command.execute(
                    command.name().equals("divcalendar")?List.of():command.name().equals("divhistory")?List.of("SBER","3y"):List.of("SBER"))).contains("не настроен"));
        });
    }
    @Test void enabledAdapterReusesSharedClientAndBoundsAreValidated() {
        runner.withPropertyValues("INVEST_API_ENABLED=true","INVEST_API_TOKEN=synthetic")
                .withBean(TInvestStockInstrumentProvider.class,() -> mock(TInvestStockInstrumentProvider.class)).run(c -> assertThat(c).hasNotFailed());
        for (String property:List.of("DIVIDENDS_LOOKBACK_YEARS=0","DIVIDENDS_LOOKBACK_YEARS=6","DIVIDENDS_FORWARD_DAYS=89",
                "DIVIDENDS_FORWARD_DAYS=367","DIVIDENDS_CACHE_TTL=0s","DIVIDENDS_CACHE_TTL=31d","DIVIDENDS_REFRESH_COOLDOWN=1s"))
            runner.withPropertyValues(property).run(c -> assertThat(c).hasFailed());
    }
    @Configuration(proxyBeanMethods=false)
    @EnableConfigurationProperties({InvestProperties.class,MarketDataProperties.class,DividendProperties.class})
    @Import(DividendConfiguration.class)
    static class Config {}
}
