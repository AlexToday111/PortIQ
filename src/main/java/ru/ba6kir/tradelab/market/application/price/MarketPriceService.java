package ru.ba6kir.tradelab.market.application.price;

import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import org.slf4j.*;
import ru.ba6kir.tradelab.market.application.*;
import ru.ba6kir.tradelab.market.domain.StockInstrument;
import ru.ba6kir.tradelab.market.domain.price.*;

public class MarketPriceService {
    public record QuoteResult(StockInstrument instrument, MarketQuote quote, boolean cached, String warning) { }
    public enum Outcome { SUCCESS, PARTIAL, FAILED, COOLDOWN }
    public record SyncResult(Outcome outcome, boolean quoteUpdated, int candlesSaved, String quoteError, String historyError) { }
    public record HistoryResult(StockInstrument instrument, LocalDate from, LocalDate to, Optional<HistorySummary> summary,
                                int incompleteCount, Instant lastFetchedAt, SyncResult synchronization) { }
    public record TrackedResult(String ticker, SyncResult synchronization, String error) { }
    private static final Logger log = LoggerFactory.getLogger(MarketPriceService.class);
    private final InstrumentService instruments;
    private final MarketPriceProvider provider;
    private final MarketPriceStore store;
    private final Clock clock;
    private final Duration cooldown;
    private final int defaultDays;
    private final List<String> tracked;
    private final boolean enabled;

    public MarketPriceService(InstrumentService instruments, MarketPriceProvider provider, MarketPriceStore store,
                              Clock clock, Duration cooldown, int defaultDays, List<String> tracked, boolean enabled) {
        this.instruments=instruments; this.provider=provider; this.store=store; this.clock=clock;
        this.cooldown=cooldown; this.defaultDays=defaultDays; this.tracked=List.copyOf(tracked); this.enabled=enabled;
    }
    public boolean enabled() { return enabled; }
    private void requireEnabled() { if (!enabled) throw new MarketException(MarketException.Reason.DISABLED); }
    public StockInstrument resolve(String identifier) {
        requireEnabled();
        if (identifier != null && identifier.length() == 36) return instruments.findByUid(identifier, false).instrument();
        var matches = instruments.findByTicker(identifier);
        if (matches.isEmpty()) throw new MarketException(MarketException.Reason.NOT_FOUND);
        if (matches.size() > 1) throw new AmbiguousInstrumentException(matches);
        return instruments.findByUid(matches.getFirst().uid(), false).instrument();
    }
    public QuoteResult lastPrice(String identifier) {
        var instrument = resolve(identifier);
        MarketQuote quote;
        try { quote = fetchQuote(instrument); }
        catch (MarketException | PriceException e) {
            var cached = store.findQuote(instrument.uid());
            if (cached.isPresent()) return new QuoteResult(instrument, cached.get(), true, code(e));
            throw e;
        }
        store.saveQuote(quote);
        return new QuoteResult(instrument, quote, false, null);
    }
    public Optional<MarketQuote> cachedQuote(String uid) { return store.findQuote(InstrumentService.validateUid(uid)); }
    private MarketQuote fetchQuote(StockInstrument instrument) {
        var quote = provider.getLastPrice(instrument).orElseThrow(() -> new PriceException(PriceException.Reason.NO_PRICE));
        if (!instrument.uid().equals(quote.instrumentUid()) || instrument.currency() == null
                || !instrument.currency().equalsIgnoreCase(quote.currency()) || quote.quoteTime().isAfter(clock.instant().plusSeconds(60))
                || quote.fetchedAt().isAfter(clock.instant().plusSeconds(60))) throw new PriceException(PriceException.Reason.INVALID_DATA);
        return quote;
    }
    public SyncResult refresh(String identifier) { return synchronize(resolve(identifier), defaultDays); }
    public List<TrackedResult> refreshTracked() {
        requireEnabled();
        var result = new ArrayList<TrackedResult>();
        for (String ticker : tracked) {
            try { result.add(new TrackedResult(ticker, refresh(ticker), null)); }
            catch (RuntimeException e) { result.add(new TrackedResult(ticker, null, code(e))); }
        }
        return List.copyOf(result);
    }
    public HistoryResult history(String identifier, int days) {
        validateDays(days);
        var instrument = resolve(identifier);
        var synchronization = synchronize(instrument, days);
        var to = clock.instant().atZone(DailyCandle.TRADING_ZONE).toLocalDate();
        var from = to.minusDays(days - 1L);
        var candles = store.findCandles(instrument.uid(), from, to);
        return new HistoryResult(instrument, from, to, HistorySummary.calculate(candles),
                (int)candles.stream().filter(c -> !c.complete()).count(),
                candles.stream().map(DailyCandle::fetchedAt).max(Comparator.naturalOrder()).orElse(null), synchronization);
    }
    private static void validateDays(int days) {
        if (days < 1 || days > 365) throw new MarketException(MarketException.Reason.INVALID_INPUT);
    }
    public SyncResult synchronize(StockInstrument instrument, int days) {
        requireEnabled(); validateDays(days);
        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        if (!store.tryStart(instrument.uid(), now, cooldown)) return new SyncResult(Outcome.COOLDOWN, false, 0, null, null);
        boolean quoteUpdated = false;
        boolean historyFetched = false;
        int saved = 0;
        String quoteError = null, historyError = null;
        try {
            try { store.saveQuote(fetchQuote(instrument)); quoteUpdated = true; }
            catch (RuntimeException e) { quoteError = code(e); }
            var requestedFrom = now.atZone(DailyCandle.TRADING_ZONE).toLocalDate().minusDays(days - 1L)
                    .atStartOfDay(DailyCandle.TRADING_ZONE).toInstant();
            try {
                var state = store.syncState(instrument.uid()).orElse(null);
                var ranges = ranges(instrument.uid(), requestedFrom, now, state);
                for (var range : ranges) {
                    for (Instant cursor=range.from(); cursor.isBefore(range.to());) {
                        Instant end = cursor.plus(Duration.ofDays(180));
                        if (end.isAfter(range.to())) end=range.to();
                        var candles = provider.getDailyCandles(instrument, cursor, end);
                        validateCandles(instrument.uid(), candles, cursor, end, now);
                        store.saveCandles(candles);
                        saved += candles.size();
                        cursor=end;
                    }
                }
                Instant coveredFrom=requestedFrom;
                if (state != null && state.coveredFrom()!=null && state.coveredTo()!=null
                        && !state.coveredTo().isBefore(requestedFrom) && state.coveredFrom().isBefore(coveredFrom))
                    coveredFrom=state.coveredFrom();
                store.coverHistory(instrument.uid(), coveredFrom, now);
                historyFetched = true;
                if (store.findCandles(instrument.uid(), requestedFrom.atZone(DailyCandle.TRADING_ZONE).toLocalDate(),
                        now.atZone(DailyCandle.TRADING_ZONE).toLocalDate()).isEmpty()) historyError="EMPTY_HISTORY";
            } catch (RuntimeException e) { historyError=code(e); }
            boolean success=quoteUpdated && historyFetched && historyError==null;
            var outcome=success ? Outcome.SUCCESS : quoteUpdated || (historyFetched && historyError==null) || saved>0 ? Outcome.PARTIAL : Outcome.FAILED;
            store.finish(instrument.uid(), now, success, success ? null : "quote="+quoteError+";history="+historyError);
            log.info("Market synchronization completed; outcome={}, candles={}", outcome, saved);
            return new SyncResult(outcome, quoteUpdated, saved, quoteError, historyError);
        } catch (RuntimeException e) {
            // A database outage may also prevent durable failure reporting; /status then reports unavailable.
            log.warn("Market synchronization failed internally");
            try { store.finish(instrument.uid(), now, false, "INTERNAL"); } catch (RuntimeException ignored) { }
            throw new MarketException(MarketException.Reason.INTERNAL);
        }
    }
    private record Range(Instant from, Instant to) { }
    private List<Range> ranges(String uid, Instant from, Instant to, MarketPriceStore.SyncState state) {
        if (state==null || state.coveredFrom()==null || state.coveredTo()==null || state.coveredTo().isBefore(from)
                || state.coveredFrom().isAfter(to)) return List.of(new Range(from,to));
        var result=new ArrayList<Range>();
        if (from.isBefore(state.coveredFrom())) result.add(new Range(from, state.coveredFrom()));
        Instant tail=state.coveredTo().truncatedTo(ChronoUnit.DAYS).minus(Duration.ofDays(1));
        var incomplete=store.earliestIncomplete(uid,from);
        if (incomplete.isPresent() && incomplete.get().isBefore(tail)) tail=incomplete.get();
        if (tail.isBefore(from)) tail=from;
        if (!result.isEmpty() && !tail.isAfter(result.getFirst().to())) return List.of(new Range(from,to));
        if (tail.isBefore(to)) result.add(new Range(tail,to));
        return result;
    }
    private void validateCandles(String uid, List<DailyCandle> candles, Instant from, Instant to, Instant now) {
        var seen = new HashSet<Instant>();
        for (var candle : candles) if (!uid.equals(candle.instrumentUid()) || candle.candleTime().isBefore(from)
                || !candle.candleTime().isBefore(to) || candle.fetchedAt().isAfter(now.plusSeconds(60))
                || !seen.add(candle.candleTime())) throw new PriceException(PriceException.Reason.INVALID_DATA);
    }
    public MarketPriceStore.SyncStatus status() { return store.status(); }
    public static String code(RuntimeException e) {
        if (e instanceof MarketException market) return market.reason().name();
        if (e instanceof PriceException price) return price.reason().name();
        if (e instanceof AmbiguousInstrumentException) return "AMBIGUOUS";
        return "INTERNAL";
    }
}
