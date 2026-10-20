package ru.ba6kir.tradelab.telegram.command;

import java.util.List;

public class PingCommand implements TelegramCommand {
    @Override
    public String name() {
        return "ping";
    }

    @Override
    public String description() {
        return "проверка связи";
    }

    @Override
    public String execute(List<String> arguments) {
        return "TradeLab Portfolio: online";
    }
}
