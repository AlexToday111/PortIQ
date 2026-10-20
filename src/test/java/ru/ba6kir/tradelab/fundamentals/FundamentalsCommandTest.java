package ru.ba6kir.tradelab.fundamentals;

import java.util.*;
import org.junit.jupiter.api.Test;
import ru.ba6kir.tradelab.fundamentals.application.port.FundamentalsStore;
import ru.ba6kir.tradelab.fundamentals.application.service.*;
import ru.ba6kir.tradelab.fundamentals.domain.model.FinancialReportEvent;
import ru.ba6kir.tradelab.fundamentals.telegram.FundamentalsCommand;
import ru.ba6kir.tradelab.telegram.adapter.TelegramMessageSender;
import ru.ba6kir.tradelab.telegram.command.*;
import ru.ba6kir.tradelab.telegram.dispatcher.TelegramCommandDispatcher;
import ru.ba6kir.tradelab.telegram.security.TelegramAccessGuard;
import ru.ba6kir.tradelab.telegram.service.TelegramBotState;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static ru.ba6kir.tradelab.fundamentals.FundamentalsTestData.*;
import static ru.ba6kir.tradelab.telegram.TelegramTestData.*;

class FundamentalsCommandTest {
    private final FundamentalsService service=mock(FundamentalsService.class);
    private FundamentalAnalysisView view() { return new FundamentalAnalysisView(stock(UID),identity(UID),snapshot(),true,false,false,false,null,
            new FundamentalsStore.RequestState(NOW,null,null)); }
    private FundamentalsCommand command(String name) { return new FundamentalsCommand(name,service); }
    @Test void showsAvailableRealMockedMetricsUnitsProvenanceAndQualityWithoutRecommendations() {
        when(service.getLatest("SBER")).thenReturn(view());
        var text=command("fundamentals").execute(List.of("SBER"));
        assertThat(text).contains("P/E: 5.25x", "USD", "ROE: 0.25 (шкала ?)", "UNSUPPORTED", "SUSPECT", "2025-12-31", "кэш")
                .doesNotContain("BUY","SELL","HOLD","ROE: 25");
    }
    @Test void calendarShowsPeriodPublicationAndRetrievalSeparatelyAndDisclaimsStatementExistence() {
        var event=new FinancialReportEvent(UID,NOW.plusSeconds(86400),2026,3,"PERIOD_TYPE_QUARTER",NOW.minusSeconds(1),SOURCE,NOW);
        when(service.reports("SBER")).thenReturn(new FundamentalsService.ReportsView(stock(UID),
                new FundamentalsStore.ReportBatch(List.of(event),NOW,NOW.minusSeconds(100),NOW.plusSeconds(172800)),false,false,null));
        assertThat(command("reports").execute(List.of("SBER"))).contains("2026-10-21", "2026 / PERIOD_TYPE_QUARTER / 3",
                "Получено (UTC)", "не финансовые отчёты и не подтверждение публикации");
    }
    @Test void refreshAndStatusIncludeCountsAndTimestamps() {
        when(service.refresh("SBER")).thenReturn(new FundamentalsService.SyncResult(FundamentalsService.Outcome.UNCHANGED,view(),0,null));
        when(service.status("SBER")).thenReturn(view());
        assertThat(command("fundrefresh").execute(List.of("SBER"))).contains("не изменились", "Изменённых записей метрик: 0", "T-Invest",NOW.toString());
        assertThat(command("fundstatus").execute(List.of("SBER"))).contains(UID,ASSET,"Последний успешный запрос", "в пределах TTL");
    }
    @Test void invalidArgumentsAndInternalFailuresAreSafe() {
        assertThat(command("fundamentals").execute(List.of())).contains("Используйте"); verifyNoInteractions(service);
        when(service.getLatest("SBER")).thenThrow(new IllegalStateException("password=secret private-host"));
        assertThat(command("fundamentals").execute(List.of("SBER"))).contains("внутренняя ошибка").doesNotContain("secret","private-host");
    }
    @Test void everyFundamentalCommandPassesOwnerPrivateChatGuardAndAppearsInHelp() {
        var commands=List.<TelegramCommand>of(command("fundamentals"),command("reports"),command("fundrefresh"),command("fundstatus"));
        var sender=mock(TelegramMessageSender.class);
        var dispatcher=new TelegramCommandDispatcher(new TelegramAccessGuard(properties()),sender,new TelegramBotState(),commands);
        for (var command:commands) {
            var unauthorized=update("/"+command.name()+" SBER"); unauthorized.getMessage().getFrom().setId(123L);
            var group=update("/"+command.name()+" SBER"); group.getMessage().getChat().setType("group");
            dispatcher.dispatch(unauthorized); dispatcher.dispatch(group);
        }
        verifyNoInteractions(service,sender);
        assertThat(new HelpCommand(() -> commands).execute(List.of())).contains("/fundamentals","/reports","/fundrefresh","/fundstatus");
        when(service.status("SBER")).thenReturn(view()); dispatcher.dispatch(update("/fundstatus SBER"));
        verify(service).status("SBER");
    }
}
