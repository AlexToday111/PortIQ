package ru.ba6kir.tradelab.telegram.command;

import java.util.List;

/** Handlers return plain text and receive no raw update, chat ID, or transport. */
public interface TelegramCommand {
    String name();

    String description();

    String execute(List<String> arguments);

    default boolean acceptsArguments() {
        return false;
    }
}
