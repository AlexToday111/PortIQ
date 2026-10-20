package ru.ba6kir.tradelab.telegram.command;

import java.util.Comparator;
import java.util.List;
import java.util.function.Supplier;
import java.util.stream.Collectors;

public class HelpCommand implements TelegramCommand {
    private final Supplier<List<TelegramCommand>> commands;

    public HelpCommand(Supplier<List<TelegramCommand>> commands) {
        this.commands = commands;
    }

    @Override
    public String name() {
        return "help";
    }

    @Override
    public String description() {
        return "список доступных команд";
    }

    @Override
    public String execute(List<String> arguments) {
        return "Доступные команды:\n" + commands.get().stream()
                .sorted(Comparator.comparing(TelegramCommand::name))
                .map(command -> "/" + command.name() + " — " + command.description())
                .collect(Collectors.joining("\n"));
    }
}
