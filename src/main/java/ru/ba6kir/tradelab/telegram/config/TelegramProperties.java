package ru.ba6kir.tradelab.telegram.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("tradelab.telegram")
public record TelegramProperties(boolean enabled, String botToken, Long ownerId) {

    public TelegramProperties {
        if (enabled) {
            // Constructor validation avoids putting a rejected token into binding diagnostics.
            if (botToken == null || !botToken.matches("[0-9]{5,20}:[A-Za-z0-9_-]{30,100}")) {
                throw new IllegalArgumentException("TELEGRAM_BOT_TOKEN must be a valid BotFather token when TELEGRAM_ENABLED=true");
            }
            if (ownerId == null || ownerId <= 0) {
                throw new IllegalArgumentException("TELEGRAM_OWNER_ID must be a positive numeric Telegram User ID when TELEGRAM_ENABLED=true");
            }
        }
    }

    @Override
    public String toString() {
        return "TelegramProperties[enabled=%s, botToken=<redacted>, ownerId=%s]".formatted(enabled, ownerId);
    }
}
