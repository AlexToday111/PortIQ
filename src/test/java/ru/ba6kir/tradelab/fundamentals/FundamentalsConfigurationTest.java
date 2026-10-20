package ru.ba6kir.tradelab.fundamentals;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import com.fasterxml.jackson.databind.ObjectMapper;
import ru.ba6kir.tradelab.market.application.InstrumentService;
import ru.ba6kir.tradelab.market.infrastructure.tinvest.*;
import ru.ba6kir.tradelab.fundamentals.infrastructure.tinvest.*;
import ru.ba6kir.tradelab.fundamentals.telegram.*;
import ru.ba6kir.tradelab.telegram.command.TelegramCommand;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class FundamentalsConfigurationTest {
    private final ApplicationContextRunner runner=new ApplicationContextRunner().withInitializer(new ConfigDataApplicationContextInitializer())
            .withUserConfiguration(Config.class).withBean(JdbcTemplate.class,() -> mock(JdbcTemplate.class))
            .withBean(PlatformTransactionManager.class,() -> mock(PlatformTransactionManager.class)).withBean(ObjectMapper.class,ObjectMapper::new)
            .withBean(InstrumentService.class,() -> mock(InstrumentService.class)).withPropertyValues("INVEST_API_ENABLED=false","INVEST_API_TOKEN=");
    @Test void disabledIntegrationStartsAndAllCommandsExplainConfigurationWithoutCredentials() {
        runner.withUserConfiguration(FundamentalsTelegramConfiguration.class).withPropertyValues("TELEGRAM_ENABLED=true").run(c -> {
            assertThat(c).hasNotFailed().doesNotHaveBean(TInvestFundamentalsAdapter.class);
            assertThat(c.getBeansOfType(TelegramCommand.class)).hasSize(4);
            c.getBeansOfType(TelegramCommand.class).values().forEach(command -> assertThat(command.execute(java.util.List.of("SBER"))).contains("не настроен"));
        });
    }
    @Test void enabledUsesExistingClientAndInvalidThresholdsAreRejected() {
        runner.withPropertyValues("INVEST_API_ENABLED=true","INVEST_API_TOKEN=synthetic")
                .withBean(TInvestStockInstrumentProvider.class,() -> mock(TInvestStockInstrumentProvider.class)).run(c -> assertThat(c).hasNotFailed().hasSingleBean(TInvestFundamentalsAdapter.class));
        for (var property:java.util.List.of("FUNDAMENTALS_SNAPSHOT_TTL=0s","FUNDAMENTALS_REPORTING_MAX_AGE=1d",
                "FUNDAMENTALS_REFRESH_COOLDOWN=0s","FUNDAMENTALS_REPORT_WINDOW_DAYS=181")) runner.withPropertyValues(property).run(c -> assertThat(c).hasFailed());
    }
    @Configuration(proxyBeanMethods=false)
    @EnableConfigurationProperties({InvestProperties.class,FundamentalsProperties.class})
    @Import(FundamentalsConfiguration.class)
    static class Config { }
}
