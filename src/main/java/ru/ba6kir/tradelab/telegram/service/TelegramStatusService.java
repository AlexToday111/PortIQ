package ru.ba6kir.tradelab.telegram.service;

import java.time.Clock;
import java.time.format.DateTimeFormatter;

import org.springframework.boot.availability.ApplicationAvailability;
import org.springframework.boot.availability.ReadinessState;
import org.springframework.jdbc.core.JdbcTemplate;

public class TelegramStatusService {
    private final JdbcTemplate jdbc;
    private final ApplicationAvailability availability;
    private final TelegramBotState botState;
    private final Clock clock;

    public TelegramStatusService(JdbcTemplate jdbc, ApplicationAvailability availability,
                                 TelegramBotState botState, Clock clock) {
        this.jdbc = jdbc;
        this.availability = availability;
        this.botState = botState;
        this.clock = clock;
    }

    public String status() {
        boolean databaseAvailable;
        try {
            databaseAvailable = Integer.valueOf(1).equals(jdbc.queryForObject("SELECT 1", Integer.class));
        } catch (RuntimeException ignored) {
            databaseAvailable = false;
        }
        return "Состояние TradeLab Portfolio:\n"
                + "Приложение: " + (availability.getReadinessState() == ReadinessState.ACCEPTING_TRAFFIC
                ? "доступно" : "не готово") + "\n"
                + "База данных: " + (databaseAvailable ? "доступна" : "недоступна") + "\n"
                + "Время (UTC): " + DateTimeFormatter.ISO_INSTANT.format(clock.instant()) + "\n"
                + "Telegram: " + (botState.isPolling() ? "Long Polling запущен" : "Long Polling остановлен");
    }
}
