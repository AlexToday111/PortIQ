package ru.ba6kir.tradelab.dividends;

import java.util.*;
import org.junit.jupiter.api.Test;
import ru.ba6kir.tradelab.dividends.application.port.DividendStore;
import ru.ba6kir.tradelab.dividends.application.service.DividendService;
import ru.ba6kir.tradelab.dividends.domain.service.DividendAnalytics;
import ru.ba6kir.tradelab.dividends.telegram.DividendCommand;
import ru.ba6kir.tradelab.telegram.adapter.TelegramMessageSender;
import ru.ba6kir.tradelab.telegram.command.*;
import ru.ba6kir.tradelab.telegram.dispatcher.TelegramCommandDispatcher;
import ru.ba6kir.tradelab.telegram.security.TelegramAccessGuard;
import ru.ba6kir.tradelab.telegram.service.TelegramBotState;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static ru.ba6kir.tradelab.dividends.DividendTestData.*;
import static ru.ba6kir.tradelab.telegram.TelegramTestData.*;

class DividendCommandTest {
    final DividendService service=mock(DividendService.class);
    DividendCommand command(String name) { return new DividendCommand(name,service); }
    DividendService.View view() { return new DividendService.View(stock(UID),List.of(observation(event())),
            new DividendStore.State(TODAY.minusYears(3),TODAY.plusDays(365),NOW,null,null),TODAY,false,null,
            new DividendAnalytics.Result(event().amountPerShare(),"RUB",null),DividendAnalytics.Result.unavailable("MISSING_PRICE"),null); }
    @Test void dividendsShowsActualMockObservationUnitsStatusDatesSourceAndNoReceiptClaim() {
        when(service.get("SBER",1)).thenReturn(view());
        assertThat(command("dividends").execute(List.of("SBER"))).contains("10.5 RUB","105 RUB","HISTORICAL_RECORDED","2026-10-10",
                "T-Invest", "MISSING_PRICE", "gross/net", "Даты не подтверждают получение денег").doesNotContain("PAID","BUY","SELL");
    }
    @Test void historyAcceptsAllThreeWindowsAndSortsNewestFirst() {
        for (int years:List.of(1,3,5)) {
            when(service.get("SBER",years)).thenReturn(view());
            assertThat(command("divhistory").execute(List.of("SBER",years+"y"))).contains("История по датам реестра UTC","записанных событий");
            verify(service).get("SBER",years);
        }
    }
    @Test void refreshShowsNewConflictCancellationUnchangedCountsAndErrors() {
        when(service.refresh("SBER")).thenReturn(new DividendService.SyncResult("SUCCESS",new DividendStore.Counts(1,2,3,4,5),null,view()));
        assertThat(command("divrefresh").execute(List.of("SBER"))).contains("Новых: 1","версий: 2","отмен: 3","неизменённых: 4","наблюдений: 5");
    }
    @Test void calendarShowsDateKindAmountAndEvidenceStatus() {
        when(service.calendar()).thenReturn(new DividendService.CalendarView(TODAY,TODAY.plusDays(90),
                List.of(new DividendService.CalendarRow("SBER",TODAY.plusDays(10),"реестр",observation(event()))),List.of(),0));
        assertThat(command("divcalendar").execute(List.of())).contains("2026-10-30 — SBER — реестр","10.5 RUB/акцию","HISTORICAL_RECORDED");
        assertThat(command("divcalendar").acceptsArguments()).isFalse();
    }
    @Test void allCommandsRejectWrongArgumentsAndSanitizeUnexpectedExceptions() {
        for (String name:List.of("dividends","divrefresh","divhistory")) assertThat(command(name).execute(List.of())).contains("Используйте");
        assertThat(command("divcalendar").execute(List.of("extra"))).contains("Используйте");
        assertThat(command("divhistory").execute(List.of("SBER","10y"))).contains("Используйте"); verifyNoInteractions(service);
        when(service.get("SBER",1)).thenThrow(new IllegalStateException("password=secret internalhost"));
        assertThat(command("dividends").execute(List.of("SBER"))).doesNotContain("secret","internalhost");
    }
    @Test void allFourCommandsAreOwnerPrivateOnlyAndInDynamicHelp() {
        var commands=List.<TelegramCommand>of(command("dividends"),command("divcalendar"),command("divrefresh"),command("divhistory"));
        var sender=mock(TelegramMessageSender.class);
        var dispatcher=new TelegramCommandDispatcher(new TelegramAccessGuard(properties()),sender,new TelegramBotState(),commands);
        for (var c:commands) {
            var outsider=update("/"+c.name()+" SBER 3y"); outsider.getMessage().getFrom().setId(123L);
            var group=update("/"+c.name()); group.getMessage().getChat().setType("group");
            dispatcher.dispatch(outsider); dispatcher.dispatch(group);
        }
        verifyNoInteractions(service,sender);
        assertThat(new HelpCommand(() -> commands).execute(List.of())).contains("/dividends","/divcalendar","/divrefresh","/divhistory");
        when(service.get("SBER",1)).thenReturn(view()); dispatcher.dispatch(update("/dividends SBER")); verify(service).get("SBER",1);
    }
}
