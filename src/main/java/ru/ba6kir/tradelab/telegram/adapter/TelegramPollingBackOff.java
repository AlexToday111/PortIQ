package ru.ba6kir.tradelab.telegram.adapter;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.telegram.telegrambots.longpolling.interfaces.BackOff;
import org.telegram.telegrambots.longpolling.util.ExponentialBackOff;

public class TelegramPollingBackOff implements BackOff {
    private static final Logger log = LoggerFactory.getLogger(TelegramPollingBackOff.class);
    private final BackOff delegate = new ExponentialBackOff();

    @Override
    public void reset() {
        delegate.reset();
    }

    @Override
    public long nextBackOffMillis() {
        // Keep retrying after long outages, with a bounded delay rather than a terminal sentinel.
        long delay = Math.max(1000, Math.min(60000, delegate.nextBackOffMillis()));
        log.warn("Telegram polling unavailable; retrying in {} ms", delay);
        return delay;
    }
}
