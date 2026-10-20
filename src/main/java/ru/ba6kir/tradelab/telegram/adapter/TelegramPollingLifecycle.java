package ru.ba6kir.tradelab.telegram.adapter;

import java.util.List;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.telegram.telegrambots.longpolling.TelegramBotsLongPollingApplication;
import org.telegram.telegrambots.meta.TelegramUrl;
import org.telegram.telegrambots.meta.api.methods.GetMe;
import org.telegram.telegrambots.meta.api.methods.updates.GetUpdates;
import org.telegram.telegrambots.meta.generics.TelegramClient;
import ru.ba6kir.tradelab.telegram.config.TelegramProperties;
import ru.ba6kir.tradelab.telegram.service.TelegramBotState;

public class TelegramPollingLifecycle implements SmartLifecycle {
    private static final Logger log = LoggerFactory.getLogger(TelegramPollingLifecycle.class);
    private final TelegramProperties properties;
    private final TelegramClient client;
    private final TelegramBotsLongPollingApplication polling;
    private final TelegramUpdateConsumer consumer;
    private final TelegramBotState state;
    private final ScheduledExecutorService executor;
    private final OkHttpClient http;
    private volatile boolean running;

    public TelegramPollingLifecycle(TelegramProperties properties, TelegramClient client,
                                    TelegramBotsLongPollingApplication polling, TelegramUpdateConsumer consumer,
                                    TelegramBotState state, ScheduledExecutorService executor, OkHttpClient http) {
        this.properties = properties;
        this.client = client;
        this.polling = polling;
        this.consumer = consumer;
        this.state = state;
        this.executor = executor;
        this.http = http;
    }

    @Override
    public synchronized void start() {
        if (running) {
            return;
        }
        try {
            var identity = client.execute(new GetMe());
            if (identity == null || !Boolean.TRUE.equals(identity.getIsBot())
                    || identity.getUserName() == null || identity.getUserName().isBlank()) {
                throw new IllegalStateException("Invalid bot identity");
            }
            state.identify(identity.getUserName());
            var session = polling.registerBot(properties.botToken(), () -> TelegramUrl.DEFAULT_URL,
                    lastId -> GetUpdates.builder().offset(lastId + 1).limit(100).timeout(20)
                            .allowedUpdates(List.of("message")).build(), consumer);
            state.registered(session);
            running = true;
            log.info("Telegram Long Polling started");
        } catch (Exception ignored) {
            stop();
            // Deliberately omit the cause: SDK exceptions may embed credentials in URLs.
            throw new IllegalStateException("Telegram startup failed. Check TELEGRAM_BOT_TOKEN, network access, and that only one bot instance is running.");
        }
    }

    @Override
    public synchronized void stop() {
        running = false;
        state.stopped();
        try {
            polling.close();
        } catch (Exception ignored) {
            log.warn("Telegram polling session cleanup failed");
        }
        http.dispatcher().cancelAll();
        executor.shutdownNow();
        http.dispatcher().executorService().shutdownNow();
        http.connectionPool().evictAll();
        try {
            if (!executor.awaitTermination(10, TimeUnit.SECONDS)) {
                log.warn("Telegram polling executor did not terminate within shutdown timeout");
            }
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
        log.info("Telegram Long Polling stopped");
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    @Override
    public int getPhase() {
        return Integer.MAX_VALUE - 100;
    }
}
