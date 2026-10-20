package ru.ba6kir.tradelab.market;

import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import ru.ba6kir.tradelab.market.application.*;
import ru.ba6kir.tradelab.market.application.price.*;
import ru.ba6kir.tradelab.market.domain.price.*;
import ru.ba6kir.tradelab.market.telegram.*;
import ru.ba6kir.tradelab.telegram.adapter.TelegramMessageSender;
import ru.ba6kir.tradelab.telegram.dispatcher.TelegramCommandDispatcher;
import ru.ba6kir.tradelab.telegram.security.TelegramAccessGuard;
import ru.ba6kir.tradelab.telegram.service.TelegramBotState;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static ru.ba6kir.tradelab.market.InstrumentServiceTest.*;
import static ru.ba6kir.tradelab.market.PriceDomainTest.*;
import static ru.ba6kir.tradelab.telegram.TelegramTestData.*;

class PriceCommandTest {
    private final MarketPriceService service=mock(MarketPriceService.class);
    private final PriceCommand price=new PriceCommand("price",service);
    private final PriceCommand history=new PriceCommand("history",service);
    private final PriceCommand refresh=new PriceCommand("refresh",service);
    private final MarketPriceService.SyncResult success=new MarketPriceService.SyncResult(MarketPriceService.Outcome.SUCCESS,true,20,null,null);
    @Test void priceShowsRealValueTimeSourceAndExecutionCaveat() {
        when(service.lastPrice("SBER")).thenReturn(new MarketPriceService.QuoteResult(stock(UID,"Сбербанк",NOW),quote(NOW.minusSeconds(3600)),false,null));
        assertThat(price.execute(List.of("SBER"))).contains("123.123456789", "RUB", "MSK", "T-Invest", "свежий запрос",
                "не гарантированная цена исполнения", "рынок может быть закрыт");
    }
    @Test void cachedQuoteIncludesOriginalTimestampAndFailureLabel() {
        when(service.lastPrice(UID)).thenReturn(new MarketPriceService.QuoteResult(stock(UID,"Сбербанк",NOW),quote(NOW.minusSeconds(3600)),true,"TIMEOUT"));
        assertThat(price.execute(List.of(UID))).contains("кэш", "20.10.2026 12:00:00 MSK", "Свежий запрос не выполнен", "не ответил вовремя");
    }
    @Test void historyShowsActualObservationsAndExcludesDividendsAndIncomplete() {
        var summary=HistorySummary.calculate(List.of(candle(NOW.minus(Duration.ofDays(4)),"100",true),candle(NOW.minusSeconds(3600),"110",true)));
        when(service.history("SBER",30)).thenReturn(new MarketPriceService.HistoryResult(stock(UID,"Сбербанк",NOW),
                LocalDate.parse("2026-09-21"),LocalDate.parse("2026-10-20"),summary,1,NOW,success));
        assertThat(history.execute(List.of("SBER","30d"))).contains("Первое закрытие: 100", "Изменение цены: 10 (10%)",
                "Завершённых свечей: 2", "незавершённых исключено: 1", "Дивиденды не включены", "2026-10-16", "2026-10-20");
    }
    @Test void emptyHistoryDoesNotPretendThereIsAZeroClose() {
        when(service.history(UID,90)).thenReturn(new MarketPriceService.HistoryResult(stock(UID,"Сбербанк",NOW),
                LocalDate.parse("2026-10-20"),LocalDate.parse("2026-10-20"),Optional.empty(),1,NOW,success));
        assertThat(history.execute(List.of(UID,"90d"))).contains("Завершённых дневных свечей нет", "Незавершённых: 1").doesNotContain("закрытие: 0");
    }
    @Test void refreshExplainsSuccessPartialFailureAndCooldown() {
        when(service.refresh("SBER")).thenReturn(success,
                new MarketPriceService.SyncResult(MarketPriceService.Outcome.PARTIAL,true,2,null,"RATE_LIMIT"),
                new MarketPriceService.SyncResult(MarketPriceService.Outcome.FAILED,false,0,"TIMEOUT","UNAVAILABLE"),
                new MarketPriceService.SyncResult(MarketPriceService.Outcome.COOLDOWN,false,0,null,null));
        assertThat(refresh.execute(List.of("SBER"))).contains("выполнена", "20");
        assertThat(refresh.execute(List.of("SBER"))).contains("Частичный успех", "лимит");
        assertThat(refresh.execute(List.of("SBER"))).contains("не выполнена", "не ответил вовремя");
        assertThat(refresh.execute(List.of("SBER"))).contains("cooldown");
    }
    @Test void validatesCommandsBeforeExecutingAndReportsAmbiguity() {
        assertThat(price.execute(List.of())).contains("Используйте");
        assertThat(history.execute(List.of("SBER","10000d"))).contains("Используйте");
        assertThat(refresh.execute(List.of("SBER","extra"))).contains("Используйте");
        verifyNoInteractions(service);
        when(service.lastPrice("MULTI")).thenThrow(new AmbiguousInstrumentException(List.of(stock(UID,"one",NOW),
                stock("22222222-2222-2222-2222-222222222222","two",NOW))));
        assertThat(price.execute(List.of("MULTI"))).contains("неоднозначен", "/price "+UID);
    }
    @Test void newCommandsCannotBypassOwnerAuthorization() {
        var sender=mock(TelegramMessageSender.class);
        var dispatcher=new TelegramCommandDispatcher(new TelegramAccessGuard(properties()),sender,new TelegramBotState(),List.of(price,history,refresh));
        for (var text:List.of("/price SBER","/history SBER 30d","/refresh SBER")) {
            var unauthorized=update(text); unauthorized.getMessage().getFrom().setId(123L);
            var group=update(text); group.getMessage().getChat().setType("group");
            dispatcher.dispatch(unauthorized); dispatcher.dispatch(group);
        }
        verifyNoInteractions(service,sender);
        when(service.refresh("SBER")).thenReturn(success);
        dispatcher.dispatch(update("/refresh SBER"));
        verify(service).refresh("SBER"); verify(sender).send(eq(OWNER),contains("Синхронизация выполнена"));
    }
    @Test void stockMetadataSurvivesPriceStorageFailureAndIncludesQuoteWhenAvailable() {
        var instruments=mock(InstrumentService.class);
        when(instruments.findByUid(UID,false)).thenReturn(new InstrumentService.Metadata(stock(UID,"Сбербанк",NOW),true));
        var stockCommand=new MarketCommand(instruments,false,service);
        when(service.cachedQuote(UID)).thenReturn(Optional.of(quote(NOW.minusSeconds(60))));
        assertThat(stockCommand.execute(List.of(UID))).contains("Сбербанк", "123.123456789", "кэш");
        when(service.cachedQuote(UID)).thenThrow(new IllegalStateException("private-host secret"));
        assertThat(stockCommand.execute(List.of(UID))).contains("Сбербанк").doesNotContain("private-host", "secret");
    }
    @Test void statusExposesEnabledStateAndSyncTimesWithoutSensitiveDiagnostics() {
        when(service.enabled()).thenReturn(true);
        when(service.status()).thenReturn(new MarketPriceStore.SyncStatus(NOW,NOW.minusSeconds(60),"TIMEOUT"));
        assertThat(PriceText.status(service)).contains("T-Invest: включён",NOW.toString(),NOW.minusSeconds(60).toString());
        when(service.status()).thenThrow(new IllegalStateException("token=secret"));
        assertThat(PriceText.status(service)).contains("недоступен").doesNotContain("secret");
    }
}
