package ru.ba6kir.tradelab.telegram.security;

import org.telegram.telegrambots.meta.api.objects.Update;
import ru.ba6kir.tradelab.telegram.config.TelegramProperties;

public class TelegramAccessGuard {
    private final TelegramProperties properties;

    public TelegramAccessGuard(TelegramProperties properties) {
        this.properties = properties;
    }

    public boolean isAllowed(Update update) {
        if (!properties.enabled() || properties.ownerId() == null || update == null || update.getMessage() == null) {
            return false;
        }
        var message = update.getMessage();
        var sender = message.getFrom();
        var chat = message.getChat();
        return sender != null && chat != null
                && Boolean.FALSE.equals(sender.getIsBot())
                && properties.ownerId().equals(sender.getId())
                && "private".equals(chat.getType())
                && properties.ownerId().equals(chat.getId())
                && message.getSenderChat() == null;
    }
}
