package ru.ba6kir.tradelab.analysis.telegram;

import java.util.*;
import ru.ba6kir.tradelab.analysis.ranking.application.StockRankingService;
import ru.ba6kir.tradelab.analysis.reporting.application.StockAnalysisService;
import ru.ba6kir.tradelab.analysis.reporting.presentation.*;
import ru.ba6kir.tradelab.market.application.*;
import ru.ba6kir.tradelab.telegram.command.TelegramCommand;

public class StockResearchCommand implements TelegramCommand {
    private final String name;private final StockAnalysisService analysis;private final StockRankingService rankings;
    private final StockAnalysisFormatter reports=new StockAnalysisFormatter();private final RankingFormatter rankingText=new RankingFormatter();
    public StockResearchCommand(String name,StockAnalysisService analysis,StockRankingService rankings) { this.name=name;this.analysis=analysis;this.rankings=rankings; }
    @Override public String name() { return name; }
    @Override public boolean acceptsArguments() { return Set.of("analyze","why","top").contains(name); }
    @Override public String description() { return switch(name) {
        case "analyze" -> "отчёт по сохранённым данным: /analyze <тикер|UID>";case "why" -> "объяснения правил и вкладов: /why <тикер|UID>";
        case "top" -> "группы сопоставимых оценок: /top [banks|oil|metals]";case "rankstatus" -> "допуск, источники и свежесть рейтинга";
        default -> "ручное обновление рейтинга по сохранённым оценкам"; }; }
    @Override public String execute(List<String> args) {
        if (Set.of("analyze","why").contains(name) && args.size()!=1) return "Используйте /"+name+" <тикер|UID>.";
        if (name.equals("top") && args.size()>1 || !acceptsArguments() && !args.isEmpty()) return "Неверные аргументы. "+description();
        try {
            return switch(name) {
                case "analyze","why" -> reports.format(analysis.analyze(args.getFirst()),name.equals("why"));
                case "top" -> {String profile=args.isEmpty()?null:RankingFormatter.profileFilter(args.getFirst());yield rankingText.format(rankings.current(),profile,false);}
                case "rankstatus" -> rankingText.format(rankings.current(),null,true);
                default -> {var result=rankings.refresh();yield switch(result.outcome()) {
                    case "SUCCESS" -> "Рейтинг сохранён: #"+result.run().id()+"; допущено "+result.run().eligibleCount()+", исключено "+result.run().excludedCount()+". /top и /rankstatus показывают результат.";
                    case "COOLDOWN_OR_RUNNING" -> "Обновление уже выполняется или действует cooldown. Предыдущий снимок сохранён.";
                    default -> "Обновление не завершено [RANKING_GENERATION_FAILED]. Предыдущий рейтинг сохранён. Проверьте /rankstatus и доступность локальных данных.";};}
            };
        } catch (AmbiguousInstrumentException e) {
            var text=new StringBuilder("Тикер неоднозначен. Укажите точный UID:\n");e.matches().stream().limit(10).forEach(i -> text.append("/").append(name).append(" ").append(i.uid())
                    .append(" — ").append(ResearchText.safe(i.ticker())).append("/").append(ResearchText.safe(i.classCode())).append("\n"));return text.toString();
        } catch (MarketException e) {
            return e.reason()==MarketException.Reason.DISABLED?"Для поиска тикера нужен T-Invest. Укажите UID для чтения локальных данных.":"Данные инструмента недоступны ["+e.reason()+"].";
        } catch (IllegalArgumentException e) { return name.equals("top")?"Неизвестный фильтр. Используйте /top, /top banks, /top oil или /top metals.":"Некорректные аргументы команды."; }
        catch (RuntimeException e) { return "Исследовательский отчёт временно недоступен [INTERNAL]."; }
    }
}
