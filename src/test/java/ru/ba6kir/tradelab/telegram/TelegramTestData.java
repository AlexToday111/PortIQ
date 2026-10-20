package ru.ba6kir.tradelab.telegram;

import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.User;
import org.telegram.telegrambots.meta.api.objects.chat.Chat;
import org.telegram.telegrambots.meta.api.objects.message.Message;
import ru.ba6kir.tradelab.telegram.config.TelegramProperties;

public final class TelegramTestData {
    public static final long OWNER = 5_000_000_001L;
    // Synthetic, never used for network access.
    public static final String TOKEN = "123456789:synthetic_test_token_not_real_123456789";

    private TelegramTestData() {
    }

    public static TelegramProperties properties() {
        return new TelegramProperties(true, TOKEN, OWNER);
    }

    public static Update update(String text) {
        var update = new Update();
        update.setUpdateId(1);
        var message = new Message();
        message.setMessageId(1);
        message.setText(text);
        message.setFrom(User.builder().id(OWNER).isBot(false).firstName("Owner").build());
        message.setChat(Chat.builder().id(OWNER).type("private").build());
        update.setMessage(message);
        return update;
    }
}
