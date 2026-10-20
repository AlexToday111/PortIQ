package ru.ba6kir.tradelab.analysis.scoring.telegram;

import java.math.BigDecimal;
import java.util.*;
import ru.ba6kir.tradelab.telegram.command.TelegramCommand;
import ru.ba6kir.tradelab.market.application.*;
import ru.ba6kir.tradelab.analysis.scoring.application.service.FundamentalScoringService;
import ru.ba6kir.tradelab.analysis.scoring.domain.model.ScoringResult;

public class ScoreCommand implements TelegramCommand {
    private final String name; private final FundamentalScoringService service; private final InstrumentService instruments;
    public ScoreCommand(String name,FundamentalScoringService service,InstrumentService instruments) { this.name=name;this.service=service;this.instruments=instruments; }
    @Override public String name() { return name; }
    @Override public boolean acceptsArguments() { return true; }
    @Override public String description() {
        return switch(name) { case "score" -> "экспериментальная оценка по сохранённым данным: /score <тикер|UID>";
            case "scoreinfo" -> "правила и причины допуска, без сохранения: /scoreinfo <тикер|UID>";
            default -> "история оценок и версий модели: /scorehistory <тикер|UID>"; };
    }
    @Override public String execute(List<String> args) {
        if (args.size()!=1) return "Используйте /"+name+" <тикер|UID>.";
        try {
            // Optional ticker metadata resolution is separate from the local snapshot/calculation use case.
            String value=args.getFirst(); String uid=value.length()==36?InstrumentService.validateUid(value):instruments.resolve(value).uid();
            if (name.equals("scorehistory")) return history(service.getScoreHistory(uid));
            var result=name.equals("scoreinfo")?service.getScoringEligibility(uid):service.evaluateInstrument(uid);
            return format(result,name.equals("scoreinfo"));
        } catch (AmbiguousInstrumentException e) {
            var text=new StringBuilder("Тикер неоднозначен. Укажите UID:");
            e.matches().stream().limit(10).forEach(s -> text.append("\n/").append(name).append(" ").append(s.uid()).append(" — ").append(safe(s.ticker())).append("/").append(safe(s.classCode())));
            return text.toString();
        } catch (MarketException e) { return "Скоринг: "+(e.reason()==MarketException.Reason.DISABLED?"T-Invest не настроен для поиска тикера; для локальных данных укажите UID":e.reason().name())+"."; }
        catch (RuntimeException e) { return "Скоринг временно недоступен [INTERNAL]."; }
    }
    private String format(ScoringResult r,boolean detail) {
        var text=new StringBuilder(safe(r.name())+"\nФундаментальная оценка\nUID: "+r.instrumentUid()+"; asset: "+r.assetUid())
                .append("\nСектор: ").append(r.classification()==null?"UNKNOWN":r.classification().sector())
                .append("; бизнес-тип: ").append(r.classification()==null?"UNKNOWN":r.classification().businessType())
                .append("\nПрофиль: ").append(r.profileId()).append("; модель: ").append(r.modelVersion()).append("; конфигурация: ").append(r.configurationVersion())
                .append("\nСтатус: ").append(r.status()).append("\nИтог: ").append(number(r.overallScore()));
        for (var p:ScoringResult.Pillar.values()) text.append("\n").append(p).append(": ").append(number(r.pillarScores().get(p)))
                .append("; покрытие веса метрик: ").append(number(r.coverage().get(p)));
        text.append("\nОценка UTC: ").append(r.evaluatedAt()).append("; последняя метка получения входных наблюдений UTC: ").append(r.dataCutoff());
        if (!r.eligibilityReasons().isEmpty()) text.append("\nПричины недопуска: ").append(String.join(", ",r.eligibilityReasons()));
        r.risks().stream().sorted(Comparator.comparing(ScoringResult.RiskFlag::severity).reversed()).limit(detail?12:6)
                .forEach(f -> text.append("\nРиск ").append(f.severity()).append(" [").append(f.ruleId()).append("]: ").append(safe(f.explanation()))
                        .append("; значение: ").append(number(f.actualValue())).append("; условие: ").append(safe(f.condition())).append("; наблюдение UTC: ").append(f.observedAt()));
        if (detail) {
            text.append("\n\nВклады метрик (пустое значение не заменяется нулём):");
            r.contributions().forEach(c -> {
                text.append("\n").append(c.pillar()).append("/").append(c.metric()).append(" [").append(c.ruleId()).append("]")
                    .append("; вход: ").append(safe(c.observationId())).append("; нормализовано: ").append(number(c.normalizedValue()))
                    .append("; балл: ").append(number(c.metricScore())).append("; вес внутри компонента: ").append(number(c.metricWeight()))
                    .append("; вес компонента: ").append(number(c.pillarWeight())).append("; вклад внутри компонента: ").append(number(c.contribution()))
                    .append("; ").append(c.status()).append("; ").append(safe(c.reason())).append("; ").append(safe(c.methodology()));
                if (r.input()!=null) r.input().observations().stream().filter(o -> Objects.equals(o.id(),c.observationId())).findFirst().ifPresent(o ->
                        text.append("\nИсточник: ").append(safe(o.source())).append("/").append(o.origin()).append("; единица: ").append(o.unit())
                        .append(o.currency()==null?"":" "+o.currency()).append("; качество: ").append(new TreeSet<>(o.qualityStatus()))
                        .append("; ").append(safe(o.qualityReason())).append("; период: ").append(o.observationType()).append("/").append(safe(o.reportingPeriod()))
                        .append(" ").append(o.periodStart()).append(" — ").append(o.reportingEndDate()).append("; получено UTC: ").append(o.fetchedAt()));
            });
            text.append("\nХэш конфигурации: ").append(r.configurationFingerprint()).append("\nХэш входов: ").append(r.inputFingerprint())
                    .append("\nСнимки: ").append(r.inputSnapshotIds()).append("\nРежим проверки без сохранения.");
        } else if (r.id()==null) text.append("\nСнимок оценки не сохранён.");
        return text+"\nЭкспериментальный эвристический индикатор. Не вероятность доходности и не инвестиционная рекомендация. Пустые данные не получают условный балл.";
    }
    private String history(List<ScoringResult> rows) {
        if (rows.isEmpty()) return "История оценок отсутствует. /score <UID> оценит сохранённые данные; при недостатке сведений итог останется недоступен.";
        var text=new StringBuilder("История оценок (последние 20):\n");
        rows.forEach(r -> text.append("\n").append(r.evaluatedAt()).append("; ").append(r.profileId()).append("; модель ").append(r.modelVersion())
                .append("/").append(r.configurationVersion()).append("; config ").append(r.configurationFingerprint(),0,12)
                .append("; ").append(r.status()).append("; итог: ").append(number(r.overallScore())));
        return text+"\nВерсии и конфигурации с разными правилами напрямую не сопоставляются. Это не прогноз доходности.";
    }
    private static String number(BigDecimal n) { return n==null?"недоступно":n.stripTrailingZeros().toPlainString(); }
    private static String safe(String s) { return s==null?"не указано":s.replaceAll("[\\p{Cntrl}]"," ").substring(0,Math.min(300,s.length())); }
}
