package ru.ba6kir.tradelab.telegram.config;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.OkHttpClient;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.availability.ApplicationAvailability;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.telegram.telegrambots.client.okhttp.OkHttpTelegramClient;
import org.telegram.telegrambots.longpolling.TelegramBotsLongPollingApplication;
import org.telegram.telegrambots.meta.generics.TelegramClient;
import ru.ba6kir.tradelab.telegram.adapter.TelegramMessageSender;
import ru.ba6kir.tradelab.telegram.adapter.TelegramPollingBackOff;
import ru.ba6kir.tradelab.telegram.adapter.TelegramPollingLifecycle;
import ru.ba6kir.tradelab.telegram.adapter.TelegramUpdateConsumer;
import ru.ba6kir.tradelab.telegram.command.HelpCommand;
import ru.ba6kir.tradelab.telegram.command.PingCommand;
import ru.ba6kir.tradelab.telegram.command.StartCommand;
import ru.ba6kir.tradelab.telegram.command.StatusCommand;
import ru.ba6kir.tradelab.telegram.command.TelegramCommand;
import ru.ba6kir.tradelab.telegram.dispatcher.TelegramCommandDispatcher;
import ru.ba6kir.tradelab.telegram.security.TelegramAccessGuard;
import ru.ba6kir.tradelab.telegram.service.TelegramBotState;
import ru.ba6kir.tradelab.telegram.service.TelegramStatusService;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "tradelab.telegram", name = "enabled", havingValue = "true")
public class TelegramBotConfiguration {
    @Bean(destroyMethod = "shutdownNow", defaultCandidate = false)
    ScheduledExecutorService telegramPollingExecutor() {
        return Executors.newSingleThreadScheduledExecutor(runnable -> {
            var thread = new Thread(runnable, "tradelab-telegram-polling");
            thread.setDaemon(true);
            return thread;
        });
    }

    @Bean
    OkHttpClient telegramHttpClient() {
        var http = new OkHttpClient.Builder().connectTimeout(Duration.ofSeconds(10))
                .readTimeout(Duration.ofSeconds(30)).callTimeout(Duration.ofSeconds(35)).build();
        http.dispatcher().setMaxRequests(2);
        http.dispatcher().setMaxRequestsPerHost(2);
        return http;
    }

    @Bean
    @ConditionalOnMissingBean(TelegramClient.class)
    TelegramClient telegramClient(TelegramProperties properties, OkHttpClient telegramHttpClient) {
        return new OkHttpTelegramClient(telegramHttpClient, properties.botToken());
    }

    @Bean(destroyMethod = "") // Closed by the lifecycle before its executors are stopped.
    @ConditionalOnMissingBean(TelegramBotsLongPollingApplication.class)
    TelegramBotsLongPollingApplication telegramPollingApplication(ObjectMapper mapper, OkHttpClient telegramHttpClient,
            @Qualifier("telegramPollingExecutor") ScheduledExecutorService executor) {
        return new TelegramBotsLongPollingApplication(mapper::copy, () -> telegramHttpClient,
                () -> executor, TelegramPollingBackOff::new);
    }

    @Bean
    TelegramBotState telegramBotState() {
        return new TelegramBotState();
    }

    @Bean
    TelegramAccessGuard telegramAccessGuard(TelegramProperties properties) {
        return new TelegramAccessGuard(properties);
    }

    @Bean
    TelegramMessageSender telegramMessageSender(TelegramClient client, TelegramProperties properties) {
        return new TelegramMessageSender(client, properties.ownerId());
    }

    @Bean
    StartCommand telegramStartCommand() {
        return new StartCommand();
    }

    @Bean
    PingCommand telegramPingCommand() {
        return new PingCommand();
    }

    @Bean
    HelpCommand telegramHelpCommand(ObjectProvider<TelegramCommand> commands) {
        return new HelpCommand(() -> commands.orderedStream().toList());
    }

    @Bean
    TelegramStatusService telegramStatusService(JdbcTemplate jdbc, ApplicationAvailability availability, TelegramBotState state,
            ObjectProvider<ru.ba6kir.tradelab.market.application.price.MarketPriceService> market) {
        return new TelegramStatusService(jdbc, availability, state, Clock.systemUTC(), () -> {
            var service=market.getIfAvailable();
            return service==null ? "" : ru.ba6kir.tradelab.market.telegram.PriceText.status(service);
        });
    }

    @Bean
    StatusCommand telegramStatusCommand(TelegramStatusService service) {
        return new StatusCommand(service);
    }

    @Bean
    TelegramCommandDispatcher telegramCommandDispatcher(TelegramAccessGuard guard, TelegramMessageSender sender,
            TelegramBotState state, List<TelegramCommand> commands) {
        return new TelegramCommandDispatcher(guard, sender, state, commands);
    }

    @Bean
    TelegramUpdateConsumer telegramUpdateConsumer(TelegramCommandDispatcher dispatcher) {
        return new TelegramUpdateConsumer(dispatcher);
    }

    @Bean
    TelegramPollingLifecycle telegramPollingLifecycle(TelegramProperties properties, TelegramClient client,
            TelegramBotsLongPollingApplication polling, TelegramUpdateConsumer consumer, TelegramBotState state,
            @Qualifier("telegramPollingExecutor") ScheduledExecutorService executor, OkHttpClient telegramHttpClient) {
        return new TelegramPollingLifecycle(properties, client, polling, consumer, state, executor, telegramHttpClient);
    }
}
