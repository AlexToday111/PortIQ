package ru.ba6kir.tradelab.telegram;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.telegram.telegrambots.longpolling.BotSession;
import org.telegram.telegrambots.longpolling.TelegramBotsLongPollingApplication;
import org.telegram.telegrambots.meta.api.methods.GetMe;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.objects.User;
import org.telegram.telegrambots.meta.generics.TelegramClient;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import ru.ba6kir.tradelab.telegram.adapter.TelegramPollingLifecycle;
import ru.ba6kir.tradelab.telegram.adapter.TelegramUpdateConsumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static ru.ba6kir.tradelab.telegram.TelegramTestData.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "tradelab.telegram.enabled=true", "tradelab.telegram.bot-token=" + TOKEN,
        "tradelab.telegram.owner-id=" + OWNER
})
@Import(TelegramEnabledApplicationIT.FakeTransport.class)
@Testcontainers(disabledWithoutDocker = true)
class TelegramEnabledApplicationIT {
    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-bookworm");

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("tradelab.database.host", POSTGRES::getHost);
        registry.add("tradelab.database.port", POSTGRES::getFirstMappedPort);
        registry.add("tradelab.database.name", POSTGRES::getDatabaseName);
        registry.add("tradelab.database.username", POSTGRES::getUsername);
        registry.add("tradelab.database.password", POSTGRES::getPassword);
    }

    @Autowired
    private TelegramClient client;
    @Autowired
    private TelegramUpdateConsumer consumer;
    @Autowired
    private TelegramPollingLifecycle lifecycle;

    @Test
    void enabledApplicationReportsRealDatabaseAndApplicationStatusThroughMockTransport() throws Exception {
        assertThat(lifecycle.isRunning()).isTrue();

        consumer.consume(List.of(update("/status")));

        verify(client).execute(argThat((org.telegram.telegrambots.meta.api.methods.botapimethods.BotApiMethod<?> method) ->
                method instanceof SendMessage message && message.getChatId().equals(Long.toString(OWNER))
                        && message.getText().contains("Приложение: доступно")
                        && message.getText().contains("База данных: доступна")
                        && message.getText().contains("Telegram: Long Polling запущен")));
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class FakeTransport {
        @Bean
        @Primary
        TelegramClient fakeTelegramClient() throws Exception {
            var client = mock(TelegramClient.class);
            when(client.execute(any(GetMe.class))).thenReturn(User.builder().id(123456789L).isBot(true)
                    .firstName("TradeLab").userName("TradeLabTestBot").build());
            return client;
        }

        @Bean(destroyMethod = "")
        @Primary
        TelegramBotsLongPollingApplication fakePollingApplication() throws Exception {
            var application = mock(TelegramBotsLongPollingApplication.class);
            var session = mock(BotSession.class);
            when(session.isRunning()).thenReturn(true);
            when(application.registerBot(anyString(), any(), any(), any())).thenReturn(session);
            return application;
        }
    }
}
