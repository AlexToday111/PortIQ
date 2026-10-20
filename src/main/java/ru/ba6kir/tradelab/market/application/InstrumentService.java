package ru.ba6kir.tradelab.market.application;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import ru.ba6kir.tradelab.market.domain.StockInstrument;

public class InstrumentService {
    public record Metadata(StockInstrument instrument, boolean cached) { }
    private final StockInstrumentProvider provider;
    private final InstrumentStore store;
    private final Clock clock;
    private final Duration ttl;
    private final boolean enabled;

    public InstrumentService(StockInstrumentProvider provider, InstrumentStore store, Clock clock, Duration ttl, boolean enabled) {
        this.provider = provider;
        this.store = store;
        this.clock = clock;
        this.ttl = ttl;
        this.enabled = enabled;
    }

    public List<StockInstrument> search(String query) {
        query = validateQuery(query);
        requireEnabled();
        return provider.findShares(query);
    }

    public List<StockInstrument> findByTicker(String ticker) {
        if (ticker == null || !ticker.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,31}"))
            throw new MarketException(MarketException.Reason.INVALID_INPUT);
        // Always resolve ticker candidates remotely: a partial local catalog cannot prove uniqueness.
        String exact = ticker.toUpperCase(Locale.ROOT);
        return search(exact).stream().filter(s -> exact.equalsIgnoreCase(s.ticker())).toList();
    }

    public Metadata findByUid(String uid, boolean refresh) {
        uid = validateUid(uid);
        requireEnabled();
        if (!refresh) {
            var cached = store.findByUid(uid).filter(s -> !s.updatedAt().isAfter(clock.instant())
                    && s.updatedAt().plus(ttl).isAfter(clock.instant()));
            if (cached.isPresent()) return new Metadata(cached.get(), true);
        }
        var instrument = provider.findByUid(uid).orElseThrow(() -> new MarketException(MarketException.Reason.NOT_FOUND));
        save(instrument);
        return new Metadata(instrument, false);
    }

    public Metadata refresh(String uid) { return findByUid(uid, true); }
    public StockInstrument resolve(String identifier) {
        if (identifier!=null && identifier.length()==36) return findByUid(identifier,false).instrument();
        var matches=findByTicker(identifier);
        if (matches.isEmpty()) throw new MarketException(MarketException.Reason.NOT_FOUND);
        if (matches.size()>1) throw new AmbiguousInstrumentException(matches);
        return findByUid(matches.getFirst().uid(),false).instrument();
    }
    public Optional<StockInstrument> getCached(String uid) { return store.findByUid(validateUid(uid)); }
    public void save(StockInstrument instrument) { store.upsert(instrument); }

    public static String validateQuery(String query) {
        if (query == null || query.isBlank() || query.length() > 64
                || !query.matches("[\\p{L}\\p{N} ._\\-]{1,64}"))
            throw new MarketException(MarketException.Reason.INVALID_INPUT);
        return query.trim();
    }

    public static String validateUid(String uid) {
        if (uid == null || !uid.matches("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}"))
            throw new MarketException(MarketException.Reason.INVALID_INPUT);
        return UUID.fromString(uid).toString();
    }

    private void requireEnabled() {
        if (!enabled) throw new MarketException(MarketException.Reason.DISABLED);
    }
}
