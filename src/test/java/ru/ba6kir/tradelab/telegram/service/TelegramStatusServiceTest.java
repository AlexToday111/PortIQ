package ru.ba6kir.tradelab.telegram.service;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import org.junit.jupiter.api.Test;
import org.springframework.boot.availability.ApplicationAvailability;
import org.springframework.boot.availability.ReadinessState;
import org.springframework.jdbc.CannotGetJdbcConnectionException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.telegram.telegrambots.longpolling.BotSession;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class TelegramStatusServiceTest {
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final ApplicationAvailability availability = mock(ApplicationAvailability.class);
    private final TelegramBotState state = new TelegramBotState();
    private final Clock clock = Clock.fixed(Instant.parse("2026-10-20T09:00:00Z"), ZoneOffset.UTC);
    private final TelegramStatusService service = new TelegramStatusService(jdbc, availability, state, clock);

    @Test
    void showsAvailabilityDatabaseClockAndPollingState() {
        when(jdbc.queryForObject("SELECT 1", Integer.class)).thenReturn(1);
        when(availability.getReadinessState()).thenReturn(ReadinessState.ACCEPTING_TRAFFIC);
        var session = mock(BotSession.class);
        when(session.isRunning()).thenReturn(true);
        state.registered(session);

        assertThat(service.status()).contains("Приложение: доступно", "База данных: доступна",
                "2026-10-20T09:00:00Z", "Telegram: Long Polling запущен");
    }

    @Test
    void databaseFailureDoesNotLeakDiagnostics() {
        when(jdbc.queryForObject("SELECT 1", Integer.class))
                .thenThrow(new CannotGetJdbcConnectionException("jdbc:postgresql://private-host password=secret"));
        when(availability.getReadinessState()).thenReturn(ReadinessState.REFUSING_TRAFFIC);

        assertThat(service.status()).contains("Приложение: не готово", "База данных: недоступна",
                "Telegram: Long Polling остановлен").doesNotContain("private-host", "secret", "jdbc:", "Exception");
    }
}
