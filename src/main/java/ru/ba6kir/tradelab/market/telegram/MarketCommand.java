package ru.ba6kir.tradelab.market.telegram;

import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ru.ba6kir.tradelab.market.application.InstrumentService;
import ru.ba6kir.tradelab.market.application.MarketException;
import ru.ba6kir.tradelab.market.domain.StockInstrument;
import ru.ba6kir.tradelab.telegram.command.TelegramCommand;

/** Two thin command instances share formatting and controlled error presentation. */
public class MarketCommand implements TelegramCommand {
    private static final Logger log = LoggerFactory.getLogger(MarketCommand.class);
    private static final int RESULT_LIMIT = 10;
    private final InstrumentService service;
    private final boolean search;
    private final ru.ba6kir.tradelab.market.application.price.MarketPriceService prices;

    public MarketCommand(InstrumentService service, boolean search) {
        this(service, search, null);
    }

    public MarketCommand(InstrumentService service, boolean search, ru.ba6kir.tradelab.market.application.price.MarketPriceService prices) {
        this.service = service;
        this.search = search;
        this.prices = prices;
    }

    @Override public String name() { return search ? "search" : "stock"; }
    @Override public String description() {
        return search ? "поиск акций: /search <запрос>" : "метаданные акции: /stock <тикер|UID> [refresh]";
    }
    @Override public boolean acceptsArguments() { return true; }

    @Override
    public String execute(List<String> arguments) {
        try {
            if (arguments.isEmpty()) return usage();
            if (search) {
                var matches = service.search(String.join(" ", arguments));
                return matches.isEmpty() ? "Акции не найдены." : matches(matches, false);
            }
            if (arguments.size() > 2 || (arguments.size() == 2 && !arguments.get(1).equalsIgnoreCase("refresh")))
                return usage();
            String identifier = arguments.getFirst();
            boolean refresh = arguments.size() == 2;
            if (identifier.length() == 36) return details(service.findByUid(identifier, refresh));
            var candidates = service.findByTicker(identifier);
            if (candidates.isEmpty()) return "Акция не найдена.";
            if (candidates.size() > 1) return matches(candidates, true);
            return details(service.findByUid(candidates.getFirst().uid(), refresh));
        } catch (MarketException e) {
            return switch (e.reason()) {
                case DISABLED -> "Доступ к данным рынка не настроен (INVEST_API_ENABLED=false).";
                case INVALID_INPUT -> usage();
                case NOT_FOUND -> "Акция не найдена.";
                case AUTHENTICATION -> "Ошибка авторизации T-Invest. Проверьте read-only токен.";
                case RATE_LIMIT -> "Достигнут лимит T-Invest. Повторите запрос позже.";
                case TIMEOUT -> "T-Invest не ответил вовремя. Повторите запрос позже.";
                case UNAVAILABLE -> "T-Invest временно недоступен. Повторите запрос позже.";
                case INTERNAL -> "Внутренняя ошибка получения метаданных. Повторите запрос позже.";
            };
        } catch (RuntimeException ignored) {
            log.warn("Market metadata command failed internally");
            return "Внутренняя ошибка получения метаданных. Повторите запрос позже.";
        }
    }

    private String usage() {
        return search ? "Используйте /search <запрос>: 1–64 буквы, цифры, пробелы, . _ -"
                : "Используйте /stock <тикер|UID> [refresh]. Например: /stock SBER";
    }

    private String matches(List<StockInstrument> matches, boolean ambiguous) {
        var result = new StringBuilder(ambiguous ? "Тикер неоднозначен. Выберите UID:"
                : "Найдены акции (T-Invest, свежий поиск):");
        matches.stream().limit(RESULT_LIMIT).forEach(s -> result.append("\n")
                .append(text(s.ticker(), 32)).append(" / ").append(text(s.classCode(), 16))
                .append(" — ").append(text(s.name(), 80)).append("\n/stock ").append(s.uid()));
        if (matches.size() > RESULT_LIMIT) result.append("\nПоказаны первые 10. Уточните запрос через /search.");
        return result.toString();
    }

    private String details(InstrumentService.Metadata metadata) {
        var s = metadata.instrument();
        var details = """
                %s
                Тикер: %s / %s
                Биржа: %s
                Валюта: %s
                Лот: %s
                UID: %s
                FIGI: %s
                ISIN: %s
                Статус торгов (метаданные): %s
                Доступен через API: %s
                Обновлено (UTC): %s
                Источник: %s; %s
                """.formatted(text(s.name(), 160), text(s.ticker(), 32), text(s.classCode(), 16), text(s.exchange(), 80),
                text(s.currency(), 16), s.lotSize() == null ? "не указан" : s.lotSize(), s.uid(),
                text(s.figi(), 32), text(s.isin(), 32), text(s.tradingStatus(), 100),
                s.apiTradeAvailable() == null ? "не указан" : s.apiTradeAvailable() ? "да" : "нет",
                s.updatedAt(), s.source(), metadata.cached() ? "кэш" : "свежие метаданные");
        if (prices!=null) {
            try {
                var quote=prices.cachedQuote(s.uid());
                if (quote.isPresent()) details+="\n"+PriceText.quote(quote.get(),true);
            } catch (RuntimeException ignored) { /* Metadata remains available independently of prices. */ }
        }
        return details;
    }

    private static String text(String value, int limit) {
        if (value == null || value.isBlank()) return "не указан";
        var clean = value.replaceAll("[\\p{Cc}\\p{Cf}]", " ");
        int end = clean.offsetByCodePoints(0, Math.min(limit, clean.codePointCount(0, clean.length())));
        return clean.substring(0, end);
    }
}
