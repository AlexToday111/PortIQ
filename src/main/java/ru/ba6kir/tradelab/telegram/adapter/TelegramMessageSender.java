package ru.ba6kir.tradelab.telegram.adapter;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.telegram.telegrambots.meta.generics.TelegramClient;

public class TelegramMessageSender {
    private static final Logger log = LoggerFactory.getLogger(TelegramMessageSender.class);
    private static final int MAX_LENGTH = 4096;
    private final TelegramClient client;
    private final long ownerId;

    public TelegramMessageSender(TelegramClient client, long ownerId) {
        this.client = client;
        this.ownerId = ownerId;
    }

    public boolean send(long chatId, String text) {
        if (chatId != ownerId || text == null || text.isBlank()) {
            return false;
        }
        try {
            for (int offset = 0; offset < text.length();) {
                int end = Math.min(offset + MAX_LENGTH, text.length());
                if (end < text.length() && Character.isHighSurrogate(text.charAt(end - 1))) {
                    end--;
                }
                client.execute(SendMessage.builder().chatId(Long.toString(chatId))
                        .text(text.substring(offset, end)).build());
                offset = end;
            }
            return true;
        } catch (TelegramApiException | RuntimeException ignored) {
            // Exception messages and request URLs can contain the bot token.
            log.warn("Telegram message delivery failed; response was not retried");
            return false;
        }
    }
}
