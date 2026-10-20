package ru.ba6kir.tradelab.market.telegram;

import java.util.*;
import org.slf4j.*;
import ru.ba6kir.tradelab.market.application.price.*;
import ru.ba6kir.tradelab.market.application.AmbiguousInstrumentException;
import ru.ba6kir.tradelab.telegram.command.TelegramCommand;

public class PriceCommand implements TelegramCommand {
    private static final Logger log=LoggerFactory.getLogger(PriceCommand.class);
    private final String name;
    private final MarketPriceService service;
    public PriceCommand(String name, MarketPriceService service) { this.name=name; this.service=service; }
    @Override public String name() { return name; }
    @Override public boolean acceptsArguments() { return true; }
    @Override public String description() {
        return switch (name) {
            case "price" -> "последняя цена сделки: /price <тикер|UID>";
            case "history" -> "изменение цены без дивидендов: /history <тикер|UID> <30d|90d|180d|365d>";
            default -> "синхронизация: /refresh <тикер|UID|tracked>";
        };
    }
    @Override public String execute(List<String> arguments) {
        try {
            if (name.equals("history")) {
                if (arguments.size()!=2 || !Set.of("30d","90d","180d","365d").contains(arguments.get(1))) return usage();
                int days=Integer.parseInt(arguments.get(1).substring(0,arguments.get(1).length()-1));
                return history(service.history(arguments.getFirst(),days));
            }
            if (arguments.size()!=1) return usage();
            if (name.equals("price")) {
                var result=service.lastPrice(arguments.getFirst());
                return PriceText.instrument(result.instrument())+"\n\n"
                        +PriceText.quote(result.quote(),result.cached())
                        +(result.warning()==null ? "" : "\nСвежий запрос не выполнен: "+PriceText.error(result.warning()));
            }
            if (arguments.getFirst().equalsIgnoreCase("tracked")) {
                var results=service.refreshTracked();
                if (results.isEmpty()) return "Список INVEST_TRACKED_TICKERS пуст.";
                var text=new StringBuilder("Ручная синхронизация настроенного списка:");
                for (var result:results) text.append("\n").append(result.ticker()).append(": ")
                        .append(result.error()!=null ? PriceText.error(result.error()) : PriceText.sync(result.synchronization()));
                return text.toString();
            }
            return PriceText.sync(service.refresh(arguments.getFirst()));
        } catch (AmbiguousInstrumentException e) {
            var text=new StringBuilder("Тикер неоднозначен. Выберите UID:");
            e.matches().stream().limit(10).forEach(s -> text.append("\n").append(s.ticker()).append(" / ")
                    .append(s.classCode()).append("\n/").append(name).append(" ").append(s.uid())
                    .append(name.equals("history") ? " 30d" : ""));
            return text.toString();
        } catch (RuntimeException e) {
            log.warn("Market price command failed; reason={}",MarketPriceService.code(e));
            return PriceText.error(MarketPriceService.code(e))+". "+usage();
        }
    }
    private String history(MarketPriceService.HistoryResult r) {
        String prefix=PriceText.instrument(r.instrument())+"\nПериод: "+r.from()+" — "+r.to();
        String status="\n"+PriceText.sync(r.synchronization());
        if (r.summary().isEmpty()) return prefix+"\nЗавершённых дневных свечей нет. Незавершённых: "+r.incompleteCount()+status;
        var s=r.summary().get();
        return prefix+"\nНаблюдения: "+s.firstDate()+" — "+s.lastDate()
                +"\nПервое закрытие: "+PriceText.number(s.firstClose())+" "+r.instrument().currency()
                +"\nПоследнее завершённое закрытие: "+PriceText.number(s.lastClose())+" "+r.instrument().currency()
                +"\nИзменение цены: "+PriceText.number(s.absoluteChange())+" ("+PriceText.number(s.percentChange())+"%)"
                +"\nЗавершённых свечей: "+s.finalizedCount()+"; незавершённых исключено: "+r.incompleteCount()
                +"\nИсточник: T-Invest; "+(r.synchronization().outcome()==MarketPriceService.Outcome.SUCCESS ? "история обновлена" : "локально сохранённые данные (кэш)")
                +"\nПоследнее получение сохранённых свечей (UTC): "+r.lastFetchedAt()
                +"\nДивиденды не включены. Это изменение цены, а не полная инвестиционная доходность."+status;
    }
    private String usage() { return "Используйте /"+name+(name.equals("history") ? " <тикер|UID> <30d|90d|180d|365d>." : " <тикер|UID>."); }
}
