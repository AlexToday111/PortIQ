package ru.ba6kir.tradelab.analysis.reporting.presentation;

import java.util.*;
import ru.ba6kir.tradelab.analysis.ranking.application.StockRankingService;
import ru.ba6kir.tradelab.analysis.ranking.domain.RankingRun;
import static ru.ba6kir.tradelab.analysis.reporting.presentation.ResearchText.*;

public class RankingFormatter {
    public static String profileFilter(String filter) {
        return switch(filter.toLowerCase(Locale.ROOT)) {case "banks" -> "BANK";case "oil" -> "OIL_AND_GAS";case "metals" -> "METALS_AND_MINING";
            default -> throw new IllegalArgumentException("Unknown ranking filter");};
    }
    public String format(StockRankingService.View view,String profile,boolean detailed) {
        var run=view.run();var text=new StringBuilder("Рейтинг по фундаментальной модели\n");
        if (run==null) return text.append("Снимок ещё не создан. Настройте список наблюдения, сохраните оценки через /score и выполните /rankrefresh.\nПоследняя ошибка: ")
                .append(safe(view.state().lastFailure())).toString();
        text.append("Снимок #").append(run.id()).append("; сформирован: ").append(time(run.createdAt())).append("; действует до: ").append(time(run.expiresAt()))
                .append("\nМодель: ").append(safe(run.modelVersion())).append("; конфигурация: ").append(run.configurationFingerprint())
                .append("\nПолитика допуска: ").append(safe(run.policy().version())).append("; hash: ").append(run.policyFingerprint())
                .append("\nСписок наблюдения: ").append(run.universe().size()).append("; допущено бумаг: ").append(run.eligibleCount()).append("; исключено: ").append(run.excludedCount())
                .append("\nСтатус: ").append(view.configurationChanged()?"конфигурация/список изменились":view.expired()?"срок свежести истёк":"в пределах зафиксированной политики свежести")
                .append("\nПоследняя попытка обновления: ").append(time(view.state().lastAttempt())).append("; результат ошибки: ").append(safe(view.state().lastFailure())).append("\n");
        if (view.expired() || view.configurationChanged()) text.append("Текущий порядок не показывается: требуется /rankrefresh. Сохранённый снимок остаётся историческим.\n");
        else {
            var groups=new TreeMap<String,List<RankingRun.Entry>>();
            run.entries().stream().filter(e -> e.status()==RankingRun.Status.ELIGIBLE && (profile==null || profile.equals(e.profileId())))
                    .forEach(e -> groups.computeIfAbsent(e.comparisonGroup(),k -> new ArrayList<>()).add(e));
            if (groups.isEmpty()) text.append("Нет допущенных бумаг в выбранной области.\n");
            for (var group:groups.entrySet()) {
                var first=group.getValue().getFirst();text.append("\n").append(sector(first.sector())).append(" / ").append(first.profileId())
                        .append("\nГруппа совместимых определений и периодов: ").append(group.getKey().substring(group.getKey().lastIndexOf('/')+1,group.getKey().lastIndexOf('/')+13)).append("\n");
                group.getValue().stream().sorted(Comparator.comparing(RankingRun.Entry::rank)).forEach(e -> text.append(e.rank()).append(". ").append(safe(e.ticker())).append(" — ").append(safe(e.name()))
                        .append(": ").append(number(e.score())).append(" / 100; ").append(sector(e.sector())).append("; score:").append(e.scoreSnapshotId())
                        .append("; риски: ").append(e.riskCodes().isEmpty()?"не зафиксированы моделью":safe(String.join(",",e.riskCodes()))).append("\n"));
                if (group.getValue().size()==1) text.append("Одна допущенная бумага: это не подтверждённое превосходство над аналогами.\n");
            }
        }
        text.append("\nПричины исключения (весь список):\n");
        if (run.exclusionCounts().isEmpty()) text.append("Нет исключений.\n");
        run.exclusionCounts().forEach((code,count) -> text.append(count).append(": ").append(reason(code)).append("\n"));
        if (detailed) {
            text.append("\nТочный список и источники; universe hash: ").append(run.universeHash()).append("\n");
            for (var member:run.universe()) text.append("Запрос ").append(safe(member.identifier())).append(" → ").append(safe(member.instrumentUid())).append("; ").append(safe(member.resolutionReason())).append("\n");
            for (var e:run.entries()) {
                text.append("\n").append(safe(e.ticker())).append(" UID:").append(safe(e.instrumentUid())).append("; asset:").append(safe(e.assetUid())).append("; issuer:").append(safe(e.issuerKey()))
                        .append("\n").append(status(e.status().name())).append("; балл: ").append(number(e.score())).append("; score:").append(safe(e.scoreSnapshotId()))
                        .append("\nДата оценки: ").append(time(e.scoreEvaluatedAt())).append("; срез данных: ").append(time(e.dataCutoff()))
                        .append("\nДата сделки: ").append(time(e.quoteTime())).append("; получено: ").append(time(e.quoteFetchedAt())).append("; метаданные: ").append(time(e.metadataAt())).append("\n");
                e.reasons().forEach(reason -> text.append(reason(reason)).append("\n"));
                e.sourceReferences().forEach(ref -> text.append("Источник: ").append(safe(ref)).append("\n"));
            }
        } else {
            run.entries().stream().filter(e -> e.status()!=RankingRun.Status.ELIGIBLE && (profile==null || profile.equals(e.profileId()) || e.profileId()==null))
                    .forEach(e -> text.append("Исключён ").append(safe(e.ticker())).append(" [").append(e.status()).append("]: ")
                            .append(e.reasons().stream().map(ResearchText::reason).reduce((a,b) -> a+"; "+b).orElse("нет причины")).append("\n"));
            text.append("Полный список, даты и ссылки: /rankstatus.\n");
        }
        return text.append("\nМежотраслевое сравнение баллов не валидировано. Места присваиваются только внутри показанных групп. Для одного эмитента/актива выбран один допущенный UID; остальные классы исключены явно. Без подтверждённого issuer ID идентичность ограничена asset UID.\n")
                .append(LIMITATION).toString();
    }
}
