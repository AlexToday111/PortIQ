package ru.ba6kir.tradelab.analysis.telegram;

import java.math.BigDecimal;
import java.util.*;
import ru.ba6kir.tradelab.telegram.command.TelegramCommand;
import ru.ba6kir.tradelab.market.application.AmbiguousInstrumentException;
import ru.ba6kir.tradelab.fundamentals.application.service.FundamentalsService;
import ru.ba6kir.tradelab.analysis.application.service.*;
import ru.ba6kir.tradelab.analysis.domain.metric.*;

public class AnalysisCommand implements TelegramCommand {
    private final String name; private final FinancialMetricService metrics; private final SectorComparisonService comparisons;
    public AnalysisCommand(String name,FinancialMetricService metrics,SectorComparisonService comparisons) { this.name=name;this.metrics=metrics;this.comparisons=comparisons; }
    @Override public String name() { return name; }
    @Override public boolean acceptsArguments() { return true; }
    @Override public String description() {
        return switch(name) { case "sector" -> "сектор и бизнес-модель: /sector <тикер|UID>";
            case "metrics" -> "финансовые метрики и качество: /metrics <тикер|UID>";
            default -> "сопоставимые отраслевые наблюдения: /compare <тикер|UID>"; };
    }
    @Override public String execute(List<String> arguments) {
        if (arguments.size()!=1) return "Используйте /"+name+" <тикер|UID>.";
        try { return switch(name) {
            case "sector" -> sector(metrics.sector(arguments.getFirst()));
            case "metrics" -> metrics(metrics.get(arguments.getFirst()));
            default -> compare(comparisons.compare(arguments.getFirst()));
        }; } catch (AmbiguousInstrumentException e) {
            var text=new StringBuilder("Тикер неоднозначен. Укажите UID:");
            e.matches().stream().limit(10).forEach(s -> text.append("\n/").append(name).append(" ").append(s.uid()).append(" — ").append(safe(s.ticker())).append("/").append(safe(s.classCode())));
            return text.toString();
        } catch (RuntimeException e) {
            String code=FundamentalsService.code(e);
            return code.equals("DISABLED")?"T-Invest не настроен.":"Финансовый анализ недоступен ["+code+"].";
        }
    }
    private String sector(FinancialMetricService.SectorView v) {
        var c=v.classification();
        return safe(v.input().instrument().ticker())+" — "+safe(v.input().instrument().name())+"\n"
                +"Компания/актив: "+(v.input().identity()==null?"связь не подтверждена":safe(v.input().identity().assetName()))
                +"\nAsset UID: "+(c==null?"не установлен":c.assetUid())+"\nСектор: "+(c==null?"UNKNOWN":c.sector())
                +"\nБизнес-тип: "+(c==null?"UNKNOWN":c.businessType())+"\nИсточник классификации: "+(c==null?"UNKNOWN":c.source())
                +"\nПроверка: "+(v.fresh()?"VERIFIED (источник/ручная запись)":"UNKNOWN/STALE")
                +(c==null?"":"\nПроверено UTC: "+c.verifiedAt()+"; override: "+c.override()+"\nИдентификатор эмитента: "+safe(c.issuerKey())
                        +"\nОснование: "+safe(c.evidence())+"\nСектор провайдера: "+safe(c.providerSector()));
    }
    private String metrics(FinancialMetricService.View v) {
        var text=new StringBuilder(sector(v.sector())).append("\n\nГотовность: ").append(v.readiness()).append("; профиль: ").append(v.profile()==null?"не поддерживается":v.profile().name());
        var snapshot=v.sector().input().snapshot();
        if (snapshot!=null) text.append("\nФискальный контекст снимка (не даты каждой метрики): ").append(snapshot.periodStart()).append(" — ").append(snapshot.periodEnd());
        var available=v.observations().stream().filter(o -> o.value()!=null).toList();
        text.append("\n\nДоступные наблюдения:\n");
        if (available.isEmpty()) text.append("Нет доступных значений.\n");
        available.stream().limit(40).forEach(o -> text.append(o.metricCode()).append(" [").append(o.origin()).append("] = ")
                .append(number(o.value())).append(" ").append(o.unit()).append(o.currency()==null?"":" "+o.currency())
                .append("; ").append(o.observationType()).append("/").append(safe(o.reportingPeriod())).append("\nПериод: ")
                .append(o.periodStart()).append(" — ").append(o.reportingEndDate()).append("; as-of UTC: ").append(o.providerAsOfDate())
                .append("; получено UTC: ").append(o.fetchedAt()).append("\nИсточник: ").append(safe(o.source()))
                .append("; качество: ").append(new TreeSet<>(o.qualityStatus())).append("; ").append(safe(o.qualityReason()))
                .append("; применимость: ").append(v.profile()==null?"UNKNOWN":v.profile().required().contains(o.metricCode())?"REQUIRED":v.profile().optional().contains(o.metricCode())?"OPTIONAL":"вне профиля")
                .append(o.origin()==FinancialObservation.Origin.DERIVED?"\nФормула: "+safe(o.methodology())+"; входов: "+o.inputIds().size():"").append("\n\n"));
        text.append("Недоступные варианты:\n");
        v.observations().stream().filter(o -> o.value()==null).forEach(o -> text.append(o.metricCode()).append("/").append(o.origin()).append(": ").append(safe(o.qualityReason())).append("\n"));
        v.warnings().forEach(w -> text.append("Предупреждение: ").append(safe(w)).append("\n"));
        return text+"\nДата расчёта UTC: "+v.calculatedAt()+". Неизвестные даты/шкалы не восстанавливаются. Дивидендные наблюдения не подтверждают зачисление денег. Рейтинг и рекомендации не рассчитываются.";
    }
    private String compare(SectorComparisonService.View v) {
        var text=new StringBuilder(sector(v.analysis().sector())).append("\n\nГотовность анализа: ").append(v.analysis().readiness())
                .append("\nСравнение с сохранёнными наблюдениями классифицированных эмитентов (без загрузки данных аналогов).\n");
        if (v.comparisons().isEmpty()) text.append("UNSUPPORTED_PROFILE: нет подтверждённого профиля.\n");
        for (var r:v.comparisons().stream().limit(20).toList()) {
            var o=r.subject();
            text.append("\n").append(o.metricCode()).append("/").append(o.origin()).append(": ").append(number(o.value())).append(" ").append(o.unit()).append(o.currency()==null?"":" "+o.currency())
                    .append("; медиана: ").append(number(r.median())).append("; валидных аналогов: ").append(r.validPeers())
                    .append("; минимум для позиции: ").append(r.minimumPeers()).append("\n").append(r.status()).append("; ")
                    .append(o.observationType()).append("/").append(safe(o.reportingPeriod())).append("; ").append(o.periodStart()).append(" — ").append(o.reportingEndDate())
                    .append("; as-of UTC: ").append(o.providerAsOfDate()).append("; получено UTC: ").append(o.fetchedAt());
            if (r.relativePositionPercent()!=null) text.append("\nОтносительная позиция по значению (midrank): ").append(number(r.relativePositionPercent())).append("%; без оценки привлекательности");
            else if (r.median()!=null) text.append("\nМедиана описательная; процентиль недоступен для малой выборки.");
            var exclusions=new TreeMap<String,Long>(); r.universe().stream().filter(p -> p.exclusion()!=null).forEach(p -> exclusions.merge(p.exclusion(),1L,Long::sum));
            if (!exclusions.isEmpty()) text.append("\nИсключения активов: ").append(exclusions);
            text.append("\n");
        }
        v.comparisons().stream().filter(r -> !r.universe().isEmpty()).findFirst().ifPresent(r -> {
            text.append("\nВселенная аналогов (оценка допустимости отдельно для каждой метрики):");
            r.universe().stream().limit(20).forEach(p -> text.append("\n").append(p.assetUid()).append("/").append(safe(p.issuerKey())).append("; получено UTC: ").append(p.fetchedAt()));
            if (r.universe().size()>20) text.append("\nПоказаны 20 из ").append(r.universe().size()).append(" активов.");
            text.append("\n");
        });
        if (v.comparisons().size()>20) text.append("Показаны первые 20 вариантов метрик.\n");
        v.warnings().forEach(w -> text.append("Предупреждение: ").append(safe(w)).append("\n"));
        return text+"\nРасчёт UTC: "+v.analysis().calculatedAt()+". Непроверенные периоды, валюты, определения и малые группы не дают устойчивой относительной позиции. Общий балл не рассчитывается.";
    }
    private static String number(BigDecimal n) { return n==null?"недоступно":n.stripTrailingZeros().toPlainString(); }
    private static String safe(String s) { return s==null?"не установлено":s.replaceAll("[\\p{Cntrl}]"," ").substring(0,Math.min(250,s.length())); }
}
