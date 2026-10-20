package ru.ba6kir.tradelab.telegram.adapter;

import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.telegram.telegrambots.longpolling.BotSession;
import org.telegram.telegrambots.longpolling.TelegramBotsLongPollingApplication;
import org.telegram.telegrambots.meta.api.methods.GetMe;
import org.telegram.telegrambots.meta.api.objects.User;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.telegram.telegrambots.meta.generics.TelegramClient;
import ru.ba6kir.tradelab.telegram.service.TelegramBotState;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static ru.ba6kir.tradelab.telegram.TelegramTestData.*;

@ExtendWith(OutputCaptureExtension.class)
class TelegramPollingLifecycleTest {
    private final TelegramClient client = mock(TelegramClient.class);
    private final TelegramBotsLongPollingApplication polling = mock(TelegramBotsLongPollingApplication.class);
    private final TelegramUpdateConsumer consumer = mock(TelegramUpdateConsumer.class);
    private final ScheduledExecutorService executor = mock(ScheduledExecutorService.class);
    private final OkHttpClient http = new OkHttpClient();
    private final TelegramBotState state = new TelegramBotState();
    private final TelegramPollingLifecycle lifecycle = new TelegramPollingLifecycle(properties(), client,
            polling, consumer, state, executor, http);

    @BeforeEach
    void setUp() throws Exception {
        when(executor.awaitTermination(anyLong(), any(TimeUnit.class))).thenReturn(true);
    }

    @Test
    void registersOnceThenClosesSessionsAndExecutors() throws Exception {
        when(client.execute(any(GetMe.class))).thenReturn(bot());
        var session = mock(BotSession.class);
        when(session.isRunning()).thenReturn(true);
        when(polling.registerBot(eq(TOKEN), any(), any(), eq(consumer))).thenReturn(session);

        lifecycle.start();
        lifecycle.start();

        assertThat(lifecycle.isRunning()).isTrue();
        assertThat(state.matchesUsername("tradelabtestbot")).isTrue();
        assertThat(state.isPolling()).isTrue();
        verify(polling).registerBot(eq(TOKEN), any(), any(), eq(consumer));

        lifecycle.stop();

        assertThat(lifecycle.isRunning()).isFalse();
        assertThat(state.isPolling()).isFalse();
        assertThat(state.matchesUsername("TradeLabTestBot")).isFalse();
        verify(polling).close();
        verify(executor).shutdownNow();
        assertThat(http.dispatcher().executorService().isShutdown()).isTrue();
    }

    @Test
    void startupTransportFailureCleansResourcesAndDiscardsSecretBearingCause(CapturedOutput output) throws Exception {
        when(client.execute(any(GetMe.class))).thenThrow(new TelegramApiException("https://api.telegram.org/bot" + TOKEN));

        assertThatThrownBy(lifecycle::start).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Telegram startup failed").hasMessageNotContaining(TOKEN).hasNoCause();

        verify(polling).close();
        verify(executor).shutdownNow();
        assertThat(lifecycle.isRunning()).isFalse();
        assertThat(output).doesNotContain(TOKEN, "api.telegram.org");
    }

    @Test
    void registrationFailureIsAlsoSanitizedAndCleanedUp() throws Exception {
        when(client.execute(any(GetMe.class))).thenReturn(bot());
        when(polling.registerBot(eq(TOKEN), any(), any(), eq(consumer)))
                .thenThrow(new TelegramApiException("secret=" + TOKEN));

        assertThatThrownBy(lifecycle::start).hasMessageContaining("Telegram startup failed")
                .hasMessageNotContaining(TOKEN).hasNoCause();
        assertThat(state.matchesUsername("TradeLabTestBot")).isFalse();
        verify(executor).shutdownNow();
    }

    private static User bot() {
        return User.builder().id(123456789L).isBot(true).firstName("TradeLab").userName("TradeLabTestBot").build();
    }
}
