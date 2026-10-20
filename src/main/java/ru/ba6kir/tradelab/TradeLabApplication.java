package ru.ba6kir.tradelab;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.telegram.telegrambots.longpolling.starter.TelegramBotStarterConfiguration;

// The feature owns registration, safe error handling, and executor shutdown.
@SpringBootApplication(exclude = TelegramBotStarterConfiguration.class)
@ConfigurationPropertiesScan
public class TradeLabApplication {

    public static void main(String[] args) {
        SpringApplication.run(TradeLabApplication.class, args);
    }
}
