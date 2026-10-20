package ru.ba6kir.tradelab.market;

import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import ru.ba6kir.tradelab.market.application.*;
import ru.ba6kir.tradelab.market.infrastructure.tinvest.*;
import ru.ba6kir.tradelab.market.telegram.MarketTelegramConfiguration;
import ru.ba6kir.tradelab.telegram.command.TelegramCommand;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class MarketConfigurationTest {
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withInitializer(new ConfigDataApplicationContextInitializer())
            .withUserConfiguration(Config.class)
            .withBean(JdbcTemplate.class, () -> mock(JdbcTemplate.class))
            .withBean(org.springframework.transaction.PlatformTransactionManager.class,
                    () -> mock(org.springframework.transaction.PlatformTransactionManager.class))
            .withPropertyValues("INVEST_API_ENABLED=false", "INVEST_API_TOKEN=");

    @Test void disabledStartsWithNoTokenAndNoSdkTransport() {
        runner.run(c -> {
            assertThat(c).hasNotFailed().hasSingleBean(InstrumentService.class).hasSingleBean(StockInstrumentProvider.class)
                    .doesNotHaveBean(TInvestStockInstrumentProvider.class).doesNotHaveBean(TInvestMarketPriceProvider.class);
            assertThat(c.getBean(InvestProperties.class).enabled()).isFalse();
            assertThatThrownBy(() -> c.getBean(StockInstrumentProvider.class).findShares("SBER"))
                    .isInstanceOfSatisfying(MarketException.class, e -> assertThat(e.reason()).isEqualTo(MarketException.Reason.DISABLED));
        });
    }

    @Test void enabledRequiresNonblankTokenAndNeverDisclosesRejectedSecret() {
        for (String token : new String[]{"", " ", "synthetic secret must not leak"}) {
            runner.withPropertyValues("INVEST_API_ENABLED=true", "INVEST_API_TOKEN=" + token).run(c -> {
                assertThat(c).hasFailed();
                assertThat(c.getStartupFailure()).hasStackTraceContaining("INVEST_API_TOKEN must be")
                        .hasStackTraceContaining("tradelab.invest");
                if (!token.isBlank()) assertThat(c.getStartupFailure()).hasStackTraceContaining("INVEST_API_TOKEN")
                        .hasStackTraceContaining("without whitespace");
                assertThat(stack(c.getStartupFailure())).doesNotContain("synthetic secret must not leak");
            });
        }
    }

    @Test void createsProductionSdkClientWithoutMakingRemoteCalls() {
        runner.withPropertyValues("INVEST_API_ENABLED=true", "INVEST_API_TOKEN=synthetic-test-token").run(c -> {
            assertThat(c).hasNotFailed().hasSingleBean(TInvestStockInstrumentProvider.class);
            assertThat(c.getBean(ru.ba6kir.tradelab.market.application.price.MarketPriceProvider.class))
                    .isInstanceOf(TInvestMarketPriceProvider.class);
            assertThat(c.getBean(InvestProperties.class).toString()).contains("<redacted>").doesNotContain("synthetic-test-token");
        });
    }

    @Test void rejectsUnboundedCacheAndRetrySettings() {
        for (String property : new String[]{"INVEST_METADATA_TTL=0s", "INVEST_API_TIMEOUT=31s",
                "INVEST_API_MAX_ATTEMPTS=4", "INVEST_API_RETRY_BACKOFF=3s", "INVEST_HISTORY_DAYS=366",
                "INVEST_HISTORY_DAYS=0", "INVEST_REFRESH_COOLDOWN=0s", "INVEST_TRACKED_TICKERS=SBER,bad/ticker"}) {
            runner.withPropertyValues(property).run(c -> assertThat(c).hasFailed());
        }
    }

    @Test void marketCommandsRegisterEvenWhenInvestIsDisabledAndDisappearWhenTelegramIsDisabled() {
        runner.withUserConfiguration(MarketTelegramConfiguration.class)
                .withPropertyValues("tradelab.telegram.enabled=true").run(c -> {
                    assertThat(c).hasNotFailed();
                    var commands = c.getBeansOfType(TelegramCommand.class).values();
                    assertThat(commands).extracting(TelegramCommand::name).containsExactlyInAnyOrder("stock", "search", "price", "history", "refresh");
                    assertThat(commands).allSatisfy(command -> assertThat(command.execute(command.name().equals("history")
                                    ? java.util.List.of("SBER", "30d") : java.util.List.of("SBER")))
                            .contains("не настроен"));
                });
        runner.withUserConfiguration(MarketTelegramConfiguration.class)
                .withPropertyValues("tradelab.telegram.enabled=false").run(c -> assertThat(c).doesNotHaveBean(TelegramCommand.class));
    }

    private static String stack(Throwable t) {
        var writer = new java.io.StringWriter();
        t.printStackTrace(new java.io.PrintWriter(writer));
        return writer.toString();
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties({InvestProperties.class, MarketDataProperties.class})
    @Import(MarketConfiguration.class)
    static class Config { }
}
