package ru.ba6kir.tradelab.telegram.command;

import java.util.List;

public class StartCommand implements TelegramCommand {
    @Override
    public String name() {
        return "start";
    }

    @Override
    public String description() {
        return "знакомство с помощником";
    }

    @Override
    public String execute(List<String> arguments) {
        return "TradeLab Portfolio — ваш личный помощник для долгосрочного инвестиционного анализа.\n"
                + "Сейчас доступны базовые команды и проверка состояния приложения.\n"
                + "Список команд: /help";
    }
}
