package ru.ba6kir.tradelab.telegram.dispatcher;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.telegram.telegrambots.meta.api.objects.Update;
import ru.ba6kir.tradelab.telegram.adapter.TelegramMessageSender;
import ru.ba6kir.tradelab.telegram.command.TelegramCommand;
import ru.ba6kir.tradelab.telegram.security.TelegramAccessGuard;
import ru.ba6kir.tradelab.telegram.service.TelegramBotState;

public class TelegramCommandDispatcher {
    private static final Logger log = LoggerFactory.getLogger(TelegramCommandDispatcher.class);
    private static final Pattern COMMAND = Pattern.compile("/([A-Za-z0-9_]{1,32})(?:@([A-Za-z0-9_]{5,32}))?");
    private static final Pattern WHITESPACE = Pattern.compile("\\s+", Pattern.UNICODE_CHARACTER_CLASS);
    private final TelegramAccessGuard guard;
    private final TelegramMessageSender sender;
    private final TelegramBotState botState;
    private final Map<String, TelegramCommand> commands;

    public TelegramCommandDispatcher(TelegramAccessGuard guard, TelegramMessageSender sender,
                                     TelegramBotState botState, List<TelegramCommand> commands) {
        this.guard = guard;
        this.sender = sender;
        this.botState = botState;
        for (var command : commands) {
            if (!command.name().matches("[a-z0-9_]{1,32}")) {
                throw new IllegalArgumentException("Telegram command names must be lowercase ASCII identifiers");
            }
        }
        this.commands = commands.stream().collect(Collectors.toUnmodifiableMap(TelegramCommand::name, command -> command));
    }

    public void dispatch(Update update) {
        // Every handler, including future registered commands, passes this gate first.
        if (!guard.isAllowed(update)) {
            return;
        }
        var text = update.getMessage().getText();
        if (text == null || text.isBlank()) {
            return;
        }
        var parts = WHITESPACE.matcher(text).replaceAll(" ").trim().split(" ");
        if (!parts[0].startsWith("/")) {
            return;
        }
        long chatId = update.getMessage().getChat().getId();
        var match = COMMAND.matcher(parts[0]);
        if (!match.matches()) {
            sender.send(chatId, "Некорректная команда. Используйте /help.");
            return;
        }
        if (match.group(2) != null && !botState.matchesUsername(match.group(2))) {
            return;
        }
        var command = commands.get(match.group(1).toLowerCase(Locale.ROOT));
        if (command == null) {
            sender.send(chatId, "Неизвестная команда. Используйте /help.");
            return;
        }
        var arguments = List.copyOf(Arrays.asList(parts).subList(1, parts.length));
        if (!arguments.isEmpty() && !command.acceptsArguments()) {
            sender.send(chatId, "Команда /" + command.name() + " не принимает аргументы. Используйте /help.");
            return;
        }
        try {
            String response = command.execute(arguments);
            boolean delivered = sender.send(chatId, response);
            log.info("Telegram command /{} completed; delivered={}", command.name(), delivered);
        } catch (RuntimeException ignored) {
            log.warn("Telegram command /{} failed", command.name());
            sender.send(chatId, "Не удалось выполнить команду. Попробуйте позже.");
        }
    }
}
