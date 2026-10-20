package ru.ba6kir.tradelab.dividends.telegram;

import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import ru.ba6kir.tradelab.telegram.command.TelegramCommand;
import ru.ba6kir.tradelab.market.application.AmbiguousInstrumentException;
import ru.ba6kir.tradelab.dividends.domain.model.DividendEvent.Observation;
import ru.ba6kir.tradelab.dividends.application.service.DividendService;
import ru.ba6kir.tradelab.dividends.domain.model.DividendEvent;
import ru.ba6kir.tradelab.dividends.domain.service.DividendAnalytics;

public class DividendCommand implements TelegramCommand {
    private final String name;
    private final DividendService service;
    private final DividendAnalytics analytics=new DividendAnalytics();
    public DividendCommand(String name,DividendService service) { this.name=name; this.service=service; }
    @Override public String name() { return name; }
    @Override public boolean acceptsArguments() { return !name.equals("divcalendar"); }
    @Override public String description() {
        return switch (name) {
            case "dividends" -> "дивидендные события: /dividends <тикер|UID>";
            case "divrefresh" -> "обновить дивиденды: /divrefresh <тикер|UID>";
            case "divhistory" -> "история дивидендов: /divhistory <тикер|UID> <1y|3y|5y>";
            default -> "календарь дивидендов по INVEST_TRACKED_TICKERS: /divcalendar";
        };
    }
    @Override public String execute(List<String> arguments) {
        int expected=name.equals("divcalendar")?0:name.equals("divhistory")?2:1;
        if (arguments.size()!=expected || (name.equals("divhistory") && !List.of("1y","3y","5y").contains(arguments.get(1))))
            return "Используйте /"+name+(expected==0?"":expected==1?" <тикер|UID>":" <тикер|UID> <1y|3y|5y>")+".";
        try {
            return switch (name) {
                case "divcalendar" -> calendar(service.calendar());
                case "divrefresh" -> refresh(service.refresh(arguments.getFirst()));
                case "divhistory" -> history(service.get(arguments.getFirst(),Integer.parseInt(arguments.get(1).substring(0,1))),Integer.parseInt(arguments.get(1).substring(0,1)));
                default -> dividends(service.get(arguments.getFirst(),1));
            };
        } catch (AmbiguousInstrumentException e) {
            var text=new StringBuilder("Тикер неоднозначен. Укажите UID:");
            e.matches().stream().limit(10).forEach(s -> text.append("\n/").append(name).append(" ").append(s.uid())
                    .append(name.equals("divhistory")?" 3y":"").append(" — ").append(safe(s.ticker())).append(" / ").append(safe(s.classCode())));
            return text.toString();
        } catch (RuntimeException e) { return "Дивиденды: "+error(DividendService.code(e))+"."; }
    }
    private String dividends(DividendService.View v) {
        var text=header(v);
        var sorted=sort(v.events());
        var recent=sorted.stream().filter(o -> o.event().recordDate()!=null && !o.event().recordDate().isAfter(v.asOf())).limit(6).toList();
        var upcoming=v.events().stream().filter(o -> o.event().recordDate()!=null && o.event().recordDate().isAfter(v.asOf()))
                .sorted(Comparator.comparing(o -> o.event().recordDate())).limit(2).toList();
        text.append("\nПоследние записанные события:\n");
        recent.forEach(o -> text.append(event(o,v)));
        text.append("\nБлижайшие события из API:\n");
        upcoming.forEach(o -> text.append(event(o,v)));
        sorted.stream().filter(o -> o.event().recordDate()==null).limit(2).forEach(o -> text.append(event(o,v)));
        if (v.events().isEmpty()) text.append("Событий в ответе/кэше нет; это не вывод о дивидендной политике.\n");
        if (v.events().size()>recent.size()+upcoming.size()) text.append("Полный список: /divhistory ").append(safe(v.instrument().ticker())).append(" 3y\n");
        text.append("\nСумма записанных дивидендов на акцию по реестрам (").append(v.asOf().minusDays(365)).append(", ").append(v.asOf())
                .append("]: ").append(result(v.trailingSum())).append("\nИсторическая доходность к опорной цене: ").append(result(v.trailingYield()));
        if (v.price()!=null) text.append("\nОпорная цена последней сохранённой сделки: ").append(number(v.price().amount())).append(" ")
                .append(safe(v.price().currency())).append("; дата UTC ").append(v.price().date());
        return text+footer(v);
    }
    private String history(DividendService.View v,int years) {
        LocalDate from=v.asOf().minusYears(years);
        var events=sort(v.events()).stream().filter(o -> o.event().recordDate()==null
                || (!o.event().recordDate().isBefore(from) && !o.event().recordDate().isAfter(v.asOf()))).toList();
        var text=header(v).append("\nИстория по датам реестра UTC: ").append(from).append(" — ").append(v.asOf()).append("\n");
        events.stream().limit(20).forEach(o -> text.append(event(o,v)));
        if (events.isEmpty()) text.append("Наблюдаемых событий нет.\n");
        if (events.size()>20) text.append("Показаны последние 20 из ").append(events.size()).append(" наблюдений.\n");
        analytics.statistics(events,from,v.asOf()).forEach(s -> text.append(s.year()).append(": записанных событий ").append(s.recordedEvents())
                .append(s.recordedEvents()==0?"; нет наблюдений (не доказательство отсутствия выплат)":"; допустимые наблюдаемые суммы "+s.observedAmounts())
                .append("\n"));
        text.append("Изменение сопоставимых годовых событий: ").append(result(analytics.latestComparableChange(events,v.asOf()))).append("\n");
        return text+footer(v);
    }
    private String event(Observation o,DividendService.View v) {
        var e=o.event(); var lot=analytics.perLot(e,v.instrument().lotSize());
        return "\n"+e.status(v.asOf())+(o.ambiguous()?" [AMBIGUOUS]":"")+"; реестр: "+date(e.recordDate())
                +"\nНа акцию: "+number(e.amountPerShare())+" "+safe(e.currency())+"; на лот ("+v.instrument().lotSize()+"): "+result(lot)
                +"\nОбъявление: "+date(e.declaredDate())+"; последняя покупка: "+date(e.lastBuyDate())+"; указанная выплата: "+date(e.paymentDate())
                +"\nТип: "+safe(e.dividendType())+"; регулярность: "+safe(e.regularity())
                +(e.providerYield()==null?"":"\nДоходность провайдера (шкала не уточнена): "+number(e.providerYield()))
                +(e.providerReferencePrice()==null?"":"\nЦена провайдера: "+number(e.providerReferencePrice())+" "+safe(e.referenceCurrency())+"; дата ex-dividend отсутствует")
                +"\nСумма этого события / сохранённая опорная цена: "+result(v.stale() || v.warning()!=null
                        ?DividendAnalytics.Result.unavailable("STALE_OR_FAILED_REFRESH"):analytics.eventYield(o,v.price(),v.asOf()))
                +"\nСоздано провайдером UTC: "+(e.providerCreatedAt()==null?"неизвестно":e.providerCreatedAt())+"; подтверждено API UTC: "+o.lastSeen()
                +(e.issues().isEmpty()?"":"\nКачество: "+String.join(", ",new TreeSet<>(e.issues())))+"\n";
    }
    private String refresh(DividendService.SyncResult r) {
        var c=r.counts();
        return safe(r.view().instrument().ticker())+" — обновление дивидендов: "+r.outcome()
                +(c==null?"":"\nНовых: "+c.added()+"; изменённых/конфликтующих версий: "+c.updated()+"; отмен: "+c.cancellations()
                        +"; неизменённых: "+c.unchanged()+"; неоднозначных наблюдений: "+c.ambiguous())
                +(r.error()==null?"":"\nОшибка: "+error(r.error()))+footer(r.view());
    }
    private String calendar(DividendService.CalendarView v) {
        var text=new StringBuilder("Дивидендный календарь UTC: "+v.from()+" — "+v.to()+"\nТакже: объявления и наблюдения отмен за последние 30 дней.\n");
        for (var row:v.rows()) {
            var e=row.observation().event();
            text.append("\n").append(row.date()).append(" — ").append(safe(row.ticker())).append(" — ").append(row.kind()).append("; ")
                    .append(number(e.amountPerShare())).append(" ").append(safe(e.currency())).append("/акцию; ").append(e.status(v.from()))
                    .append(row.observation().ambiguous()?" [AMBIGUOUS]":"").append(e.issues().isEmpty()?"":" ["+String.join(",",new TreeSet<>(e.issues()))+"]")
                    .append("; API UTC ").append(row.observation().lastSeen());
        }
        if (v.rows().isEmpty()) text.append("\nНаблюдаемых событий в окне нет.");
        if (v.omitted()>0) text.append("\nНе показано строк: ").append(v.omitted());
        v.warnings().forEach(w -> text.append("\nПредупреждение: ").append(safe(w)));
        return text+"\nИсточник: T-Invest. Суммы по определению провайдера. Даты не подтверждают получение денег. Будущие выплаты не гарантированы.";
    }
    private static StringBuilder header(DividendService.View v) {
        return new StringBuilder(safe(v.instrument().ticker())+" — "+safe(v.instrument().name())+"\nДивиденды; UID: "+v.instrument().uid()+"\nДата расчётов UTC: "+v.asOf()+"\n");
    }
    private static String footer(DividendService.View v) {
        return "\nИсточник: T-Invest; последний успешный запрос UTC: "+v.state().lastSuccess()+"; окно API: "+v.state().from()+" — "+v.state().to()
                +(v.stale()?"\nSTALE: кэш устарел или отсутствует":"")+(v.warning()==null?"":"\nПредупреждение: "+error(v.warning()))
                +"\nСуммы dividend_net — по определению провайдера; gross/net для конкретного инвестора не установлен. Это не полученный доход и не прогноз. Даты не подтверждают получение денег.";
    }
    private static List<Observation> sort(List<Observation> rows) {
        return rows.stream().sorted(Comparator.comparing((Observation o) -> o.event().recordDate(),Comparator.nullsLast(Comparator.reverseOrder()))
                .thenComparing(o -> o.event().contentHash())).toList();
    }
    private static String result(DividendAnalytics.Result r) { return r.value()==null?"недоступно ["+r.reason()+"]":number(r.value())+" "+r.currency(); }
    private static String number(BigDecimal n) { return n==null?"неизвестно":n.stripTrailingZeros().toPlainString(); }
    private static String date(LocalDate d) { return d==null?"не указана":d.toString(); }
    private static String safe(String s) { return s==null?"не указано":s.replaceAll("[\\p{Cntrl}]"," ").substring(0,Math.min(120,s.length())); }
    private static String error(String code) {
        return switch (code) {
            case "DISABLED" -> "T-Invest не настроен";
            case "COOLDOWN" -> "повторный запрос ограничен cooldown; показан кэш";
            case "INVALID_INPUT" -> "некорректный аргумент";
            case "NOT_FOUND" -> "инструмент не найден";
            case "AUTHENTICATION" -> "ошибка авторизации API";
            case "RATE_LIMIT" -> "лимит запросов API";
            case "TIMEOUT" -> "тайм-аут API";
            case "UNAVAILABLE" -> "API временно недоступен";
            default -> "данные временно недоступны";
        };
    }
}
