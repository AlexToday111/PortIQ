package ru.ba6kir.tradelab.analysis.reporting.presentation;

import java.math.*;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import ru.ba6kir.tradelab.analysis.domain.metric.AnalysisMetric;

public final class ResearchText {
    private ResearchText() {}
    public static final String LIMITATION="Экспериментальная фундаментальная модель: прогнозная способность не подтверждена. Высокий балл не гарантирует доходность; прошлые дивиденды не гарантируют будущие выплаты. Это не торговая рекомендация.";
    private static final DateTimeFormatter TIME=DateTimeFormatter.ofPattern("dd.MM.uuuu HH:mm:ss VV",Locale.ROOT).withZone(ZoneId.of("Europe/Moscow"));
    public static String time(Instant value) { return value==null?"нет данных":TIME.format(value); }
    public static String number(BigDecimal value) { return value==null?"нет данных":value.setScale(Math.min(6,Math.max(0,value.scale())),RoundingMode.HALF_UP).stripTrailingZeros().toPlainString().replace('.',','); }
    public static String safe(Object value) { if (value==null) return "нет данных";var text=value.toString().replaceAll("[\\p{Cntrl}\\p{Cf}]"," ");return text.length()>500?text.substring(0,500)+"… [сокращено]":text; }
    public static String unit(AnalysisMetric.Unit unit,String currency) { return switch(unit) { case PERCENT -> "%";case MONEY -> safe(currency);case PER_SHARE -> safe(currency)+"/акция";
        case MULTIPLE,RATIO -> "×";case COUNT -> "событий";case HISTORY -> "наблюдений истории";case UNVERIFIED_RATE -> "шкала не подтверждена"; }; }
    public static String sector(String value) { return switch(String.valueOf(value)) {
        case "FINANCIALS" -> "Финансы";case "OIL_AND_GAS" -> "Нефть и газ";case "METALS_AND_MINING" -> "Металлургия и добыча";
        case "INFORMATION_TECHNOLOGY" -> "Информационные технологии";case "TELECOMMUNICATIONS" -> "Телекоммуникации";case "UTILITIES" -> "Энергетика и ЖКХ";
        case "CONSUMER" -> "Потребительский сектор";case "TRANSPORT" -> "Транспорт";case "CONSTRUCTION" -> "Строительство";case "CHEMICALS" -> "Химия";
        case "INDUSTRIALS" -> "Промышленность";case "OTHER" -> "Прочее";default -> "Не подтверждён"; }; }
    public static String business(String value) { return switch(String.valueOf(value)) {case "BANK" -> "Банк";case "NON_BANK_FINANCIAL" -> "Небанковский финансовый бизнес";case "NON_FINANCIAL" -> "Нефинансовый бизнес";default -> "Не подтверждён";}; }
    public static String status(String value) { return switch(value) {case "ELIGIBLE" -> "Допущен";case "SCORED" -> "Оценён";case "PARTIAL" -> "Частичные данные";
        case "INSUFFICIENT_DATA" -> "Недостаточно данных";case "STALE_DATA" -> "Данные устарели";case "UNSUPPORTED_PROFILE" -> "Методология не поддерживается";
        case "TRADING_UNAVAILABLE" -> "Торговая доступность не подтверждена";case "NOT_COMPARABLE" -> "Сопоставимость не подтверждена";default -> "Ошибка/нет результата"; }; }
    public static String reason(String code) {
        String text=code.startsWith("MISSING_")?"Не хватает обязательных данных":code.startsWith("STALE")?"Истёк срок свежести данных":
                code.startsWith("INCOMPATIBLE_")?"Несовместимые периоды или даты":code.startsWith("UNVERIFIED_")?"Не подтверждены определения/единицы":
                code.startsWith("ALTERNATE_SECURITY_")?"Другой класс/представление того же эмитента или актива; выбран указанный UID":switch(code) {
            case "NO_STORED_SCORE" -> "Нет сохранённого результата; выполните /score для этой бумаги";
            case "NO_MARKET_PRICE" -> "Нет сохранённой цены последней сделки";
            case "UNRELIABLE_DIVIDEND_HISTORY" -> "История дивидендов не удовлетворяет требованиям покрытия и даты";
            case "MODEL_OR_CONFIGURATION_MISMATCH" -> "Версия модели или конфигурации отличается от текущего рейтинга";
            case "TRADING_METADATA_UNAVAILABLE" -> "Статус торговли или флаг API не подтверждает допуск";
            case "AMBIGUOUS_TICKER_USE_UID" -> "Тикер неоднозначен; настройте точный UID";
            case "INSTRUMENT_NOT_FOUND","UID_NOT_IN_LOCAL_CATALOG" -> "Инструмент не найден/не сохранён";
            case "SCORE_OUTSIDE_AS_OF_WINDOW" -> "Оценка вне общего временного окна";
            case "CURRENT_ASSET_MAPPING_MISMATCH","CLASSIFICATION_CHANGED_SINCE_SCORE" -> "Связь с активом или классификация изменилась после оценки";
            case "UNSUPPORTED_PROFILE","SCORE_UNSUPPORTED_PROFILE" -> "Нет поддерживаемой методологии для бизнес-типа";
            default -> "Ограничение качества, полноты или сопоставимости данных";
        };
        return text+" ["+safe(code)+"]";
    }
}
