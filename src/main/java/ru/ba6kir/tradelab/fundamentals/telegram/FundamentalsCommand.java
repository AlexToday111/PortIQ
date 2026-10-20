package ru.ba6kir.tradelab.fundamentals.telegram;

import java.util.*;
import ru.ba6kir.tradelab.telegram.command.TelegramCommand;
import ru.ba6kir.tradelab.market.application.AmbiguousInstrumentException;
import ru.ba6kir.tradelab.fundamentals.application.service.*;
import ru.ba6kir.tradelab.fundamentals.domain.model.*;

public class FundamentalsCommand implements TelegramCommand {
    private final String name;
    private final FundamentalsService service;
    public FundamentalsCommand(String name,FundamentalsService service) { this.name=name; this.service=service; }
    @Override public String name() { return name; }
    @Override public boolean acceptsArguments() { return true; }
    @Override public String description() {
        return switch (name) {
            case "fundamentals" -> "фундаментальные показатели: /fundamentals <тикер|UID>";
            case "reports" -> "календарь отчётности: /reports <тикер|UID>";
            case "fundrefresh" -> "обновить фундаментальные данные: /fundrefresh <тикер|UID>";
            default -> "качество и свежесть: /fundstatus <тикер|UID>";
        };
    }
    @Override public String execute(List<String> arguments) {
        if (arguments.size()!=1) return "Используйте /"+name+" <тикер|UID>.";
        try {
            return switch (name) {
                case "fundamentals" -> fundamentals(service.getLatest(arguments.getFirst()));
                case "reports" -> reports(service.reports(arguments.getFirst()));
                case "fundrefresh" -> refresh(service.refresh(arguments.getFirst()));
                default -> status(service.status(arguments.getFirst()));
            };
        } catch (AmbiguousInstrumentException e) {
            var text=new StringBuilder("Тикер неоднозначен. Выберите UID:");
            e.matches().stream().limit(10).forEach(s -> text.append("\n/").append(name).append(" ").append(s.uid())
                    .append(" — ").append(safe(s.ticker())).append(" / ").append(safe(s.classCode())));
            return text.toString();
        } catch (RuntimeException e) { return "Фундаментальные данные: "+error(FundamentalsService.code(e))+"."; }
    }
    private String fundamentals(FundamentalAnalysisView view) {
        var text=new StringBuilder(safe(view.instrument().ticker())+" — "+safe(view.instrument().name())+"\nФундаментальные показатели\n");
        if (view.snapshot()==null) return text+"Снимок отсутствует. "+error(view.warning()==null?"EMPTY_RESPONSE":view.warning());
        var byCode=new EnumMap<MetricCode,FundamentalAnalysisView.Assessment>(MetricCode.class);
        view.metrics().forEach(m -> byCode.put(m.observation().code(),m));
        for (var code:MetricCode.values()) {
            if (code==MetricCode.MARKET_CAPITALIZATION) text.append("\nОценка стоимости\n");
            if (code==MetricCode.ROE) text.append("\nРентабельность\n");
            if (code==MetricCode.TOTAL_DEBT) text.append("\nФинансовая устойчивость\n");
            if (code==MetricCode.REVENUE) text.append("\nФинансовые результаты\n");
            if (code==MetricCode.DIVIDEND_YIELD) text.append("\nДополнительно\n");
            var assessment=byCode.get(code);
            if (assessment==null) continue;
            var metric=assessment.observation();
            text.append(code.label()).append(": ").append(metric.value()==null?"недоступно":metric.value().stripTrailingZeros().toString());
            if (metric.value()!=null) text.append(unit(metric));
            text.append(" [").append(String.join(",",assessment.qualities().stream().map(Enum::name).sorted().toList())).append("]\n");
        }
        return text+"\n"+provenance(view)+"\nДоступно значений: "+view.availableCount()+"; отсутствует/не поддерживается: "+view.missingCount()
                +"; сомнительных: "+view.suspectCount()
                +"\nSUSPECT: ноль без признака присутствия, неуточнённая шкала ставки, валюта или иной контекст."
                +"\nСтавки со знаком «шкала ?» сохранены как у провайдера, без пересчёта в %."
                +warnings(view);
    }
    private static String unit(MetricValue value) {
        return switch (value.unit()) {
            case MONEY -> " "+safe(value.currency())+" (ед. провайдера)";
            case MONEY_PER_SHARE -> " "+safe(value.currency())+"/акцию";
            case MULTIPLE -> "x";
            case RATIO -> " (коэффициент)";
            case UNVERIFIED_RATE -> " (шкала ?)";
            case COUNT -> " шт.";
        };
    }
    private String status(FundamentalAnalysisView view) {
        return safe(view.instrument().ticker())+" — статус фундаментальных данных\n"
                +"Instrument UID: "+view.instrument().uid()+"\nAsset UID: "+(view.identity()==null?"надёжная связь ещё не установлена":view.identity().assetUid())
                +(view.identity()==null?"":"\nСвязь подтверждена (UTC): "+view.identity().verifiedAt())
                +"\nПоследний успешный запрос (UTC): "+stamp(view.requestState()==null?null:view.requestState().lastSuccess())
                +"\nПервый снимок текущей версии (UTC): "+stamp(view.snapshot()==null?null:view.snapshot().fetchedAt())
                +"\nПоследнее подтверждение (UTC): "+stamp(view.snapshot()==null?null:view.snapshot().lastFetchedAt())
                +"\nДоступно: "+view.availableCount()+"; отсутствует/не поддерживается: "+view.missingCount()+"; сомнительных: "+view.suspectCount()
                +"\nСвежесть получения: "+(view.snapshot()==null?"нет данных":view.retrievalStale()?"STALE":"в пределах TTL")
                +"\nВозраст финансового периода: "+(view.snapshot()==null || view.snapshot().periodEnd()==null?"неизвестен":view.reportingStale()?"STALE":"в пределах порога")
                +warnings(view);
    }
    private String refresh(FundamentalsService.SyncResult result) {
        String status=switch (result.outcome()) {
            case CHANGED -> "Успех: сохранена новая версия.";
            case UNCHANGED -> "Успех: данные не изменились, получение подтверждено.";
            case COOLDOWN -> "Повторное обновление ограничено cooldown.";
            case FAILED -> "Не удалось обновить: "+error(result.error())+".";
        };
        var view=result.view();
        return status+"\nИзменённых записей метрик: "+result.updatedMetrics()+"\nОтсутствует/не поддерживается: "+view.missingCount()
                +"; сомнительных: "+view.suspectCount()+"\nИсточник: T-Invest\nПоследнее получение (UTC): "
                +stamp(view.snapshot()==null?null:view.snapshot().lastFetchedAt());
    }
    private String reports(FundamentalsService.ReportsView view) {
        var text=new StringBuilder(safe(view.instrument().ticker())+" — календарь отчётности\n");
        view.batch().events().stream().sorted(Comparator.comparing(FinancialReportEvent::reportDate,Comparator.nullsLast(Comparator.naturalOrder())))
                .limit(10).forEach(event -> text.append("\nПубликация по календарю (UTC): ").append(stamp(event.reportDate()))
                        .append("\nПериод: ").append(event.reportingYear()==null?"неизвестен":event.reportingYear()).append(" / ")
                        .append(safe(event.periodType())).append(" / ").append(event.periodNumber()==null?"номер неизвестен":event.periodNumber()).append("\n"));
        if (view.batch().events().isEmpty()) text.append("Событий в запрошенном интервале нет.\n");
        if (view.batch().events().size()>10) text.append("Показаны первые 10 событий.\n");
        return text+"\nПолучено (UTC): "+view.batch().fetchedAt()+"; "+(view.cached()?"кэш":"свежий запрос")+(view.stale()?"; STALE":"")
                +"\nИсточник: T-Invest; окно: "+view.batch().from()+" — "+view.batch().to()
                +(view.warning()==null?"":"\nНовый запрос не выполнен: "+error(view.warning()))
                +"\nЭто календарные события, а не финансовые отчёты и не подтверждение публикации. Стандарт отчётности провайдер не указал.";
    }
    private static String provenance(FundamentalAnalysisView view) {
        var snapshot=view.snapshot();
        return "Источник: T-Invest ("+snapshot.source()+"); "+(view.cached()?"кэш":"свежий запрос")
                +"\nAsset UID: "+snapshot.assetUid()+"\nВпервые получено (UTC): "+snapshot.fetchedAt()
                +"\nПодтверждено (UTC): "+snapshot.lastFetchedAt()+"\nФискальный период провайдера: "+stamp(snapshot.periodStart())+" — "+stamp(snapshot.periodEnd())
                +"\nДата актуальности/публикации данных: "+stamp(snapshot.providerAsOf())
                +"\nTTM/MRQ/FY — горизонты полей; фискальные даты не назначаются всем метрикам автоматически.";
    }
    private static String warnings(FundamentalAnalysisView view) {
        return (view.retrievalStale()?"\nSTALE: истёк срок свежести получения.":"")
                +(view.reportingStale()?"\nSTALE: финансовый период старше порога.":"")
                +(view.mappingStale()?"\nSTALE: связь с активом требует повторной проверки.":"")
                +(view.warning()==null?"":"\nПредупреждение: "+error(view.warning()));
    }
    private static String stamp(java.time.Instant value) { return value==null?"неизвестно":value.toString(); }
    private static String safe(String value) {
        if (value==null || value.isBlank()) return "не указано";
        String clean=value.replaceAll("[\\p{Cc}\\p{Cf}]"," ");
        return clean.substring(0,clean.offsetByCodePoints(0,Math.min(120,clean.codePointCount(0,clean.length()))));
    }
    private static String error(String code) {
        if (code==null) return "данные не получены";
        return switch (code) {
            case "DISABLED" -> "доступ к T-Invest не настроен (INVEST_API_ENABLED=false)";
            case "INVALID_INPUT" -> "некорректный тикер или UID";
            case "NOT_FOUND" -> "акция не найдена";
            case "MAPPING_UNAVAILABLE" -> "нет надёжной связи инструмента с активом";
            case "EMPTY_RESPONSE" -> "провайдер не вернул фундаментальные показатели";
            case "INVALID_DATA" -> "некорректные данные провайдера";
            case "AUTHENTICATION" -> "ошибка авторизации; проверьте production read-only токен";
            case "RATE_LIMIT" -> "достигнут лимит запросов T-Invest";
            case "TIMEOUT" -> "T-Invest не ответил вовремя";
            case "UNAVAILABLE" -> "T-Invest временно недоступен";
            case "COOLDOWN" -> "повторите после cooldown";
            default -> "внутренняя ошибка получения или сохранения данных";
        };
    }
}
