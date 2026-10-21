package ru.ba6kir.tradelab.analysis.ranking.application;

import java.time.*;
import java.util.*;
import ru.ba6kir.tradelab.analysis.ranking.domain.*;
import ru.ba6kir.tradelab.analysis.reporting.application.AnalysisSnapshotService.StockSnapshot;
import ru.ba6kir.tradelab.analysis.scoring.application.service.ScoringFingerprint;
import ru.ba6kir.tradelab.analysis.scoring.domain.model.ScoringResult;
import ru.ba6kir.tradelab.analysis.scoring.domain.profile.ScoringModel;
import ru.ba6kir.tradelab.analysis.scoring.domain.rule.FundamentalScoringEngine;
import static ru.ba6kir.tradelab.analysis.ranking.domain.RankingRun.Status;

public class RankingEligibilityService {
    private final ScoringModel active; private final RankingPolicy policy; private final ScoringFingerprint hashes;
    public RankingEligibilityService(ScoringModel active,RankingPolicy policy,ScoringFingerprint hashes) { this.active=active;this.policy=policy;this.hashes=hashes; }
    public RankingRun.Entry assess(StockSnapshot s,Instant now) {
        var reasons=new TreeSet<String>(); var member=s.member();var i=s.instrument();var r=s.score();var m=s.model();var q=s.quote();
        Status status=Status.ELIGIBLE;String group=null;Instant expires=now.plus(policy.snapshotTtl());
        if (member.resolutionReason()!=null) { reasons.add(member.resolutionReason());status=Status.INSUFFICIENT_DATA; }
        if (i==null || !Objects.equals(member.instrumentUid(),i.uid()) || i.ticker()==null || i.currency()==null || i.source().isBlank()) {
            reasons.add("UNVERIFIED_INSTRUMENT_IDENTITY");status=Status.INSUFFICIENT_DATA;
        }
        if (r==null || r.id()==null || r.input()==null) { reasons.add("NO_STORED_SCORE");status=Status.INSUFFICIENT_DATA; }
        else {
            if (r.status()!=ScoringResult.Status.SCORED || r.overallScore()==null) {
                reasons.add("SCORE_"+r.status());reasons.addAll(r.eligibilityReasons());
                status=r.status()==ScoringResult.Status.UNSUPPORTED_PROFILE?Status.UNSUPPORTED_PROFILE:r.status()==ScoringResult.Status.STALE_DATA?Status.STALE_DATA:Status.INSUFFICIENT_DATA;
            }
            if (m==null || !hashes.hash(active).equals(r.configurationFingerprint()) || !active.modelVersion().equals(r.modelVersion())
                    || !active.configurationVersion().equals(r.configurationVersion()) || m!=null && !hashes.hash(m).equals(r.configurationFingerprint())) {
                reasons.add("MODEL_OR_CONFIGURATION_MISMATCH");status=Status.NOT_COMPARABLE;
            } else {
                // Reuse the original engine solely to revalidate frozen evidence at the common cutoff.
                // Ranking points ALWAYS come from the persisted result, never from this check.
                var checked=new FundamentalScoringEngine().evaluate(r.input(),m,r.configurationFingerprint(),r.inputFingerprint(),now);
                if (!hashes.hash(r.input()).equals(r.inputFingerprint()) || !Objects.equals(member.instrumentUid(),r.instrumentUid())
                        || !Objects.equals(r.instrumentUid(),r.input().instrumentUid()) || !Objects.equals(r.assetUid(),r.input().assetUid())
                        || !Objects.equals(r.classification(),r.input().classification())) {
                    reasons.add("SCORE_INPUT_INTEGRITY_MISMATCH");status=Status.NOT_COMPARABLE;
                }
                if (checked.status()!=ScoringResult.Status.SCORED) {
                    reasons.addAll(checked.eligibilityReasons());reasons.add("FROZEN_EVIDENCE_NOT_ELIGIBLE_AT_CUTOFF");
                    status=checked.eligibilityReasons().stream().anyMatch(x -> x.startsWith("STALE") || x.startsWith("INVALID_VALUATION_AS_OF")
                            || x.startsWith("INVALID_DIVIDEND_PRICE_DATE") || x.equals("UNRELIABLE_DIVIDEND_HISTORY"))?Status.STALE_DATA:Status.INSUFFICIENT_DATA;
                } else if (r.status()==ScoringResult.Status.SCORED && (r.overallScore().compareTo(checked.overallScore())!=0
                        || !Objects.equals(r.profileId(),checked.profileId()) || !r.coverage().equals(checked.coverage())
                        || !r.pillarScores().equals(checked.pillarScores()) || !hashes.hash(r.contributions()).equals(hashes.hash(checked.contributions())))) {
                    reasons.add("SCORE_REPLAY_MISMATCH");status=Status.NOT_COMPARABLE;
                }
                if (r.classification()==null || !r.classification().fresh(now,policy.classificationMaxAge())) {
                    reasons.add("STALE_OR_UNKNOWN_CLASSIFICATION");status=Status.STALE_DATA;
                }
                if (r.evaluatedAt()==null || r.evaluatedAt().isAfter(now) || !r.evaluatedAt().plus(policy.scoreMaxAge()).isAfter(now)) {
                    reasons.add("SCORE_OUTSIDE_AS_OF_WINDOW");status=Status.STALE_DATA;
                }
                if (r.evaluatedAt()!=null) expires=min(expires,r.evaluatedAt().plus(policy.scoreMaxAge()));
                if (r.classification()!=null && r.classification().verifiedAt()!=null) expires=min(expires,r.classification().verifiedAt().plus(policy.classificationMaxAge()));
                var evidence=new ArrayList<Object>();
                for (var c:r.contributions()) if (c.metricWeight().signum()>0 && c.pillarWeight().signum()>0) {
                    var o=r.input().observations().stream().filter(x -> Objects.equals(x.id(),c.observationId())).findFirst().orElse(null);
                    if (o==null) { reasons.add("MISSING_CONTRIBUTION_OBSERVATION");status=Status.NOT_COMPARABLE;continue; }
                    evidence.add(Arrays.asList(c.ruleId(),o.origin(),o.source(),o.methodology(),o.unit(),o.currency(),o.observationType(),o.reportingPeriod(),
                            o.periodStart(),o.reportingEndDate(),c.pillar()==ScoringResult.Pillar.V || c.pillar()==ScoringResult.Pillar.D
                                    ?o.providerAsOfDate()==null?null:o.providerAsOfDate().atOffset(ZoneOffset.UTC).toLocalDate():null));
                    if (o.fetchedAt()!=null) expires=min(expires,o.fetchedAt().plus(m.observationMaxAge()));
                    if (o.reportingEndDate()!=null && c.pillar()!=ScoringResult.Pillar.D) expires=min(expires,o.reportingEndDate().atStartOfDay(ZoneOffset.UTC).toInstant().plus(m.financialPeriodMaxAge()));
                    if (o.providerAsOfDate()!=null && (c.pillar()==ScoringResult.Pillar.V || c.pillar()==ScoringResult.Pillar.D)) expires=min(expires,o.providerAsOfDate().plus(Duration.ofDays(7)));
                }
                if (r.input().dividends()!=null && active.weights().get(ScoringResult.Pillar.D).signum()>0) {
                    var d=r.input().dividends();evidence.add(Arrays.asList(d.coverageFrom(),d.coverageTo(),d.asOf()));
                    if (d.fetchedAt()!=null) expires=min(expires,d.fetchedAt().plus(m.observationMaxAge()));
                    expires=min(expires,now.atOffset(ZoneOffset.UTC).toLocalDate().plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant());
                }
                var profile=m.profiles().get(r.profileId());
                if (profile!=null) for (var code:profile.requiredContext()) for (var o:r.input().observations()) if (o.metricCode()==code && o.fetchedAt()!=null)
                    expires=min(expires,o.fetchedAt().plus(m.observationMaxAge()));
                group=r.profileId()+"/"+(r.classification()==null?"UNKNOWN":r.classification().sector())+"/"+hashes.hash(evidence);
            }
        }
        if (i!=null) {
            if (!Set.of("NORMAL_TRADING","SECURITY_TRADING_STATUS_NORMAL_TRADING").contains(String.valueOf(i.tradingStatus())) || !Boolean.TRUE.equals(i.apiTradeAvailable())) {
                reasons.add("TRADING_METADATA_UNAVAILABLE");status=Status.TRADING_UNAVAILABLE;
            }
            if (i.updatedAt().isAfter(now) || !i.updatedAt().plus(policy.metadataMaxAge()).isAfter(now)) { reasons.add("STALE_INSTRUMENT_METADATA");status=Status.STALE_DATA; }
            expires=min(expires,i.updatedAt().plus(policy.metadataMaxAge()));
        }
        if (q==null) { reasons.add("NO_MARKET_PRICE");if (status==Status.ELIGIBLE) status=Status.INSUFFICIENT_DATA; }
        else {
            if (i==null || !Objects.equals(i.uid(),q.instrumentUid()) || !q.currency().equalsIgnoreCase(i.currency())) { reasons.add("QUOTE_IDENTITY_OR_CURRENCY_MISMATCH");status=Status.NOT_COMPARABLE; }
            if (q.quoteTime().isAfter(now) || q.fetchedAt().isAfter(now) || !q.quoteTime().plus(policy.quoteMaxAge()).isAfter(now)
                    || !q.fetchedAt().plus(policy.quoteMaxAge()).isAfter(now)) { reasons.add("STALE_MARKET_PRICE");status=Status.STALE_DATA; }
            expires=min(min(expires,q.quoteTime().plus(policy.quoteMaxAge())),q.fetchedAt().plus(policy.quoteMaxAge()));
        }
        var c=r==null?null:r.classification();var refs=new ArrayList<String>();
        if (s.warnings().contains("CURRENT_ASSET_MAPPING_MISMATCH")) { reasons.add("CURRENT_ASSET_MAPPING_MISMATCH");status=Status.NOT_COMPARABLE; }
        if (c!=null && s.classification()!=null && (!Objects.equals(c.assetUid(),s.classification().assetUid()) || c.sector()!=s.classification().sector()
                || c.businessType()!=s.classification().businessType() || !Objects.equals(c.issuerKey(),s.classification().issuerKey()))) {
            reasons.add("CLASSIFICATION_CHANGED_SINCE_SCORE");status=Status.NOT_COMPARABLE;
        }
        if (reasons.isEmpty() && !expires.isAfter(now)) { reasons.add("EVIDENCE_EXPIRED_AT_CUTOFF");status=Status.STALE_DATA; }
        if (status!=Status.NOT_COMPARABLE && status!=Status.TRADING_UNAVAILABLE && reasons.contains("SCORE_UNSUPPORTED_PROFILE")) status=Status.UNSUPPORTED_PROFILE;
        if (r!=null) { refs.add("score:"+r.id());refs.add("input:"+r.inputFingerprint());refs.addAll(r.inputSnapshotIds()); }
        if (q!=null) refs.add("quote:"+q.source()+":"+q.quoteTime()+":"+q.fetchedAt()+"; uid="+q.instrumentUid()+"; price="+q.price().toPlainString()+"; currency="+q.currency());
        if (i!=null) refs.add("metadata:"+i.source()+":"+i.updatedAt()+"; uid="+i.uid()+"; currency="+i.currency()+"; status="+i.tradingStatus()+"; api="+i.apiTradeAvailable());
        return new RankingRun.Entry(member.key(),member.instrumentUid(),r==null?null:r.assetUid(),c==null?null:c.issuerKey(),i==null?member.identifier():i.ticker(),
                i==null?null:i.name(),c==null?"UNKNOWN":c.sector().name(),c==null?"UNKNOWN":c.businessType().name(),r==null?null:r.profileId(),group,
                r==null?null:r.id(),r==null?null:r.overallScore(),null,reasons.isEmpty()?Status.ELIGIBLE:status,List.copyOf(reasons),
                r==null?List.of():r.risks().stream().map(x -> x.severity()+":"+x.ruleId()).toList(),r==null?null:r.evaluatedAt(),r==null?null:r.dataCutoff(),
                q==null?null:q.quoteTime(),q==null?null:q.fetchedAt(),i==null?null:i.updatedAt(),expires,refs);
    }
    private static Instant min(Instant a,Instant b) { return a.isBefore(b)?a:b; }
}
