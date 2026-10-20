package ru.ba6kir.tradelab.market;

import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import ru.ba6kir.tradelab.market.application.*;
import ru.ba6kir.tradelab.market.telegram.MarketCommand;
import ru.ba6kir.tradelab.telegram.adapter.TelegramMessageSender;
import ru.ba6kir.tradelab.telegram.command.HelpCommand;
import ru.ba6kir.tradelab.telegram.dispatcher.TelegramCommandDispatcher;
import ru.ba6kir.tradelab.telegram.security.TelegramAccessGuard;
import ru.ba6kir.tradelab.telegram.service.TelegramBotState;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static ru.ba6kir.tradelab.market.InstrumentServiceTest.*;
import static ru.ba6kir.tradelab.telegram.TelegramTestData.*;

class MarketCommandTest {
    private final InstrumentService service = mock(InstrumentService.class);
    private final MarketCommand stock = new MarketCommand(service, false);
    private final MarketCommand search = new MarketCommand(service, true);

    @Test void displaysMetadataSourceCacheAndNoPrices() {
        var instrument = stock(UID, "Сбербанк", NOW);
        when(service.findByTicker("SBER")).thenReturn(List.of(instrument));
        when(service.findByUid(UID, false)).thenReturn(new InstrumentService.Metadata(instrument, true));
        assertThat(stock.execute(List.of("SBER"))).contains("Сбербанк", "MOEX", "rub", "Лот: 10", UID,
                "2026-10-20T10:00:00Z", "кэш", "T_INVEST_PRODUCTION", "не указан").doesNotContain("Цена", "price");
    }

    @Test void ambiguousTickerRequiresUidAndDoesNotFetchOrSaveAnArbitraryMatch() {
        when(service.findByTicker("SBER")).thenReturn(List.of(stock(UID, "one", NOW),
                stock("22222222-2222-2222-2222-222222222222", "two", NOW)));
        assertThat(stock.execute(List.of("SBER"))).contains("неоднозначен", "/stock " + UID, "two");
        verify(service, never()).findByUid(anyString(), anyBoolean());
    }

    @Test void directUidAndExplicitRefreshWork() {
        when(service.findByUid(UID, true)).thenReturn(new InstrumentService.Metadata(stock(UID, "new", NOW), false));
        assertThat(stock.execute(List.of(UID, "refresh"))).contains("свежие метаданные");
    }

    @Test void boundedSearchAndNoResults() {
        when(service.search("Сбер банк")).thenReturn(List.of());
        assertThat(search.execute(List.of("Сбер", "банк"))).isEqualTo("Акции не найдены.");
        var many = java.util.stream.IntStream.range(0, 30).mapToObj(i -> stock(UID, "Company " + i, NOW)).toList();
        when(service.search("банк")).thenReturn(many);
        var response = search.execute(List.of("банк"));
        assertThat(response).contains("Company 9", "Уточните").doesNotContain("Company 10");
        assertThat(response.length()).isLessThan(4096);
    }

    @Test void validatesArgumentShape() {
        assertThat(stock.execute(List.of())).contains("Используйте");
        assertThat(search.execute(List.of())).contains("Используйте");
        assertThat(stock.execute(List.of("SBER", "garbage"))).contains("Используйте");
        verifyNoInteractions(service);
    }

    @Test void allProviderFailuresAreSafeReadableAndDistinct() {
        var replies = new HashSet<String>();
        for (var reason : MarketException.Reason.values()) {
            doThrow(new MarketException(reason)).when(service).search("SBER");
            var response = search.execute(List.of("SBER"));
            assertThat(response).doesNotContain("Exception", "Bearer");
            replies.add(response);
        }
        assertThat(replies).hasSize(MarketException.Reason.values().length);
    }

    @Test void registeredMarketCommandsCannotBypassAuthorizationAndAppearInHelp() {
        var sender = mock(TelegramMessageSender.class);
        var help = new HelpCommand(() -> List.of(stock, search));
        var dispatcher = new TelegramCommandDispatcher(new TelegramAccessGuard(properties()), sender,
                new TelegramBotState(), List.of(stock, search, help));
        var unauthorized = update("/stock SBER");
        unauthorized.getMessage().getFrom().setId(123L);
        var group = update("/search SBER");
        group.getMessage().getChat().setType("group");
        dispatcher.dispatch(unauthorized);
        dispatcher.dispatch(group);
        verifyNoInteractions(service, sender);
        dispatcher.dispatch(update("/help"));
        verify(sender).send(eq(OWNER), contains("/stock"));
        assertThat(help.execute(List.of())).contains("/search");
    }
}
