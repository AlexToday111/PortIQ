package ru.ba6kir.tradelab.analysis.ranking.application;

import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.function.Supplier;
import ru.ba6kir.tradelab.analysis.ranking.domain.*;
import ru.ba6kir.tradelab.analysis.reporting.application.AnalysisSnapshotService;
import ru.ba6kir.tradelab.analysis.scoring.application.service.ScoringFingerprint;
import ru.ba6kir.tradelab.analysis.scoring.domain.profile.ScoringModel;

public class StockRankingService {
    public record View(RankingRun run,boolean expired,boolean configurationChanged,RankingStore.State state) {}
    public record Refresh(String outcome,RankingRun run) {}
    private final RankingStore store;private final AnalysisSnapshotService snapshots;private final Supplier<List<RankingRun.Member>> universe;
    private final Supplier<List<String>> identifiers;private final RankingEligibilityService eligibility;private final ScoringModel model;
    private final RankingPolicy policy;private final ScoringFingerprint hashes;private final Clock clock;
    public StockRankingService(RankingStore store,AnalysisSnapshotService snapshots,Supplier<List<RankingRun.Member>> universe,Supplier<List<String>> identifiers,
            ScoringModel model,RankingPolicy policy,ScoringFingerprint hashes,Clock clock) {
        this.store=store;this.snapshots=snapshots;this.universe=universe;this.identifiers=identifiers;this.model=model;this.policy=policy;this.hashes=hashes;this.clock=clock;
        this.eligibility=new RankingEligibilityService(model,policy,hashes);
    }
    public View current() {
        var run=store.latest().orElse(null);boolean changed=run!=null && (!run.configurationFingerprint().equals(hashes.hash(model))
                || !run.policyFingerprint().equals(hashes.hash(policy)) || !run.universeHash().equals(hashes.hash(identifiers.get().stream().sorted().toList())));
        return new View(run,run!=null && !run.expiresAt().isAfter(clock.instant()),changed,store.state());
    }
    public Refresh refresh() {
        var now=clock.instant().truncatedTo(ChronoUnit.MICROS);
        if (!store.tryStart(now,policy.refreshCooldown())) return new Refresh("COOLDOWN_OR_RUNNING",null);
        try {
            var members=universe.get();var asOf=clock.instant().truncatedTo(ChronoUnit.MICROS);var captured=snapshots.capture(members,asOf);
            if (captured.size()!=members.size() || !captured.stream().map(s -> s.member().key()).sorted().toList().equals(members.stream().map(RankingRun.Member::key).sorted().toList()))
                throw new IllegalStateException("Incomplete ranking capture");
            var entries=new ArrayList<>(captured.stream().map(s -> eligibility.assess(s,asOf)).toList());
            // One eligible security per verified issuer, or per asset when legal issuer identity is unknown.
            // UID selection is deterministic and does not cherry-pick the higher security score.
            var represented=new HashMap<String,String>();
            entries.sort(Comparator.comparing(RankingRun.Entry::instrumentUid,Comparator.nullsLast(Comparator.naturalOrder())).thenComparing(RankingRun.Entry::memberKey));
            for (int n=0;n<entries.size();n++) {
                var e=entries.get(n);if (e.status()!=RankingRun.Status.ELIGIBLE) continue;
                String key=e.issuerKey()==null?"asset:"+e.assetUid():"issuer:"+e.issuerKey();String selected=represented.putIfAbsent(key,e.instrumentUid());
                if (selected!=null) entries.set(n,e.exclude("ALTERNATE_SECURITY_SAME_ISSUER_OR_ASSET:"+selected));
            }
            entries.sort(Comparator.comparing((RankingRun.Entry e) -> e.status()!=RankingRun.Status.ELIGIBLE)
                    .thenComparing(RankingRun.Entry::comparisonGroup,Comparator.nullsLast(Comparator.naturalOrder()))
                    .thenComparing(RankingRun.Entry::score,Comparator.nullsLast(Comparator.reverseOrder()))
                    .thenComparing(RankingRun.Entry::instrumentUid,Comparator.nullsLast(Comparator.naturalOrder())).thenComparing(RankingRun.Entry::memberKey));
            var positions=new HashMap<String,Integer>();Instant expires=asOf.plus(policy.snapshotTtl());
            for (int n=0;n<entries.size();n++) {
                var e=entries.get(n);if (e.status()!=RankingRun.Status.ELIGIBLE) continue;
                entries.set(n,e.withRank(positions.merge(e.comparisonGroup(),1,Integer::sum)));
                if (e.expiresAt().isBefore(expires)) expires=e.expiresAt();
            }
            var run=new RankingRun(null,asOf,expires,model.modelVersion(),hashes.hash(model),policy,hashes.hash(policy),
                    hashes.hash(identifiers.get().stream().sorted().toList()),members,entries);
            return new Refresh("SUCCESS",store.save(run,now));
        } catch (RuntimeException e) {
            try { store.fail(now,"RANKING_GENERATION_FAILED"); } catch (RuntimeException ignored) { }
            return new Refresh("FAILED_PREVIOUS_PRESERVED",null);
        }
    }
}
