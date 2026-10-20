package ru.ba6kir.tradelab.analysis.domain.comparison;

import java.math.*;
import java.time.Instant;
import java.util.*;
import ru.ba6kir.tradelab.analysis.domain.metric.*;
import ru.ba6kir.tradelab.analysis.domain.sector.*;
import static ru.ba6kir.tradelab.analysis.domain.sector.SectorClassification.*;

public class SectorComparison {
    public record Company(SectorClassification classification,List<FinancialObservation> observations,boolean classificationFresh,MetricProfile profile) {
        public Company(SectorClassification classification,List<FinancialObservation> observations,boolean classificationFresh) {
            this(classification,observations,classificationFresh,MetricProfile.defaults(classification));
        }
    }
    public record PeerEvidence(String assetUid,String issuerKey,String observationId,Instant fetchedAt,String exclusion) {}
    public record Result(FinancialObservation subject,String status,BigDecimal median,BigDecimal relativePositionPercent,int validPeers,
                         int minimumPeers,List<PeerEvidence> universe,Instant calculatedAt) {}
    public Result compare(FinancialObservation subject,Company company,List<Company> universe,int minimumPeers,Instant now) {
        if (minimumPeers<5) throw new IllegalArgumentException("At least five peer companies required for relative positions");
        String invalid=invalidSubject(subject,company);
        if (invalid==null && (subject.fetchedAt()==null || subject.fetchedAt().isAfter(now)
                || subject.reportingEndDate().isAfter(now.atOffset(java.time.ZoneOffset.UTC).toLocalDate()))) invalid="INVALID_SUBJECT_DATES";
        if (invalid!=null) {
            final String reason=invalid;
            return new Result(subject,invalid,null,null,0,minimumPeers,universe.stream().map(p -> new PeerEvidence(p.classification().assetUid(),
                    p.classification().issuerKey(),null,p.observations().isEmpty()?null:p.observations().getFirst().fetchedAt(),reason)).toList(),now);
        }
        var evidence=new ArrayList<PeerEvidence>(); var groups=new TreeMap<String,List<FinancialObservation>>();
        var seenAssets=new HashSet<String>();
        for (var peer:universe) {
            var c=peer.classification();
            if (!seenAssets.add(c.assetUid())) continue;
            String excluded=null;
            if (c.assetUid().equals(company.classification().assetUid()) || Objects.equals(c.issuerKey(),company.classification().issuerKey())) excluded="SUBJECT_ISSUER";
            else if (!peer.classificationFresh()) excluded="STALE_CLASSIFICATION";
            else if (c.sector()!=company.classification().sector() || c.businessType()!=company.classification().businessType()) excluded="SECTOR_OR_BUSINESS_TYPE_MISMATCH";
            else if (c.issuerKey()==null) excluded="ISSUER_IDENTITY_UNVERIFIED";
            var candidates=peer.observations().stream().filter(o -> o.metricCode()==subject.metricCode() && compatible(subject,o)
                    && o.fetchedAt()!=null && !o.fetchedAt().isAfter(now)).toList();
            if (excluded==null && candidates.size()!=1) excluded=candidates.isEmpty()?"MISSING_INVALID_OR_INCOMPATIBLE_METRIC":"AMBIGUOUS_METRIC_VARIANTS";
            var chosen=candidates.size()==1?candidates.getFirst():null;
            if (excluded==null) groups.computeIfAbsent(c.issuerKey(),k -> new ArrayList<>()).add(chosen);
            evidence.add(new PeerEvidence(c.assetUid(),c.issuerKey(),chosen==null?null:chosen.id(),chosen==null?null:chosen.fetchedAt(),excluded));
        }
        var values=new ArrayList<BigDecimal>(); var conflicting=new HashSet<String>();
        groups.forEach((issuer,rows) -> {
            if (rows.stream().map(FinancialObservation::value).map(BigDecimal::stripTrailingZeros).distinct().count()!=1) conflicting.add(issuer);
            else values.add(rows.getFirst().value());
        });
        evidence=evidence.stream().map(e -> e.exclusion()==null && conflicting.contains(e.issuerKey())
                ?new PeerEvidence(e.assetUid(),e.issuerKey(),e.observationId(),e.fetchedAt(),"CONFLICTING_SECURITIES_FOR_ISSUER"):e).collect(java.util.stream.Collectors.toCollection(ArrayList::new));
        values.sort(Comparator.naturalOrder()); int n=values.size(); BigDecimal median=null,relative=null;
        if (n>0) median=n%2==1?values.get(n/2):values.get(n/2-1).add(values.get(n/2)).divide(BigDecimal.valueOf(2));
        if (n>=minimumPeers) {
            long less=values.stream().filter(v -> v.compareTo(subject.value())<0).count();
            long equal=values.stream().filter(v -> v.compareTo(subject.value())==0).count();
            relative=BigDecimal.valueOf(less).add(BigDecimal.valueOf(equal).divide(BigDecimal.valueOf(2)))
                    .multiply(BigDecimal.valueOf(100)).divide(BigDecimal.valueOf(n),6,RoundingMode.HALF_UP);
        }
        return new Result(subject,n<minimumPeers?"INSUFFICIENT_PEERS":"AVAILABLE",median,relative,n,minimumPeers,List.copyOf(evidence),now);
    }
    private String invalidSubject(FinancialObservation o,Company c) {
        if (!c.classificationFresh()) return "STALE_CLASSIFICATION";
        if (c.profile()==null || !c.profile().metrics().contains(o.metricCode())) return "UNSUPPORTED_PROFILE";
        if (c.classification().issuerKey()==null) return "ISSUER_IDENTITY_UNVERIFIED";
        if (!o.usable()) return "MISSING_INVALID_OR_UNDATED_SUBJECT";
        if (o.metricCode().family()==AnalysisMetric.Family.VALUATION && o.providerAsOfDate()==null) return "VALUATION_AS_OF_UNKNOWN";
        if (o.instrumentUid()!=null) return "SECURITY_SCOPED_METRIC_NOT_COMPARED_AS_COMPANY";
        return null;
    }
    private boolean compatible(FinancialObservation a,FinancialObservation b) {
        return a.metricCode()==b.metricCode() && a.compatible(b) && a.unit()==b.unit()
                && Objects.equals(a.currency(),b.currency()) && a.origin()==b.origin() && Objects.equals(a.source(),b.source())
                && Objects.equals(a.methodology(),b.methodology()) && b.instrumentUid()==null
                && (a.metricCode().family()!=AnalysisMetric.Family.VALUATION || (b.providerAsOfDate()!=null &&
                   a.providerAsOfDate().atOffset(java.time.ZoneOffset.UTC).toLocalDate().equals(b.providerAsOfDate().atOffset(java.time.ZoneOffset.UTC).toLocalDate())));
    }
}
