package ru.ba6kir.tradelab.analysis.ranking.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;

/** Immutable ranking evidence. Financial inputs remain in the referenced scoring snapshots. */
public record RankingRun(Long id,Instant createdAt,Instant expiresAt,String modelVersion,String configurationFingerprint,
        RankingPolicy policy,String policyFingerprint,String universeHash,List<Member> universe,List<Entry> entries) {
    public enum Status { ELIGIBLE,INSUFFICIENT_DATA,STALE_DATA,UNSUPPORTED_PROFILE,TRADING_UNAVAILABLE,NOT_COMPARABLE }
    public record Member(String key,String identifier,String instrumentUid,String resolutionReason) {}
    public record Entry(String memberKey,String instrumentUid,String assetUid,String issuerKey,String ticker,String name,
            String sector,String businessType,String profileId,String comparisonGroup,Long scoreSnapshotId,BigDecimal score,
            Integer rank,Status status,List<String> reasons,List<String> riskCodes,Instant scoreEvaluatedAt,
            Instant dataCutoff,Instant quoteTime,Instant quoteFetchedAt,Instant metadataAt,Instant expiresAt,List<String> sourceReferences) {
        public Entry { reasons=List.copyOf(reasons);riskCodes=List.copyOf(riskCodes);sourceReferences=List.copyOf(sourceReferences); }
        public Entry withRank(int value) { return new Entry(memberKey,instrumentUid,assetUid,issuerKey,ticker,name,sector,businessType,profileId,
                comparisonGroup,scoreSnapshotId,score,value,status,reasons,riskCodes,scoreEvaluatedAt,dataCutoff,quoteTime,quoteFetchedAt,metadataAt,expiresAt,sourceReferences); }
        public Entry exclude(String reason) { return new Entry(memberKey,instrumentUid,assetUid,issuerKey,ticker,name,sector,businessType,profileId,
                comparisonGroup,scoreSnapshotId,score,null,Status.NOT_COMPARABLE,List.of(reason),riskCodes,scoreEvaluatedAt,dataCutoff,quoteTime,quoteFetchedAt,metadataAt,expiresAt,sourceReferences); }
    }
    public RankingRun { universe=List.copyOf(universe);entries=List.copyOf(entries); }
    public long eligibleCount() { return entries.stream().filter(e -> e.status()==Status.ELIGIBLE).count(); }
    public long excludedCount() { return entries.size()-eligibleCount(); }
    public Map<String,Long> exclusionCounts() { var counts=new TreeMap<String,Long>();entries.stream().filter(e -> e.status()!=Status.ELIGIBLE)
            .flatMap(e -> e.reasons().stream()).forEach(r -> counts.merge(r,1L,Long::sum));return Collections.unmodifiableMap(counts); }
    public RankingRun withId(long value) { return new RankingRun(value,createdAt,expiresAt,modelVersion,configurationFingerprint,policy,policyFingerprint,universeHash,universe,entries); }
}
