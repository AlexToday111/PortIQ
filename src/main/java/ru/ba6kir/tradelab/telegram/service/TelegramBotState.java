package ru.ba6kir.tradelab.telegram.service;

import org.telegram.telegrambots.longpolling.BotSession;

/** Instance-local registration state; never contains credentials. */
public class TelegramBotState {
    private volatile String username;
    private volatile BotSession session;

    public void identify(String username) {
        this.username = username;
    }

    public boolean matchesUsername(String candidate) {
        var current = username;
        return current != null && current.equalsIgnoreCase(candidate);
    }

    public void registered(BotSession session) {
        this.session = session;
    }

    public boolean isPolling() {
        var current = session;
        return current != null && current.isRunning();
    }

    public void stopped() {
        session = null;
        username = null;
    }
}
