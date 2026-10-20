package ru.ba6kir.tradelab.dividends.application.service;

import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ru.ba6kir.tradelab.market.application.*;
import ru.ba6kir.tradelab.market.application.price.MarketPriceService;
import ru.ba6kir.tradelab.market.domain.StockInstrument;
import ru.ba6kir.tradelab.dividends.application.port.*;
import ru.ba6kir.tradelab.dividends.domain.model.DividendEvent;
import ru.ba6kir.tradelab.dividends.domain.model.DividendEvent.Observation;
import ru.ba6kir.tradelab.dividends.domain.service.DividendAnalytics;

public class DividendService {
    private static final Logger log=LoggerFactory.getLogger(DividendService.class);
    private final InstrumentService instruments;
    private final MarketPriceService prices;
    private final DividendProvider provider;
    private final DividendStore store;
    private final Clock clock;
    private final Duration ttl,cooldown;
    private final int lookback,forward;
    private final List<String> tracked;
    private final boolean enabled;
    private final DividendAnalytics analytics=new DividendAnalytics();
    public record View(StockInstrument instrument,List<Observation> events,DividendStore.State state,
            LocalDate asOf,boolean stale,String warning,DividendAnalytics.Result trailingSum,
            DividendAnalytics.Result trailingYield,DividendAnalytics.ReferencePrice price) {}
    public record SyncResult(String outcome,DividendStore.Counts counts,String error,View view) {}
    public record CalendarRow(String ticker,LocalDate date,String kind,Observation observation) {}
    public record CalendarView(LocalDate from,LocalDate to,List<CalendarRow> rows,List<String> warnings,int omitted) {}
    public DividendService(InstrumentService instruments,MarketPriceService prices,DividendProvider provider,DividendStore store,
            Clock clock,Duration ttl,Duration cooldown,int lookback,int forward,List<String> tracked,boolean enabled) {
        this.instruments=instruments; this.prices=prices; this.provider=provider; this.store=store; this.clock=clock;
        this.ttl=ttl; this.cooldown=cooldown; this.lookback=lookback; this.forward=forward; this.tracked=List.copyOf(tracked); this.enabled=enabled;
    }
    public View get(String identifier,int years) {
        if (years!=1 && years!=3 && years!=5) throw new MarketException(MarketException.Reason.INVALID_INPUT);
        var stock=resolve(identifier); var state=store.state(stock.uid()); var today=today();
        int back=Math.max(years,lookback);
        if (covers(state,today.minusYears(back),today.plusDays(forward)) && fresh(state)) return view(stock,null);
        return sync(stock,back).view();
    }
    public SyncResult refresh(String identifier) { return sync(resolve(identifier),lookback); }
    private StockInstrument resolve(String identifier) {
        if (!enabled) throw new MarketException(MarketException.Reason.DISABLED);
        return instruments.resolve(identifier);
    }
    private SyncResult sync(StockInstrument stock,int years) {
        var attempt=clock.instant().truncatedTo(ChronoUnit.MICROS);
        if (!store.tryStart(stock.uid(),attempt,cooldown)) return new SyncResult("COOLDOWN",null,null,view(stock,"COOLDOWN"));
        var from=today().minusYears(years); var to=today().plusDays(forward);
        try {
            // Official docs do not specify inclusive endpoints. Pad both bounds and filter UTC dates locally.
            // No database transaction is open while the adapter performs its RPC/retries.
            var response=provider.fetchDividends(stock.uid(),from.minusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant(),
                    to.plusDays(2).atStartOfDay(ZoneOffset.UTC).toInstant());
            if (response==null || response.size()>10000) throw new MarketException(MarketException.Reason.UNAVAILABLE);
            if (response.stream().anyMatch(e -> !stock.uid().equals(e.instrumentUid()))) throw new MarketException(MarketException.Reason.UNAVAILABLE);
            var events=response.stream().filter(e -> e.recordDate()==null || (!e.recordDate().isBefore(from) && !e.recordDate().isAfter(to))).toList();
            var counts=store.save(stock.uid(),events,from,to,attempt);
            log.info("Dividend synchronization outcome=SUCCESS new={} conflicts={} cancelled={} unchanged={}",
                    counts.added(),counts.updated(),counts.cancellations(),counts.unchanged());
            return new SyncResult("SUCCESS",counts,null,view(stock,null));
        } catch (RuntimeException e) {
            String code=code(e); store.fail(stock.uid(),attempt,code);
            log.warn("Dividend synchronization outcome=FAILED reason={}",code);
            return new SyncResult("FAILED",null,code,view(stock,code));
        }
    }
    private View view(StockInstrument stock,String warning) {
        var events=store.find(stock.uid()); var state=store.state(stock.uid()); var today=today();
        boolean stale=!fresh(state);
        if (warning==null && state.failureCode()!=null) warning=state.failureCode();
        ru.ba6kir.tradelab.market.domain.price.MarketQuote quote=null;
        try { quote=prices.cachedQuote(stock.uid()).filter(q -> stock.uid().equals(q.instrumentUid())).orElse(null); }
        catch (RuntimeException e) { log.warn("Dividend reference price unavailable reason={}",code(e)); }
        var reference=quote==null?null:new DividendAnalytics.ReferencePrice(quote.price(),quote.currency(),quote.quoteTime().atOffset(ZoneOffset.UTC).toLocalDate());
        boolean observationsFresh=events.stream().filter(o -> o.event().recordDate()==null ||
                        (o.event().recordDate().isAfter(today.minusDays(365)) && !o.event().recordDate().isAfter(today)))
                .allMatch(o -> o.lastSeen().plus(ttl).isAfter(clock.instant()));
        stale=stale || !observationsFresh;
        boolean covered=covers(state,today.minusDays(364),today) && !stale && warning==null;
        var sum=analytics.trailingSum(events,today,covered);
        return new View(stock,events,state,today,stale,warning,sum,analytics.trailingYield(sum,reference,today),reference);
    }
    public CalendarView calendar() {
        if (!enabled) throw new MarketException(MarketException.Reason.DISABLED);
        var today=today(); var to=today.plusDays(90); var rows=new ArrayList<CalendarRow>(); var warnings=new ArrayList<String>();
        if (tracked.isEmpty()) warnings.add("INVEST_TRACKED_TICKERS: список пуст");
        for (var ticker:tracked) {
            try {
                var view=get(ticker,1);
                if (view.stale() || view.warning()!=null) warnings.add(ticker+": STALE/"+(view.warning()==null?"MISSING_DATA":view.warning()));
                long uncertain=view.events().stream().filter(o -> o.ambiguous() || !o.event().issues().isEmpty()).count();
                if (uncertain>0) warnings.add(ticker+": наблюдений с ограничениями качества/идентичности: "+uncertain);
                for (var o:view.events()) {
                    var e=o.event();
                    add(rows,ticker,e.recordDate(),"реестр",o,today,to);
                    add(rows,ticker,e.lastBuyDate(),"последняя покупка",o,today,to);
                    add(rows,ticker,e.paymentDate(),"указанная выплата, получение не подтверждено",o,today,to);
                    if (e.declaredDate()!=null && !e.declaredDate().isBefore(today.minusDays(30)) && !e.declaredDate().isAfter(today))
                        rows.add(new CalendarRow(ticker,e.declaredDate(),"недавнее объявление",o));
                    if (e.status(today)==DividendEvent.Status.CANCELLED && !o.lastSeen().isBefore(clock.instant().minus(Duration.ofDays(30))))
                        rows.add(new CalendarRow(ticker,o.lastSeen().atOffset(ZoneOffset.UTC).toLocalDate(),"наблюдение отмены (не дата решения)",o));
                }
            } catch (RuntimeException e) { warnings.add(ticker+": "+code(e)); }
        }
        rows.sort(Comparator.comparing(CalendarRow::date).thenComparing(CalendarRow::ticker).thenComparing(CalendarRow::kind)
                .thenComparing(r -> r.observation().event().contentHash()));
        return new CalendarView(today,to,rows.stream().limit(20).toList(),List.copyOf(warnings),Math.max(0,rows.size()-20));
    }
    private static void add(List<CalendarRow> rows,String ticker,LocalDate date,String kind,Observation o,LocalDate from,LocalDate to) {
        if (date!=null && !date.isBefore(from) && !date.isAfter(to)) rows.add(new CalendarRow(ticker,date,kind,o));
    }
    private boolean fresh(DividendStore.State state) {
        return state.lastSuccess()!=null && !state.lastSuccess().isAfter(clock.instant()) && state.lastSuccess().plus(ttl).isAfter(clock.instant());
    }
    private static boolean covers(DividendStore.State s,LocalDate from,LocalDate to) {
        return s.from()!=null && s.to()!=null && !s.from().isAfter(from) && !s.to().isBefore(to);
    }
    private LocalDate today() { return clock.instant().atOffset(ZoneOffset.UTC).toLocalDate(); }
    public static String code(RuntimeException e) {
        if (e instanceof AmbiguousInstrumentException) return "AMBIGUOUS_INSTRUMENT";
        return e instanceof MarketException m?m.reason().name():"INTERNAL";
    }
}
