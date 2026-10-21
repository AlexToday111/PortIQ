package ru.ba6kir.tradelab.research;

import java.time.Duration;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import ru.ba6kir.tradelab.analysis.ranking.application.RankingUniverseService;
import ru.ba6kir.tradelab.analysis.ranking.persistence.RankingProperties;
import ru.ba6kir.tradelab.market.application.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static ru.ba6kir.tradelab.research.ResearchTestData.*;

class RankingConfigurationUniverseTest {
    final ApplicationContextRunner runner=new ApplicationContextRunner().withInitializer(new ConfigDataApplicationContextInitializer()).withUserConfiguration(Config.class);
    @Test void defaultRankingConfigurationIsBoundedAndHasNoFabricatedUniverse() {
        runner.run(c -> {assertThat(c).hasNotFailed();var properties=c.getBean(RankingProperties.class);assertThat(properties.instrumentUids()).isEmpty();assertThat(properties.policy(Duration.ofDays(365)).quoteMaxAge()).isEqualTo(Duration.ofDays(3));});
        for(var property:List.of("RANKING_SNAPSHOT_TTL=0s","RANKING_REFRESH_COOLDOWN=1s","RANKING_QUOTE_MAX_AGE=8d","RANKING_INSTRUMENT_UIDS=fake","RANKING_POLICY_VERSION="))
            runner.withPropertyValues(property).run(c -> assertThat(c).hasFailed());
    }
    @Test void universeUsesRealResolutionKeepsAmbiguousAndMissingInstrumentsAndUidOverrideAvoidsTickerLookup() {
        var instruments=mock(InstrumentService.class);when(instruments.resolve("SYNTH1")).thenReturn(instrument(1));
        when(instruments.resolve("DUAL")).thenThrow(new AmbiguousInstrumentException(List.of(instrument(1),instrument(2))));when(instruments.resolve("GONE")).thenThrow(new MarketException(MarketException.Reason.NOT_FOUND));
        var universe=new RankingUniverseService(instruments,List.of("SYNTH1","DUAL","GONE"),List.of());
        assertThat(universe.identifiers()).containsExactly("ticker:DUAL","ticker:GONE","ticker:SYNTH1");
        assertThat(universe.resolve()).extracting(m -> m.resolutionReason()).containsExactly("AMBIGUOUS_TICKER_USE_UID","INSTRUMENT_NOT_FOUND",null);
        clearInvocations(instruments);when(instruments.getCached(uid(1))).thenReturn(Optional.of(instrument(1)));
        assertThat(new RankingUniverseService(instruments,List.of("SYNTH1"),List.of(uid(1))).resolve().getFirst().instrumentUid()).isEqualTo(uid(1));verify(instruments,never()).resolve(any());
    }
    @Test void providerOutageFailsRefreshResolutionInsteadOfReplacingPreviousWithAllExcluded() {
        var instruments=mock(InstrumentService.class);when(instruments.resolve("SYNTH1")).thenThrow(new MarketException(MarketException.Reason.DISABLED));
        assertThatThrownBy(() -> new RankingUniverseService(instruments,List.of("SYNTH1"),List.of()).resolve()).isInstanceOf(MarketException.class);
    }
    @Configuration(proxyBeanMethods=false) @EnableConfigurationProperties(RankingProperties.class) static class Config {}
}
