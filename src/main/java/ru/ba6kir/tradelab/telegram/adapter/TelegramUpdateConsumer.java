package ru.ba6kir.tradelab.telegram.adapter;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.telegram.telegrambots.longpolling.interfaces.LongPollingUpdateConsumer;
import org.telegram.telegrambots.meta.api.objects.Update;
import ru.ba6kir.tradelab.telegram.dispatcher.TelegramCommandDispatcher;

public class TelegramUpdateConsumer implements LongPollingUpdateConsumer {
    private static final Logger log = LoggerFactory.getLogger(TelegramUpdateConsumer.class);
    private final TelegramCommandDispatcher dispatcher;

    public TelegramUpdateConsumer(TelegramCommandDispatcher dispatcher) {
        this.dispatcher = dispatcher;
    }

    @Override
    public void consume(List<Update> updates) {
        if (updates == null) {
            return;
        }
        // Sequential processing on the managed polling thread: no unbounded update queue.
        for (var update : updates) {
            try {
                dispatcher.dispatch(update);
            } catch (RuntimeException ignored) {
                log.warn("Malformed Telegram update ignored");
            }
        }
    }
}
