package ru.ba6kir.tradelab.telegram.command;

import java.util.List;

/** Mutating local commands receive a stable transport request key, never a raw update or account identity. */
public interface RequestAwareTelegramCommand extends TelegramCommand {
    String execute(List<String> arguments,String requestKey);
    @Override default String execute(List<String> arguments){return "Для изменения учёта требуется идентификатор Telegram update.";}
}
