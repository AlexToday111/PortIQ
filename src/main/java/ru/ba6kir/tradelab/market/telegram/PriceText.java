package ru.ba6kir.tradelab.market.telegram;

import java.time.*;
import java.time.format.DateTimeFormatter;
import java.math.BigDecimal;
import java.util.Locale;
import ru.ba6kir.tradelab.market.application.price.*;
import ru.ba6kir.tradelab.market.domain.price.*;
import ru.ba6kir.tradelab.market.domain.StockInstrument;

public final class PriceText {
    private static final DateTimeFormatter TIME=DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm:ss 'MSK'")
            .withZone(DailyCandle.TRADING_ZONE);
    private PriceText() { }
    public static String instrument(StockInstrument instrument) {
        return value(instrument.ticker(),32)+" — "+value(instrument.name(),160);
    }
    private static String value(String value,int limit) {
        if (value==null || value.isBlank()) return "не указан";
        String clean=value.replaceAll("[\\p{Cc}\\p{Cf}]"," ");
        return clean.substring(0,clean.offsetByCodePoints(0,Math.min(limit,clean.codePointCount(0,clean.length()))));
    }
    public static String number(BigDecimal value) { return value.stripTrailingZeros().toPlainString(); }
    public static String quote(MarketQuote q, boolean cached) {
        return "Последняя доступная цена сделки: " + number(q.price()) + " " + q.currency().toUpperCase(Locale.ROOT)
                + "\nВремя котировки: " + TIME.format(q.quoteTime())
                + "\nПолучено: " + TIME.format(q.fetchedAt())
                + "\nИсточник: T-Invest (биржа); " + (cached ? "кэш" : "свежий запрос")
                + "\nСвежесть определяется временем сделки; рынок может быть закрыт."
                + "\nЭто последняя доступная цена сделки, а не гарантированная цена исполнения.";
    }
    public static String error(String code) {
        if (code==null) return "нет";
        return switch (code) {
            case "DISABLED" -> "доступ к данным рынка не настроен (INVEST_API_ENABLED=false)";
            case "INVALID_INPUT" -> "некорректный тикер, UID или период";
            case "NOT_FOUND" -> "акция не найдена";
            case "AUTHENTICATION" -> "ошибка авторизации T-Invest; проверьте production read-only токен";
            case "RATE_LIMIT" -> "лимит T-Invest; повторите позже";
            case "TIMEOUT" -> "T-Invest не ответил вовремя";
            case "UNAVAILABLE" -> "T-Invest временно недоступен";
            case "INVALID_DATA" -> "провайдер вернул некорректные данные";
            case "NO_PRICE" -> "последняя цена отсутствует";
            case "EMPTY_HISTORY" -> "в запрошенном интервале нет свечей";
            case "AMBIGUOUS" -> "неоднозначный тикер; выберите UID через /search";
            case "COOLDOWN" -> "повторная синхронизация ограничена cooldown; попробуйте позже";
            default -> "внутренняя ошибка; повторите позже";
        };
    }
    public static String sync(MarketPriceService.SyncResult r) {
        return switch (r.outcome()) {
            case COOLDOWN -> error("COOLDOWN");
            case SUCCESS -> "Синхронизация выполнена. Цена обновлена; записано свечей: " + r.candlesSaved();
            case PARTIAL, FAILED -> (r.outcome()==MarketPriceService.Outcome.PARTIAL ? "Частичный успех." : "Синхронизация не выполнена.")
                    + " Цена: " + (r.quoteUpdated() ? "обновлена" : error(r.quoteError()))
                    + "; свечей записано: " + r.candlesSaved() + "; история: " + (r.historyError()==null ? "получена" : error(r.historyError()));
        };
    }
    public static String status(MarketPriceService service) {
        String prefix="\nT-Invest: " + (service.enabled() ? "включён" : "отключён");
        try {
            var state=service.status();
            return prefix + "\nПоследняя успешная синхронизация (UTC): " + stamp(state.lastSuccess())
                    + "\nПоследняя неудачная синхронизация (UTC): " + stamp(state.lastFailure());
        } catch (RuntimeException ignored) { return prefix+"\nСтатус синхронизации недоступен."; }
    }
    private static String stamp(Instant value) { return value==null ? "ещё не было" : value.toString(); }
}
