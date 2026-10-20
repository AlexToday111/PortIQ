package ru.ba6kir.tradelab.telegram.dispatcher;

import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.telegram.telegrambots.meta.api.objects.Update;
import ru.ba6kir.tradelab.telegram.adapter.TelegramMessageSender;
import ru.ba6kir.tradelab.telegram.command.*;
import ru.ba6kir.tradelab.telegram.security.TelegramAccessGuard;
import ru.ba6kir.tradelab.telegram.service.TelegramBotState;
import ru.ba6kir.tradelab.telegram.service.TelegramStatusService;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static ru.ba6kir.tradelab.telegram.TelegramTestData.*;

class TelegramCommandDispatcherTest {
    private final TelegramMessageSender sender = mock(TelegramMessageSender.class);
    private final TelegramStatusService status = mock(TelegramStatusService.class);
    private final TelegramBotState state = new TelegramBotState();
    private TelegramCommandDispatcher dispatcher;

    @BeforeEach
    void setUp() {
        state.identify("TradeLabTestBot");
        var help = new HelpCommand(() -> List.of(new StartCommand(), new PingCommand(),
                new StatusCommand(status), new HelpCommand(List::of)));
        dispatcher = dispatcher(List.of(new StartCommand(), help, new PingCommand(), new StatusCommand(status)));
        when(status.status()).thenReturn("Состояние: тестовое");
    }

    @ParameterizedTest
    @MethodSource("commands")
    void dispatchesImplementedCommandsExactlyOnce(String text, String expectedFragment) {
        dispatcher.dispatch(update(text));

        verify(sender).send(eq(OWNER), contains(expectedFragment));
        verifyNoMoreInteractions(sender);
    }

    static Stream<Arguments> commands() {
        return Stream.of(Arguments.of("/start", "личный помощник"),
                Arguments.of("/help", "/status — состояние"),
                Arguments.of("/ping", "TradeLab Portfolio: online"),
                Arguments.of("/status", "Состояние: тестовое"),
                Arguments.of("  \t/PING@tradelabTESTbot \n ", "TradeLab Portfolio: online"),
                Arguments.of("\u00a0/ping\u00a0", "TradeLab Portfolio: online"));
    }

    @Test
    void helpListsOnlyRegisteredCommands() {
        dispatcher.dispatch(update("/help"));
        verify(sender).send(eq(OWNER), argThat(text -> text.contains("/start") && text.contains("/help")
                && text.contains("/ping") && text.contains("/status") && !text.contains("/analyze") && !text.contains("/top")));
    }

    @Test
    void unknownCommandPointsToHelpWithoutEchoingInput() {
        dispatcher.dispatch(update("/unknown secret-argument"));
        verify(sender).send(OWNER, "Неизвестная команда. Используйте /help.");
        verifyNoInteractions(status);
    }

    @ParameterizedTest
    @ValueSource(strings = {"/", "/ping@", "/ping!", "/ping@TradeLabTestBot@OtherBot"})
    void malformedCommandsGetSafeValidationError(String text) {
        dispatcher.dispatch(update(text));
        verify(sender).send(OWNER, "Некорректная команда. Используйте /help.");
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"  ", "обычный текст", "text /ping", "/ping@DifferentBot"})
    void ignoresNormalTextAndCommandsAddressedToAnotherBot(String text) {
        dispatcher.dispatch(update(text));
        verifyNoInteractions(sender, status);
    }

    @Test
    void rejectsArgumentsForCurrentCommandsWithoutExecutingHandler() {
        dispatcher.dispatch(update("/status extra /ping"));
        verify(sender).send(eq(OWNER), contains("не принимает аргументы"));
        verifyNoInteractions(status);
    }

    @Test
    void parsesArgumentsForAnExtensibleRegisteredCommand() {
        var command = mock(TelegramCommand.class);
        when(command.name()).thenReturn("echo");
        when(command.acceptsArguments()).thenReturn(true);
        when(command.execute(List.of("argument1", "argument2"))).thenReturn("Принято");

        dispatcher(List.of(command)).dispatch(update(" \t/ECHO@TradeLabTestBot  argument1\n argument2  "));

        verify(command).execute(List.of("argument1", "argument2"));
        verify(sender).send(OWNER, "Принято");
    }

    @Test
    void aNewCommandCannotBypassOwnerAndPrivateChatChecks() {
        var command = mock(TelegramCommand.class);
        when(command.name()).thenReturn("future");
        var dispatcher = dispatcher(List.of(command));
        clearInvocations(command);
        var unauthorized = update("/future");
        unauthorized.getMessage().getFrom().setId(123L);
        var group = update("/future");
        group.getMessage().getChat().setType("group");

        dispatcher.dispatch(unauthorized);
        dispatcher.dispatch(group);
        dispatcher.dispatch(null);
        dispatcher.dispatch(new Update());

        verifyNoInteractions(command, sender, status);
    }

    @Test
    void refusesDuplicateRegistrations() {
        assertThatThrownBy(() -> dispatcher(List.of(new PingCommand(), new PingCommand())))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void handlerFailuresProduceGenericError() {
        when(status.status()).thenThrow(new IllegalStateException("sensitive hostname password"));
        dispatcher.dispatch(update("/status"));
        verify(sender).send(OWNER, "Не удалось выполнить команду. Попробуйте позже.");
    }

    private TelegramCommandDispatcher dispatcher(List<TelegramCommand> commands) {
        return new TelegramCommandDispatcher(new TelegramAccessGuard(properties()), sender, state, commands);
    }
}
