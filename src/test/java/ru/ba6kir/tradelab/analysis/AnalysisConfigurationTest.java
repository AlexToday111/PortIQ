package ru.ba6kir.tradelab.analysis;

import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import com.fasterxml.jackson.databind.ObjectMapper;
import ru.ba6kir.tradelab.analysis.infrastructure.configuration.*;
import ru.ba6kir.tradelab.analysis.domain.metric.AnalysisMetric;
import ru.ba6kir.tradelab.analysis.domain.sector.SectorClassification.*;
import ru.ba6kir.tradelab.analysis.telegram.AnalysisTelegramConfiguration;
import ru.ba6kir.tradelab.fundamentals.application.service.FundamentalsService;
import ru.ba6kir.tradelab.fundamentals.infrastructure.tinvest.FundamentalsProperties;
import ru.ba6kir.tradelab.dividends.application.service.DividendService;
import ru.ba6kir.tradelab.market.application.MarketException;
import ru.ba6kir.tradelab.telegram.command.TelegramCommand;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static ru.ba6kir.tradelab.analysis.AnalysisTestData.*;

class AnalysisConfigurationTest {
    final ApplicationContextRunner runner=new ApplicationContextRunner().withInitializer(new ConfigDataApplicationContextInitializer())
            .withUserConfiguration(Config.class).withBean(JdbcTemplate.class,() -> mock(JdbcTemplate.class))
            .withBean(PlatformTransactionManager.class,() -> mock(PlatformTransactionManager.class)).withBean(ObjectMapper.class,ObjectMapper::new)
            .withBean(DividendService.class,() -> mock(DividendService.class)).withBean(FundamentalsService.class,() -> {
                var f=mock(FundamentalsService.class); when(f.getLatest(anyString())).thenThrow(new MarketException(MarketException.Reason.DISABLED)); return f; });
    @Test void disabledIntegrationRegistersSafeCommandsWithoutCredentials() {
        runner.withUserConfiguration(AnalysisTelegramConfiguration.class).withPropertyValues("TELEGRAM_ENABLED=true").run(c -> {
            assertThat(c).hasNotFailed(); assertThat(c.getBeansOfType(TelegramCommand.class)).hasSize(3);
            c.getBeansOfType(TelegramCommand.class).values().forEach(command -> assertThat(command.execute(List.of("TEST"))).contains("не настроен"));
        });
    }
    @Test void sampleMinimumAndClassificationAgeAreBoundedAndSeedsAreExplicitVerifiedAssets() {
        for (String property:List.of("ANALYSIS_MINIMUM_PEERS=4","ANALYSIS_MINIMUM_PEERS=101","ANALYSIS_CLASSIFICATION_MAX_AGE=0d"))
            runner.withPropertyValues(property).run(c -> assertThat(c).hasFailed());
        assertThatThrownBy(() -> new AnalysisProperties.Seed("SBER","issuer",Sector.FINANCIALS,BusinessType.BANK,NOW,false,"test")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AnalysisProperties.Seed(ASSET,"issuer",Sector.FINANCIALS,BusinessType.NON_FINANCIAL,NOW,false,"test")).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void bankProfilesRejectIndustrialLeverageAndProfilesCannotBeOneMetric() {
        assertThatThrownBy(() -> new AnalysisProperties.Profile(Sector.FINANCIALS,BusinessType.BANK,Set.of(AnalysisMetric.PE,AnalysisMetric.NET_DEBT_EBITDA),Set.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AnalysisProperties.Profile(Sector.OIL_AND_GAS,BusinessType.NON_FINANCIAL,Set.of(AnalysisMetric.PE),Set.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }
    @Configuration(proxyBeanMethods=false)
    @EnableConfigurationProperties({AnalysisProperties.class,FundamentalsProperties.class})
    @Import(AnalysisConfiguration.class)
    static class Config {}
}
