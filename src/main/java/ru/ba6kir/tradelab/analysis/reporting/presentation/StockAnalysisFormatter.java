package ru.ba6kir.tradelab.analysis.reporting.presentation;

import java.util.*;
import ru.ba6kir.tradelab.analysis.reporting.application.StockAnalysisService.Report;
import ru.ba6kir.tradelab.analysis.scoring.domain.model.ScoringResult;
import ru.ba6kir.tradelab.analysis.scoring.domain.profile.ScoringProfiles;
import ru.ba6kir.tradelab.analysis.domain.metric.*;
import ru.ba6kir.tradelab.dividends.domain.model.DividendEvent;
import static ru.ba6kir.tradelab.analysis.reporting.presentation.ResearchText.*;

public class StockAnalysisFormatter {
    public String format(Report report,boolean explanationOnly) {
        var s=report.snapshot();var i=s.instrument();var r=s.score();var c=r==null?s.classification():r.classification();
        var text=new StringBuilder(safe(i.ticker())+" — Анализ компании\n"+safe(i.name())+"\nUID: "+i.uid()+"\nAsset UID: "+safe(r==null?c==null?null:c.assetUid():r.assetUid()))
                .append("\nСектор: ").append(sector(c==null?null:c.sector().name())).append("; тип бизнеса: ").append(business(c==null?null:c.businessType().name()))
                .append("\nТорговые метаданные: ").append(safe(i.tradingStatus())).append("; API: ").append(Boolean.TRUE.equals(i.apiTradeAvailable())?"доступен по метаданным":"не подтверждён")
                .append("\nДата метаданных: ").append(time(i.updatedAt())).append(". Это не проверка текущего счёта или исполнения сделки.");
        if (!explanationOnly) {
            text.append("\n\nРыночные данные — отдельный локальный снимок:\n");var q=s.quote();
            if (q==null) text.append("Цена: нет данных.\n");
            else text.append("Последняя сделка: ").append(number(q.price())).append(" ").append(safe(q.currency())).append("; не исполнимая котировка.\nВремя сделки: ")
                    .append(time(q.quoteTime())).append("; получено: ").append(time(q.fetchedAt())).append("\nИсточник: ").append(safe(q.source())).append("\n");
            text.append("Свежесть цены: ").append(report.eligibility().reasons().stream().anyMatch(x -> x.contains("MARKET_PRICE") || x.startsWith("QUOTE_"))?"не подтверждена":"в пределах политики рейтинга");
            text.append("\n\nФакты: фундаментальные наблюдения ").append(r==null?"локального кэша":"сохранённой оценки #"+r.id()).append("\n");
            var profile=s.model()==null?ScoringProfiles.defaults().get(ScoringProfiles.resolve(c)):s.model().profiles().get(r.profileId());
            var codes=new LinkedHashSet<AnalysisMetric>();if (profile!=null) {profile.rules().forEach(rule -> codes.add(rule.metric()));codes.addAll(new TreeSet<>(profile.requiredContext()));codes.addAll(new TreeSet<>(profile.optionalContext()));}
            if (codes.isEmpty()) text.append("Нет подтверждённого применимого профиля; неприменимые показатели не оцениваются отрицательно.\n");
            for (var code:codes) {
                var rows=s.observations().stream().filter(o -> o.metricCode()==code).toList();
                if (rows.isEmpty()) text.append(code).append(": нет данных\n");
                for (var o:rows) text.append(code).append(" = ").append(number(o.value())).append(" ").append(unit(o.unit(),o.currency()))
                        .append("; ").append(o.validValue()?"значение проверено":"есть ограничения качества").append(" [").append(new TreeSet<>(o.qualityStatus())).append("]\n")
                        .append("Период: ").append(safe(o.periodStart())).append(" — ").append(safe(o.reportingEndDate())).append("; ").append(safe(o.reportingPeriod()))
                        .append("; дата показателя: ").append(time(o.providerAsOfDate())).append("; получено: ").append(time(o.fetchedAt()))
                        .append("\nИсточник/ID: ").append(safe(o.source())).append("/").append(safe(o.id())).append("; ").append(safe(o.qualityReason())).append("\n");
            }
            dividends(text,report);
        }
        text.append("\n\nОценка модели:\n");
        if (r==null) text.append("Нет сохранённой оценки. Для расчёта по локальным данным используйте /score <UID>.\n");
        else {
            text.append("Статус: ").append(status(r.status().name())).append(" [").append(r.status()).append("]\nОбщий балл: ").append(number(r.overallScore())).append(" / 100\n");
            for (var p:ScoringResult.Pillar.values()) text.append(switch(p) {case Q -> "Качество бизнеса";case F -> "Финансовая устойчивость";case V -> "Оценка стоимости";case D -> "Дивиденды";})
                    .append(": ").append(number(r.pillarScores().get(p))).append(" / 100; покрытие: ").append(number(r.coverage().get(p))).append(" из 1\n");
            text.append("Профиль: ").append(safe(r.profileId())).append("; модель: ").append(safe(r.modelVersion())).append("; конфигурация: ").append(safe(r.configurationVersion()))
                    .append("\nОценено: ").append(time(r.evaluatedAt())).append("; срез источников: ").append(time(r.dataCutoff()))
                    .append("\nСнимок: score:").append(r.id()).append("; input:").append(r.inputFingerprint()).append("\nКонфигурация: ").append(r.configurationFingerprint()).append("\n");
            for (String reason:r.eligibilityReasons()) text.append(reason(reason)).append("\n");
        }
        text.append("\nДопуск к рейтингу на дату отчёта: ").append(status(report.eligibility().status().name())).append("\n");
        report.eligibility().reasons().forEach(reason -> text.append(reason(reason)).append("\n"));
        text.append("\nИнтерпретация: факторы модели\n");
        if (report.explanations().isEmpty()) text.append("Нет сохранённых вкладов: объяснения не выдумываются.\n");
        report.explanations().forEach(e -> {
            text.append(switch(e.direction()) {case POSITIVE -> "Положительный фактор";case NEGATIVE -> "Ограничивающий фактор";case NEUTRAL -> "Нейтральный фактор";case UNAVAILABLE -> "Недоступная метрика";})
                    .append(" — ").append(e.metric()).append(": ").append(safe(e.russianText())).append("\nНаблюдение: ").append(number(e.observedValue())).append(" ").append(unit(e.unit(),e.currency()))
                    .append("; нормализованное: ").append(number(e.normalizedValue())).append("; правило: ").append(safe(e.ruleId())).append(" [").append(e.code()).append("]\n");
            if (explanationOnly) text.append("Пороговая кривая (значение → балл): ").append(safe(e.thresholdReference())).append("\nПериод: ").append(safe(e.periodStart())).append(" — ")
                    .append(safe(e.periodEnd())).append("; ").append(safe(e.observationType())).append("/").append(safe(e.reportingPeriod())).append("; дата показателя: ").append(time(e.asOf())).append("\n");
            text.append("Источник: ").append(safe(e.sourceReference())).append("; observation:").append(safe(e.observationId())).append("\n");
        });
        text.append("\nРиски и неопределённости:\n");
        if (r!=null) for (var risk:r.risks()) {
            var observation=r.input()==null?null:r.input().observations().stream().filter(o -> risk.observationIds().contains(o.id())).findFirst().orElse(null);
            text.append("Риск модели [").append(risk.severity()).append("/").append(risk.ruleId()).append("]: ")
                .append(riskText(risk.ruleId())).append("; метрика: ").append(safe(risk.metric())).append("; значение: ").append(number(risk.actualValue()))
                .append(observation==null?"":" "+unit(observation.unit(),observation.currency())+"; качество: "+new TreeSet<>(observation.qualityStatus()))
                .append("; наблюдения: ").append(safe(String.join(",",risk.observationIds()))).append("; дата: ").append(time(risk.observedAt()))
                .append("\nУсловие правила: ").append(safe(risk.condition())).append("\n");
        }
        s.warnings().forEach(w -> text.append(reason(w)).append("\n"));
        if (s.observations().stream().map(o -> Arrays.asList(o.periodStart(),o.reportingEndDate(),o.reportingPeriod())).distinct().count()>1)
            text.append("Наблюдения относятся к разным периодам; даты указаны отдельно. Их нельзя считать единой финансовой отчётностью.\n");
        return text.append("\nДата анализа: ").append(time(report.generatedAt())).append("\n").append(LIMITATION).toString();
    }
    private void dividends(StringBuilder text,Report report) {
        text.append("\nДивиденды — отдельный локальный снимок:\n");var view=report.snapshot().dividends();
        if (view==null) {text.append("Нет доступной истории.\n");return;}
        text.append("Покрытие запроса: ").append(safe(view.state().from())).append(" — ").append(safe(view.state().to())).append("; обновлено: ").append(time(view.state().lastSuccess()))
                .append("; свежесть: ").append(view.stale()?"устарело":"по сроку кэша").append("\nИсторическая доходность за 365 дней: ")
                .append(view.stale() || view.warning()!=null?"нет надёжных данных":number(view.trailingYield().value())+" %").append("; полученные деньги не подтверждаются.\n");
        if (view.warning()!=null) text.append(reason(view.warning())).append("\n");
        var history=view.events().stream().filter(o -> o.event().status(view.asOf())==DividendEvent.Status.HISTORICAL_RECORDED)
                .sorted(Comparator.comparing((DividendEvent.Observation o) -> o.event().recordDate()).reversed()).toList();
        var announced=view.events().stream().filter(o -> o.event().status(view.asOf())==DividendEvent.Status.ANNOUNCED
                && o.event().usable() && !o.ambiguous() && view.state().lastSuccess()!=null && !o.lastSeen().isBefore(view.state().lastSuccess())
                && o.event().instrumentUid().equals(report.snapshot().instrument().uid())).sorted(Comparator.comparing(o -> o.event().recordDate())).toList();
        text.append("Недавние зафиксированные события (не доказательство выплаты):\n");events(text,history);
        text.append("Объявленные будущие события источника (не прогноз; отмена возможна):\n");events(text,announced);
        text.append("Отсутствие события не доказывает отсутствие дивиденда; полнота зависит от указанного покрытия.\n");
    }
    private void events(StringBuilder text,List<DividendEvent.Observation> events) {
        if (events.isEmpty()) text.append("Нет доступных подтверждённых событий.\n");
        events.stream().limit(5).forEach(o -> text.append(safe(o.event().recordDate())).append(": ").append(number(o.event().amountPerShare())).append(" ").append(safe(o.event().currency()))
                .append("/акция; ").append(o.ambiguous()?"неоднозначно":"качество: "+new TreeSet<>(o.event().issues())).append("; источник: ").append(safe(o.event().source()))
                .append("; event:").append(safe(o.event().contentHash())).append("\n"));
        if (events.size()>5) text.append("Показано 5 из ").append(events.size()).append(" событий; полная история — /divhistory <UID>.\n");
    }
    private String riskText(String code) { return switch(code) {
        case "NEGATIVE_NET_INCOME" -> "В источнике отражён убыток; учитывайте качество исходного наблюдения";
        case "NEGATIVE_FCF" -> "В источнике отрицательный свободный денежный поток";
        case "HIGH_INDUSTRIAL_LEVERAGE" -> "Долговая нагрузка достигла порога профиля";
        case "STABILITY_RULE_FLOOR" -> "Подтверждённая метрика устойчивости получила ноль; оценка стоимости ограничена";
        case "EXTREME_DIVIDEND_YIELD" -> "Экстремальная историческая доходность требует проверки";
        case "EXTREME_OR_UNDEFINED_PE" -> "Мультипликатор P/E неопределён или достиг крайнего порога";
        case "CYCLICAL_VALUATION_CONTEXT" -> "Цикличность прибыли ограничивает интерпретацию мультипликаторов";
        case "METADATA_TRADING_AVAILABILITY" -> "Метаданные не подтверждают торговую доступность";
        case "UNRELIABLE_DIVIDEND_EVIDENCE" -> "Недостаточно надёжной дивидендной истории";
        case "OUTDATED_FINANCIAL_EVIDENCE" -> "Финансовые данные устарели";
        case "CUSTOM_PILLAR_POLICY" -> "Модель использует явно изменённый набор взвешенных столпов";
        default -> "Недостаточно подтверждённых данных; см. причины допуска и исходные наблюдения"; }; }
}
