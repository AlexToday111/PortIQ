package ru.ba6kir.tradelab.research;

import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.mockito.ArgumentCaptor;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.generics.TelegramClient;
import ru.ba6kir.tradelab.analysis.domain.metric.AnalysisMetric;
import ru.ba6kir.tradelab.analysis.ranking.application.*;
import ru.ba6kir.tradelab.analysis.ranking.domain.*;
import ru.ba6kir.tradelab.analysis.reporting.application.*;
import ru.ba6kir.tradelab.analysis.reporting.domain.AnalysisExplanation;
import ru.ba6kir.tradelab.analysis.reporting.presentation.*;
import ru.ba6kir.tradelab.analysis.telegram.StockResearchCommand;
import ru.ba6kir.tradelab.market.application.*;
import ru.ba6kir.tradelab.telegram.adapter.TelegramMessageSender;
import ru.ba6kir.tradelab.telegram.command.*;
import ru.ba6kir.tradelab.telegram.dispatcher.TelegramCommandDispatcher;
import ru.ba6kir.tradelab.telegram.security.TelegramAccessGuard;
import ru.ba6kir.tradelab.telegram.service.TelegramBotState;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static ru.ba6kir.tradelab.research.ResearchTestData.*;
import static ru.ba6kir.tradelab.telegram.TelegramTestData.*;

class ResearchReportCommandTest {
    InstrumentService instruments;AnalysisSnapshotService snapshots;StockAnalysisService analysis;StockRankingService rankings;
    @BeforeEach void setup() {
        instruments=mock(InstrumentService.class);snapshots=mock(AnalysisSnapshotService.class);rankings=mock(StockRankingService.class);
        analysis=new StockAnalysisService(instruments,snapshots,new AnalysisExplanationService(),new RankingEligibilityService(model(),policy(),HASHES),CLOCK);
        when(snapshots.capture(anyList(),eq(NOW))).thenReturn(List.of(snapshot(1,"OIL_AND_GAS")));
    }
    StockResearchCommand command(String name) {return new StockResearchCommand(name,analysis,rankings);}
    @Test void comprehensiveReportUsesFrozenScoreAndSeparatelyDatedMarketDividendDataInRussian() {
        var text=command("analyze").execute(List.of(uid(1)));
        assertThat(text).contains("Анализ компании","Нефть и газ","Нефинансовый бизнес","Последняя сделка: 100 RUB","не исполнимая котировка","79,975","Качество бизнеса: 81",
                "Финансовая устойчивость: 83,5","Оценка стоимости: 78","Дивиденды: 75","synthetic-model-1","Europe/Moscow", "прогнозная способность не подтверждена");
        assertThat(text).contains("Недавние зафиксированные события","Объявленные будущие события","score:1","Период:","Источник/ID:","разным периодам");
        verifyNoInteractions(instruments,rankings);
    }
    @Test void bankReportDoesNotPresentIndustrialLeverageAsBadOrRelevant() {
        when(snapshots.capture(anyList(),any())).thenReturn(List.of(snapshot(1,"BANK")));
        assertThat(command("analyze").execute(List.of(uid(1)))).contains("Банк","CAPITAL_ADEQUACY","ASSET_QUALITY").doesNotContain("NET_DEBT_EBITDA","EV_EBITDA","CURRENT_RATIO");
    }
    @Test void missingScoreAndMissingPriceProduceExplicitMarkersWithoutNewScore() {
        var s=withQuote(withScore(snapshot(1,"BANK"),null,null),null);when(snapshots.capture(anyList(),any())).thenReturn(List.of(s));
        assertThat(command("analyze").execute(List.of(uid(1)))).contains("Цена: нет данных","Нет сохранённой оценки","NO_STORED_SCORE","Нет сохранённых вкладов");
    }
    @Test void explanationsTraceEachRuleToExactObservationPeriodAndThresholdIncludingMissingInputs() {
        var s=snapshot(1,replace(input(1,"OIL_AND_GAS"),AnalysisMetric.CURRENT_RATIO,null),model());
        var explained=new AnalysisExplanationService().explain(s.score(),s.model());assertThat(explained).hasSize(8);
        var missing=explained.stream().filter(e -> e.metric()==AnalysisMetric.CURRENT_RATIO).findFirst().orElseThrow();
        assertThat(missing.direction()).isEqualTo(AnalysisExplanation.Direction.UNAVAILABLE);assertThat(missing.observedValue()).isNull();assertThat(missing.ruleId()).contains("CURRENT_RATIO");
        assertThat(missing.thresholdReference()).contains("→");
        var roe=explained.stream().filter(e -> e.metric()==AnalysisMetric.ROIC).findFirst().orElseThrow();
        assertThat(roe.observationId()).isEqualTo("synthetic:1:ROIC");assertThat(roe.periodEnd()).isEqualTo(END);assertThat(roe.sourceReference()).contains("SYNTHETIC","snapshot=1");
        when(snapshots.capture(anyList(),any())).thenReturn(List.of(s));
        assertThat(command("why").execute(List.of(uid(1)))).contains("Недоступная метрика","MISSING_CURRENT_RATIO","Пороговая кривая","synthetic:1:ROIC","вклад в столп");
    }
    @Test void whyDoesNotInventSectorMedianAndDistressCapRetainsEvidence() {
        var s=snapshot(1,replace(input(1,"OIL_AND_GAS"),AnalysisMetric.NET_INCOME,row(1,AnalysisMetric.NET_INCOME,"-2")),model());
        when(snapshots.capture(anyList(),any())).thenReturn(List.of(s));
        assertThat(command("why").execute(List.of(uid(1)))).contains("CONTEXT_CAP","NEGATIVE_NET_INCOME","synthetic:1:NET_INCOME","не сравнение с медианой отрасли");
    }
    @Test void sectorFiltersAreDocumentedAndUnknownFilterDoesNotReadData() {
        assertThat(RankingFormatter.profileFilter("banks")).isEqualTo("BANK");assertThat(RankingFormatter.profileFilter("oil")).isEqualTo("OIL_AND_GAS");assertThat(RankingFormatter.profileFilter("metals")).isEqualTo("METALS_AND_MINING");
        assertThat(command("top").execute(List.of("unknown"))).contains("Неизвестный фильтр","/top banks");verifyNoInteractions(rankings);
    }
    @Test void groupedRankingShowsExclusionsSourcesAndHidesExpiredOrdering() {
        var eligibility=new RankingEligibilityService(model(),policy(),HASHES);
        var entries=List.of(eligibility.assess(snapshot(1,"BANK"),NOW).withRank(1),eligibility.assess(snapshot(2,"OIL_AND_GAS"),NOW).withRank(1),eligibility.assess(withQuote(snapshot(3,"BANK"),null),NOW));
        var run=new RankingRun(1L,NOW,NOW.plusSeconds(3600),model().modelVersion(),HASHES.hash(model()),policy(),HASHES.hash(policy()),"f".repeat(64),List.of(),entries);
        var state=new RankingStore.State(NOW,null,NOW,null);var formatter=new RankingFormatter();
        assertThat(formatter.format(new StockRankingService.View(run,false,false,state),"BANK",false)).contains("SYNTH1","Исключён SYNTH3","NO_MARKET_PRICE").doesNotContain("SYNTH2");
        assertThat(formatter.format(new StockRankingService.View(run,false,false,state),null,true)).contains("Межотраслевое сравнение баллов не валидировано","quote:SYNTHETIC","score:1");
        assertThat(formatter.format(new StockRankingService.View(run,true,false,state),null,false)).contains("Текущий порядок не показывается").doesNotContain("1. SYNTH");
    }
    @Test void tickerResolutionRequiresExistingInstrumentServiceAndAmbiguityOffersUid() {
        when(instruments.resolve("SYNTH1")).thenReturn(instrument(1));assertThat(command("why").execute(List.of("SYNTH1"))).contains("score:1");
        when(instruments.resolve("DUAL")).thenThrow(new AmbiguousInstrumentException(List.of(instrument(1),instrument(2))));
        assertThat(command("analyze").execute(List.of("DUAL"))).contains("неоднозначен",uid(1),uid(2));
    }
    @Test void allCommandsUseExistingOwnerPrivateChatGuardAndDynamicHelp() {
        var commands=List.<TelegramCommand>of(command("analyze"),command("why"),command("top"),command("rankstatus"),command("rankrefresh"));
        var sender=mock(TelegramMessageSender.class);var dispatcher=new TelegramCommandDispatcher(new TelegramAccessGuard(properties()),sender,new TelegramBotState(),commands);
        for(var c:commands) {
            var outsider=update("/"+c.name()+" "+uid(1));outsider.getMessage().getFrom().setId(123L);dispatcher.dispatch(outsider);
            var group=update("/"+c.name()+" "+uid(1));group.getMessage().getChat().setType("group");dispatcher.dispatch(group);
        }
        verifyNoInteractions(instruments,snapshots,rankings,sender);
        assertThat(new HelpCommand(() -> commands).execute(List.of())).contains("/analyze","/why","/top","/rankstatus","/rankrefresh");
    }
    @Test void fullExplainabilitySurvivesTelegramChunkingWithoutLosingFinalWarnings() throws Exception {
        var text=command("why").execute(List.of(uid(1)));assertThat(text.length()).isGreaterThan(4096);
        var client=mock(TelegramClient.class);assertThat(new TelegramMessageSender(client,OWNER).send(OWNER,text)).isTrue();
        var sent=ArgumentCaptor.forClass(SendMessage.class);verify(client,atLeast(2)).execute(sent.capture());
        assertThat(sent.getAllValues()).allSatisfy(m -> {assertThat(m.getText().length()).isLessThanOrEqualTo(4096);assertThat(m.getParseMode()).isNull();});
        assertThat(sent.getAllValues().stream().map(SendMessage::getText).reduce("",String::concat)).isEqualTo(text).endsWith(ResearchText.LIMITATION);
    }
    @Test void failedRefreshMessagePreservesPreviousAndErrorsNeverLeakDetails() {
        when(rankings.refresh()).thenReturn(new StockRankingService.Refresh("FAILED_PREVIOUS_PRESERVED",null));assertThat(command("rankrefresh").execute(List.of())).contains("Предыдущий рейтинг сохранён");
        when(snapshots.capture(anyList(),any())).thenThrow(new IllegalStateException("token=private-db-secret"));assertThat(command("why").execute(List.of(uid(1)))).contains("INTERNAL").doesNotContain("private-db-secret");
    }
    @Test void announcedDividendsRequireActualDatedEventsAndStaleYieldIsNotShownAsReliable() {
        var s=snapshot(1,"OIL_AND_GAS");var events=new ArrayList<ru.ba6kir.tradelab.dividends.domain.model.DividendEvent.Observation>();
        for(int n=0;n<7;n++) {
            var event=new ru.ba6kir.tradelab.dividends.domain.model.DividendEvent(uid(1),new java.math.BigDecimal("1"),"RUB",TODAY.minusDays(1),null,TODAY.plusDays(n+1),null,"Regular Cash",null,null,null,null,
                    NOW.minusSeconds(60),NOW,"SYNTHETIC","synthetic-announced-"+n,"synthetic-identity-"+n,Set.of(),"synthetic");
            events.add(new ru.ba6kir.tradelab.dividends.domain.model.DividendEvent.Observation(event,false,NOW,NOW));
        }
        var d=s.dividends();var stale=new ru.ba6kir.tradelab.dividends.application.service.DividendService.View(s.instrument(),events,d.state(),TODAY,true,null,d.trailingSum(),d.trailingYield(),d.price());
        var updated=new AnalysisSnapshotService.StockSnapshot(s.member(),s.instrument(),s.score(),s.model(),s.quote(),stale,s.observations(),s.classification(),List.of());
        when(snapshots.capture(anyList(),any())).thenReturn(List.of(updated));
        assertThat(command("analyze").execute(List.of(uid(1)))).contains("Историческая доходность за 365 дней: нет надёжных данных","synthetic-announced-0","Показано 5 из 7 событий","не прогноз; отмена возможна");
    }
}
