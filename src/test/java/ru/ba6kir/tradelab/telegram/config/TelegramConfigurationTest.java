package ru.ba6kir.tradelab.telegram.config;

import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.OkHttpClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.boot.availability.ApplicationAvailability;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.telegram.telegrambots.longpolling.BotSession;
import org.telegram.telegrambots.longpolling.TelegramBotsLongPollingApplication;
import org.telegram.telegrambots.meta.api.methods.GetMe;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.objects.User;
import org.telegram.telegrambots.meta.generics.TelegramClient;
import ru.ba6kir.tradelab.telegram.adapter.TelegramPollingLifecycle;
import ru.ba6kir.tradelab.telegram.adapter.TelegramUpdateConsumer;
import ru.ba6kir.tradelab.telegram.command.TelegramCommand;
import ru.ba6kir.tradelab.telegram.service.TelegramBotState;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static ru.ba6kir.tradelab.telegram.TelegramTestData.*;

class TelegramConfigurationTest {
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withInitializer(new ConfigDataApplicationContextInitializer())
            .withUserConfiguration(TestConfiguration.class)
            .withPropertyValues("TELEGRAM_ENABLED=false", "TELEGRAM_BOT_TOKEN=", "TELEGRAM_OWNER_ID=", "debug=false");

    @Test
    void disabledModuleStartsWithoutCredentialsOrTransport() {
        runner.run(context -> {
            assertThat(context).hasNotFailed().hasSingleBean(TelegramProperties.class)
                    .doesNotHaveBean(TelegramClient.class).doesNotHaveBean(TelegramPollingLifecycle.class)
                    .doesNotHaveBean(TelegramBotsLongPollingApplication.class)
                    .doesNotHaveBean(TelegramCommand.class).doesNotHaveBean(OkHttpClient.class);
            assertThat(context.getBean(TelegramProperties.class).enabled()).isFalse();
        });
    }

    @ParameterizedTest
    @MethodSource("invalidConfiguration")
    void enabledModuleRejectsInvalidCredentialsWithoutDisclosingToken(String property, String message) {
        runner.withPropertyValues("TELEGRAM_ENABLED=true", "TELEGRAM_BOT_TOKEN=" + TOKEN,
                "TELEGRAM_OWNER_ID=" + OWNER, property).run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).hasStackTraceContaining(message)
                    .hasStackTraceContaining("tradelab.telegram");
            assertThat(stackTrace(context.getStartupFailure())).doesNotContain(TOKEN, "INVALID_SECRET_MUST_NOT_LEAK");
        });
    }

    static Stream<Arguments> invalidConfiguration() {
        return Stream.of(Arguments.of("TELEGRAM_BOT_TOKEN=", "TELEGRAM_BOT_TOKEN must be"),
                Arguments.of("TELEGRAM_BOT_TOKEN=INVALID_SECRET_MUST_NOT_LEAK", "TELEGRAM_BOT_TOKEN must be"),
                Arguments.of("TELEGRAM_OWNER_ID=", "TELEGRAM_OWNER_ID must be"),
                Arguments.of("TELEGRAM_OWNER_ID=0", "TELEGRAM_OWNER_ID must be"),
                Arguments.of("TELEGRAM_OWNER_ID=-1", "TELEGRAM_OWNER_ID must be"),
                Arguments.of("TELEGRAM_OWNER_ID=not-numeric", "tradelab.telegram.owner-id"));
    }

    @Test
    void enabledModuleStartsDispatchesAndStopsUsingOnlyMockTransport() throws Exception {
        var client = mock(TelegramClient.class);
        var polling = mock(TelegramBotsLongPollingApplication.class);
        var session = mock(BotSession.class);
        when(session.isRunning()).thenReturn(true);
        when(client.execute(any(GetMe.class))).thenReturn(User.builder().id(123456789L).isBot(true)
                .firstName("TradeLab").userName("TradeLabTestBot").build());
        when(polling.registerBot(eq(TOKEN), any(), any(), any())).thenReturn(session);
        var http = new AtomicReference<OkHttpClient>();

        runner.withPropertyValues("TELEGRAM_ENABLED=true", "TELEGRAM_BOT_TOKEN=" + TOKEN,
                        "TELEGRAM_OWNER_ID=" + OWNER)
                .withBean(TelegramClient.class, () -> client)
                .withBean(TelegramBotsLongPollingApplication.class, () -> polling,
                        definition -> definition.setDestroyMethodName(""))
                .withBean(ObjectMapper.class, ObjectMapper::new)
                .withBean(JdbcTemplate.class, () -> mock(JdbcTemplate.class))
                .withBean(ApplicationAvailability.class, () -> mock(ApplicationAvailability.class))
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBeansOfType(TelegramCommand.class)).hasSize(4);
                    assertThat(context.getBean(TelegramPollingLifecycle.class).isRunning()).isTrue();
                    assertThat(context.getBean(TelegramBotState.class).isPolling()).isTrue();
                    http.set(context.getBean(OkHttpClient.class));
                    context.getBean(TelegramUpdateConsumer.class).consume(java.util.List.of(update("/ping")));
                    verify(client).execute(argThat((org.telegram.telegrambots.meta.api.methods.botapimethods.BotApiMethod<?> method) ->
                            method instanceof SendMessage message && message.getChatId().equals(Long.toString(OWNER))
                                    && message.getText().equals("TradeLab Portfolio: online")));
                    verify(polling).registerBot(eq(TOKEN), any(), argThat(generator ->
                            generator.apply(10).getOffset() == 11 && generator.apply(10).getTimeout() == 20
                                    && generator.apply(10).getAllowedUpdates().equals(java.util.List.of("message"))), any());
                });

        verify(polling).close();
        assertThat(http.get().dispatcher().executorService().isShutdown()).isTrue();
    }

    @Test
    void diagnosticRepresentationNeverIncludesToken() {
        assertThat(properties().toString()).contains("botToken=<redacted>").doesNotContain(TOKEN);
    }

    private static String stackTrace(Throwable throwable) {
        var writer = new java.io.StringWriter();
        throwable.printStackTrace(new java.io.PrintWriter(writer));
        return writer.toString();
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(TelegramProperties.class)
    @Import(TelegramBotConfiguration.class)
    static class TestConfiguration {
    }
}
