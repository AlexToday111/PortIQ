package ru.ba6kir.tradelab.fundamentals.application.service;

import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import org.slf4j.*;
import ru.ba6kir.tradelab.market.application.*;
import ru.ba6kir.tradelab.market.domain.StockInstrument;
import ru.ba6kir.tradelab.fundamentals.application.port.*;
import ru.ba6kir.tradelab.fundamentals.domain.model.*;
import ru.ba6kir.tradelab.fundamentals.domain.validation.FundamentalNormalizer;

public class FundamentalsService {
    public enum Outcome { CHANGED, UNCHANGED, FAILED, COOLDOWN }
    public record SyncResult(Outcome outcome,FundamentalAnalysisView view,int updatedMetrics,String error) { }
    public record ReportsView(StockInstrument instrument,FundamentalsStore.ReportBatch batch,boolean cached,boolean stale,String warning) { }
    private static final Logger log=LoggerFactory.getLogger(FundamentalsService.class);
    private final InstrumentService instruments;
    private final AssetIdentityResolver resolver;
    private final FundamentalsProvider provider;
    private final FundamentalsStore store;
    private final FundamentalNormalizer normalizer;
    private final Clock clock;
    private final Duration ttl,reportingAge,cooldown;
    private final int reportWindowDays;
    private final boolean enabled;
    public FundamentalsService(InstrumentService instruments,AssetIdentityResolver resolver,FundamentalsProvider provider,
            FundamentalsStore store,FundamentalNormalizer normalizer,Clock clock,Duration ttl,Duration reportingAge,
            Duration cooldown,int reportWindowDays,boolean enabled) {
        this.instruments=instruments; this.resolver=resolver; this.provider=provider; this.store=store; this.normalizer=normalizer;
        this.clock=clock; this.ttl=ttl; this.reportingAge=reportingAge; this.cooldown=cooldown; this.reportWindowDays=reportWindowDays; this.enabled=enabled;
    }
    private StockInstrument instrument(String identifier) {
        if (!enabled) throw new MarketException(MarketException.Reason.DISABLED);
        return instruments.resolve(identifier);
    }
    private boolean old(Instant time) { return time==null || time.isAfter(clock.instant()) || !time.plus(ttl).isAfter(clock.instant()); }
    public FundamentalAnalysisView getLatest(String identifier) {
        var instrument=instrument(identifier);
        var identity=store.findIdentity(instrument.uid()).orElse(null);
        var snapshot=identity==null ? null : store.latest(identity.assetUid()).orElse(null);
        if (identity!=null && snapshot!=null && !old(identity.verifiedAt()) && !old(snapshot.lastFetchedAt()))
            return view(instrument,identity,snapshot,true,null);
        return refreshResolved(instrument,identity,snapshot).view();
    }
    public FundamentalAnalysisView status(String identifier) {
        var instrument=instrument(identifier);
        var identity=store.findIdentity(instrument.uid()).orElse(null);
        return view(instrument,identity,identity==null ? null : store.latest(identity.assetUid()).orElse(null),true,null);
    }
    /** Read-only application contract for bounded peer analysis; never fetches external data. */
    public Optional<FundamentalSnapshot> cachedAsset(String assetUid) {
        return store.latest(InstrumentService.validateUid(assetUid));
    }
    public List<String> cachedInstrumentUids(String assetUid) { return store.instrumentUids(InstrumentService.validateUid(assetUid)); }
    /** Explicit local-only read, including when the provider integration is disabled. */
    public FundamentalAnalysisView cachedInstrument(String uid) {
        var instrument=instruments.getCached(InstrumentService.validateUid(uid)).orElseThrow(() -> new MarketException(MarketException.Reason.NOT_FOUND));
        var identity=store.findIdentity(uid).orElse(null);
        return view(instrument,identity,identity==null?null:store.latest(identity.assetUid()).orElse(null),true,null);
    }
    public SyncResult refresh(String identifier) {
        var instrument=instrument(identifier);
        var identity=store.findIdentity(instrument.uid()).orElse(null);
        return refreshResolved(instrument,identity,identity==null ? null : store.latest(identity.assetUid()).orElse(null));
    }
    private SyncResult refreshResolved(StockInstrument instrument,AssetIdentity cachedIdentity,FundamentalSnapshot cachedSnapshot) {
        AssetIdentity identity=cachedIdentity;
        FundamentalSnapshot snapshot=cachedSnapshot;
        Instant attempt=clock.instant().truncatedTo(ChronoUnit.MICROS);
        boolean admitted=false;
        try {
            if (identity==null || old(identity.verifiedAt())) {
                var resolved=resolver.resolve(instrument.uid()).orElseThrow(() -> new FundamentalsException(FundamentalsException.Reason.MAPPING_UNAVAILABLE));
                if (!instrument.uid().equals(resolved.instrumentUid()) || resolved.assetUid()==null)
                    throw new FundamentalsException(FundamentalsException.Reason.INVALID_DATA);
                InstrumentService.validateUid(resolved.assetUid());
                var resolvedSnapshot=store.latest(resolved.assetUid()).orElse(null);
                store.saveIdentity(resolved);
                identity=resolved;
                snapshot=resolvedSnapshot;
            }
            if (!store.tryStart(identity.assetUid(),attempt,cooldown))
                return new SyncResult(Outcome.COOLDOWN,view(instrument,identity,snapshot,true,"COOLDOWN"),0,"COOLDOWN");
            admitted=true;
            var data=provider.fetch(identity.assetUid()).orElseThrow(() -> new FundamentalsException(FundamentalsException.Reason.EMPTY_RESPONSE));
            if (!identity.assetUid().equals(data.assetUid()) || data.fetchedAt()==null || data.fetchedAt().isAfter(clock.instant().plusSeconds(60)))
                throw new FundamentalsException(FundamentalsException.Reason.INVALID_DATA);
            FundamentalSnapshot normalized;
            try { normalized=normalizer.normalize(data); }
            catch (IllegalArgumentException e) { throw new FundamentalsException(FundamentalsException.Reason.INVALID_DATA); }
            var saved=store.saveSnapshot(normalized,attempt);
            return new SyncResult(saved.changed()?Outcome.CHANGED:Outcome.UNCHANGED,
                    view(instrument,identity,saved.snapshot(),false,null),saved.updatedMetrics(),null);
        } catch (RuntimeException e) {
            String failure=code(e);
            log.warn("Fundamentals refresh failed; reason={}",failure);
            if (admitted && identity!=null) try { store.failed(identity.assetUid(),attempt,clock.instant(),failure); } catch (RuntimeException ignored) { }
            return new SyncResult(Outcome.FAILED,view(instrument,identity,snapshot,true,failure),0,failure);
        }
    }
    private FundamentalAnalysisView view(StockInstrument instrument,AssetIdentity identity,FundamentalSnapshot snapshot,boolean cached,String warning) {
        var state=new FundamentalsStore.RequestState(null,null,null);
        if (identity!=null) try { state=store.requestState(identity.assetUid()); } catch (RuntimeException ignored) { }
        return new FundamentalAnalysisView(instrument,identity,snapshot,cached,snapshot!=null && old(snapshot.lastFetchedAt()),
                snapshot!=null && snapshot.periodEnd()!=null && snapshot.periodEnd().plus(reportingAge).isBefore(clock.instant()),
                identity!=null && old(identity.verifiedAt()),warning,state);
    }
    public ReportsView reports(String identifier) {
        var instrument=instrument(identifier);
        var cached=store.reports(instrument.uid()).orElse(null);
        if (cached!=null && !old(cached.fetchedAt())) return new ReportsView(instrument,cached,true,false,null);
        Instant now=clock.instant().truncatedTo(ChronoUnit.MICROS);
        Instant from=now.minus(Duration.ofDays(reportWindowDays)),to=now.plus(Duration.ofDays(reportWindowDays));
        try {
            var events=provider.fetchReports(instrument.uid(),from,to);
            if (events.size()>500 || events.stream().anyMatch(e -> !instrument.uid().equals(e.instrumentUid())
                    || (e.reportDate()!=null && (e.reportDate().isBefore(from) || e.reportDate().isAfter(to)))))
                throw new FundamentalsException(FundamentalsException.Reason.INVALID_DATA);
            Instant fetched=clock.instant().truncatedTo(ChronoUnit.MICROS);
            store.saveReports(instrument.uid(),events,fetched,from,to);
            return new ReportsView(instrument,new FundamentalsStore.ReportBatch(events,fetched,from,to),false,false,null);
        } catch (RuntimeException e) {
            log.warn("Financial report calendar request failed; reason={}",code(e));
            if (cached!=null) return new ReportsView(instrument,cached,true,true,code(e));
            throw e;
        }
    }
    public static String code(RuntimeException e) {
        if (e instanceof MarketException m) return m.reason().name();
        if (e instanceof FundamentalsException f) return f.reason().name();
        return "INTERNAL";
    }
}
