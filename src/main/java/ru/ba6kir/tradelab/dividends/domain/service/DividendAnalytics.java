package ru.ba6kir.tradelab.dividends.domain.service;

import java.math.*;
import java.time.*;
import java.util.*;
import ru.ba6kir.tradelab.dividends.domain.model.DividendEvent;
import ru.ba6kir.tradelab.dividends.domain.model.DividendEvent.Observation;

/** Pure calculations. A result is an observed provider amount, not cash received. */
public class DividendAnalytics {
    public record ReferencePrice(BigDecimal amount, String currency, LocalDate date) {}
    public record Result(BigDecimal value, String currency, String reason) {
        public static Result unavailable(String reason) { return new Result(null,null,reason); }
    }
    public record YearStatistics(int year, long recordedEvents, Map<String,BigDecimal> observedAmounts) {}
    public Result perLot(DividendEvent event, Integer lot) {
        if (!event.usable() || lot==null || lot<=0) return Result.unavailable("INVALID_AMOUNT_OR_LOT");
        return new Result(event.amountPerShare().multiply(BigDecimal.valueOf(lot)),event.currency(),null);
    }
    public Result eventYield(Observation observation, ReferencePrice price, LocalDate asOf) {
        var e=observation.event();
        if (observation.ambiguous() || e.providerCreatedAt()==null || !e.usable() || !"Regular Cash".equalsIgnoreCase(e.dividendType()) || e.status(asOf)==DividendEvent.Status.CANCELLED
                || e.status(asOf)==DividendEvent.Status.UNKNOWN) return Result.unavailable("INELIGIBLE_EVENT");
        return calculateYield(e.amountPerShare(),e.currency(),price,asOf);
    }
    public Result trailingSum(List<Observation> events, LocalDate asOf, boolean coveredAndFresh) {
        if (!coveredAndFresh) return Result.unavailable("STALE_OR_INCOMPLETE_COVERAGE");
        // Unknown dates might belong to the period; do not optimistically ignore them.
        if (events.stream().anyMatch(o -> o.event().recordDate()==null)) return Result.unavailable("MISSING_RECORD_DATE");
        if (events.stream().map(o -> o.event().instrumentUid()).distinct().count()>1) return Result.unavailable("INSTRUMENT_MISMATCH");
        var distinct=new LinkedHashMap<String,Observation>();
        for (var o:events) {
            String key=o.event().source()+"|"+o.event().contentHash();
            distinct.merge(key,o,(a,b) -> a.ambiguous()?a:b);
        }
        var eligible=distinct.values().stream().filter(o -> o.event().recordDate().isAfter(asOf.minusDays(365))
                && !o.event().recordDate().isAfter(asOf))
                .filter(o -> o.event().status(asOf)!=DividendEvent.Status.CANCELLED).toList();
        if (eligible.isEmpty()) return Result.unavailable("NO_OBSERVED_EVENTS");
        if (eligible.stream().anyMatch(o -> o.ambiguous() || !o.event().usable() || o.event().providerCreatedAt()==null
                || !"Regular Cash".equalsIgnoreCase(o.event().dividendType())))
            return Result.unavailable("AMBIGUOUS_OR_INVALID_OBSERVATION");
        var currencies=eligible.stream().map(o -> o.event().currency()).distinct().toList();
        if (currencies.size()!=1) return Result.unavailable("CURRENCY_MISMATCH");
        return new Result(eligible.stream().map(o -> o.event().amountPerShare()).reduce(BigDecimal.ZERO,BigDecimal::add),currencies.getFirst(),null);
    }
    public Result trailingYield(Result sum, ReferencePrice price, LocalDate asOf) {
        return sum.value()==null ? sum : calculateYield(sum.value(),sum.currency(),price,asOf);
    }
    private Result calculateYield(BigDecimal amount,String currency,ReferencePrice price,LocalDate asOf) {
        if (price==null || price.amount()==null) return Result.unavailable("MISSING_PRICE");
        if (price.amount().signum()<=0) return Result.unavailable("INVALID_PRICE");
        if (price.currency()==null || !price.currency().equalsIgnoreCase(currency)) return Result.unavailable("CURRENCY_MISMATCH");
        if (price.date()==null || price.date().isAfter(asOf) || price.date().isBefore(asOf.minusDays(7)))
            return Result.unavailable("INVALID_OR_OLD_PRICE_DATE");
        return new Result(amount.divide(price.amount(),12,RoundingMode.HALF_UP).multiply(BigDecimal.valueOf(100)).setScale(6,RoundingMode.HALF_UP),"%",null);
    }
    public List<YearStatistics> statistics(List<Observation> events,LocalDate from,LocalDate to) {
        var results=new ArrayList<YearStatistics>();
        for (int year=from.getYear();year<=to.getYear();year++) {
            final int y=year;
            var rows=events.stream().filter(o -> o.event().recordDate()!=null && o.event().recordDate().getYear()==y
                    && !o.event().recordDate().isBefore(from) && !o.event().recordDate().isAfter(to)
                    && o.event().status(to)==DividendEvent.Status.HISTORICAL_RECORDED).toList();
            var sums=new TreeMap<String,BigDecimal>();
            rows.stream().filter(o -> !o.ambiguous() && o.event().usable() && o.event().providerCreatedAt()!=null
                            && "Regular Cash".equalsIgnoreCase(o.event().dividendType()))
                    .forEach(o -> sums.merge(o.event().currency(),o.event().amountPerShare(),BigDecimal::add));
            results.add(new YearStatistics(y,rows.size(),Collections.unmodifiableMap(sums)));
        }
        return List.copyOf(results);
    }
    /** Comparable only by explicit regular cash/regularity, consecutive annual record years and currency. */
    public Result latestComparableChange(List<Observation> observations,LocalDate asOf) {
        var rows=observations.stream().filter(o -> o.event().recordDate()!=null && o.event().status(asOf)==DividendEvent.Status.HISTORICAL_RECORDED)
                .sorted(Comparator.comparing((Observation o) -> o.event().recordDate()).reversed()).toList();
        if (rows.size()<2) return Result.unavailable("NOT_COMPARABLE");
        var newest=rows.getFirst(); var previous=rows.get(1);
        if (rows.stream().filter(o -> o.event().recordDate().getYear()==newest.event().recordDate().getYear()).count()!=1
                || rows.stream().filter(o -> o.event().recordDate().getYear()==previous.event().recordDate().getYear()).count()!=1)
            return Result.unavailable("MULTIPLE_EVENTS_IN_YEAR");
        return comparableChange(previous,newest,asOf);
    }
    public Result comparableChange(Observation previous,Observation current,LocalDate asOf) {
        var a=previous.event(); var b=current.event();
        if (previous.ambiguous() || current.ambiguous() || a.providerCreatedAt()==null || b.providerCreatedAt()==null || !a.usable() || !b.usable()
                || a.status(asOf)!=DividendEvent.Status.HISTORICAL_RECORDED || b.status(asOf)!=DividendEvent.Status.HISTORICAL_RECORDED
                || !Objects.equals(a.instrumentUid(),b.instrumentUid()) || !Objects.equals(a.currency(),b.currency())
                || !"Regular Cash".equals(a.dividendType()) || !a.dividendType().equals(b.dividendType())
                || !"Annual".equals(a.regularity()) || !a.regularity().equals(b.regularity())
                || b.recordDate().getYear()!=a.recordDate().getYear()+1) return Result.unavailable("NOT_COMPARABLE");
        return new Result(b.amountPerShare().subtract(a.amountPerShare()).divide(a.amountPerShare(),12,RoundingMode.HALF_UP)
                .multiply(BigDecimal.valueOf(100)).setScale(6,RoundingMode.HALF_UP),"%",null);
    }
}
