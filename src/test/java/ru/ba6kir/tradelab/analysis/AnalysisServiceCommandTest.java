package ru.ba6kir.tradelab.analysis;

import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import ru.ba6kir.tradelab.analysis.application.port.AnalysisStore;
import ru.ba6kir.tradelab.analysis.application.service.*;
import ru.ba6kir.tradelab.analysis.domain.metric.*;
import ru.ba6kir.tradelab.analysis.domain.sector.*;
import ru.ba6kir.tradelab.analysis.telegram.AnalysisCommand;
import ru.ba6kir.tradelab.fundamentals.application.service.FundamentalsService;
import ru.ba6kir.tradelab.dividends.application.service.DividendService;
import ru.ba6kir.tradelab.market.application.MarketException;
import ru.ba6kir.tradelab.telegram.adapter.TelegramMessageSender;
import ru.ba6kir.tradelab.telegram.command.*;
import ru.ba6kir.tradelab.telegram.dispatcher.TelegramCommandDispatcher;
import ru.ba6kir.tradelab.telegram.security.TelegramAccessGuard;
import ru.ba6kir.tradelab.telegram.service.TelegramBotState;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static ru.ba6kir.tradelab.analysis.AnalysisTestData.*;
import static ru.ba6kir.tradelab.telegram.TelegramTestData.*;

class AnalysisServiceCommandTest {
    final AnalysisStore store=mock(AnalysisStore.class);
    final FundamentalsService fundamentals=mock(FundamentalsService.class);
    final DividendService dividends=mock(DividendService.class);
    final SectorClassificationService sectors=new SectorClassificationService(store,List.of(classification(0)),CLOCK,Duration.ofDays(365));
    final FinancialMetricService service=new FinancialMetricService(fundamentals,dividends,sectors,store,CLOCK,Map.of());
    final SectorComparisonService comparison=new SectorComparisonService(service,fundamentals,sectors,CLOCK,Duration.ofDays(1),Duration.ofDays(548),5);
    void setup() {
        when(fundamentals.getLatest("TEST")).thenReturn(input());
        when(dividends.get(UID,1)).thenThrow(new MarketException(MarketException.Reason.UNAVAILABLE));
        when(fundamentals.cachedAsset(ASSET)).thenReturn(Optional.of(snapshot(1L)));
    }
    @Test void sectorAndMetricsRetainIdentityRawLimitationsProviderAndDerivedVariants() {
        setup(); var view=service.get("TEST");
        assertThat(view.sector().classification().assetUid()).isEqualTo(ASSET);
        assertThat(view.observations()).anyMatch(o -> o.metricCode()==AnalysisMetric.EARNINGS_YIELD && o.value()!=null && o.value().toPlainString().startsWith("20"));
        assertThat(view.readiness()).isEqualTo(MetricProfile.Readiness.INSUFFICIENT_DATA);
        assertThat(view.warnings()).contains("DIVIDENDS_UNAVAILABLE"); verify(store).saveDerived(argThat(rows -> rows.size()==7));
        assertThat(new AnalysisCommand("sector",service,comparison).execute(List.of("TEST"))).contains("OIL_AND_GAS","NON_FINANCIAL",ASSET,"MANUAL_VERIFIED");
        assertThat(new AnalysisCommand("metrics",service,comparison).execute(List.of("TEST"))).contains("EARNINGS_YIELD [DERIVED] = 20","UNVERIFIED_RATE","METRIC_REPORTING_DATES_UNKNOWN");
    }
    @Test void comparisonUsesCachedPeerApplicationContractWithoutFetchingPeerProviders() {
        setup(); var view=comparison.compare("TEST");
        assertThat(view.comparisons()).isNotEmpty().allMatch(r -> r.relativePositionPercent()==null);
        verify(fundamentals).cachedAsset(ASSET); verify(fundamentals,times(1)).getLatest(anyString());
        assertThat(new AnalysisCommand("compare",service,comparison).execute(List.of("TEST"))).contains("MISSING_INVALID_OR_UNDATED_SUBJECT").doesNotContain("BUY","SELL","HOLD");
    }
    @Test void allThreeCommandsValidateArgumentsAndDiscardUnexpectedErrorDetails() {
        for (String name:List.of("sector","metrics","compare")) assertThat(new AnalysisCommand(name,service,comparison).execute(List.of())).contains("Используйте");
        verifyNoInteractions(fundamentals,dividends);
        when(fundamentals.getLatest("TEST")).thenThrow(new IllegalStateException("password=secret internalhost"));
        assertThat(new AnalysisCommand("metrics",service,comparison).execute(List.of("TEST"))).contains("INTERNAL").doesNotContain("secret","internalhost");
    }
    @Test void commonAuthorizationProtectsEveryNewCommandAndHelpDiscoversThem() {
        var commands=List.<TelegramCommand>of(new AnalysisCommand("sector",service,comparison),new AnalysisCommand("metrics",service,comparison),new AnalysisCommand("compare",service,comparison));
        var sender=mock(TelegramMessageSender.class); var dispatcher=new TelegramCommandDispatcher(new TelegramAccessGuard(properties()),sender,new TelegramBotState(),commands);
        for (var command:commands) {
            var outsider=update("/"+command.name()+" TEST"); outsider.getMessage().getFrom().setId(123L); dispatcher.dispatch(outsider);
            var group=update("/"+command.name()+" TEST"); group.getMessage().getChat().setType("group"); dispatcher.dispatch(group);
        }
        verifyNoInteractions(fundamentals,dividends,sender);
        assertThat(new HelpCommand(() -> commands).execute(List.of())).contains("/sector","/metrics","/compare");
    }
}
