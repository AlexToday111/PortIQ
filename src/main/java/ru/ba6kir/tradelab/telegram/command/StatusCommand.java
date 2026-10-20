package ru.ba6kir.tradelab.telegram.command;

import java.util.List;

import ru.ba6kir.tradelab.telegram.service.TelegramStatusService;

public class StatusCommand implements TelegramCommand {
    private final TelegramStatusService statusService;

    public StatusCommand(TelegramStatusService statusService) {
        this.statusService = statusService;
    }

    @Override
    public String name() {
        return "status";
    }

    @Override
    public String description() {
        return "состояние приложения, БД и Telegram";
    }

    @Override
    public String execute(List<String> arguments) {
        return statusService.status();
    }
}
