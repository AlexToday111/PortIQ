package ru.ba6kir.tradelab.analysis.scoring.domain.rule;

import java.math.*;
import java.time.*;
import java.util.*;
import ru.ba6kir.tradelab.analysis.domain.metric.*;
import ru.ba6kir.tradelab.analysis.domain.sector.SectorClassification;
import ru.ba6kir.tradelab.analysis.scoring.domain.model.*;
import ru.ba6kir.tradelab.analysis.scoring.domain.profile.*;
import ru.ba6kir.tradelab.analysis.scoring.domain.risk.FundamentalRiskEvaluator;
import static ru.ba6kir.tradelab.analysis.scoring.domain.model.ScoringResult.*;

/** Pure absolute-threshold model; contains no provider, persistence, clock or Telegram calls. */
public class FundamentalScoringEngine {
    public ScoringResult evaluate(ScoringInput input,ScoringModel model,String configurationHash,String inputHash,Instant now) {
        String profileId=ScoringProfiles.resolve(input.classification()); var profile=model.profiles().get(profileId);
        var scores=new EnumMap<Pillar,BigDecimal>(Pillar.class); var coverage=new EnumMap<Pillar,BigDecimal>(Pillar.class);
        var contributions=new ArrayList<Contribution>(); var reasons=new TreeSet<String>();
        boolean reliable=input.classification()!=null && input.classificationFresh()
                && Objects.equals(input.assetUid(),input.classification().assetUid()) && input.classification().source()!=SectorClassification.Source.UNKNOWN
                && input.classification().verifiedAt()!=null && !input.classification().verifiedAt().isAfter(now);
        if (input.classification()==null || input.classification().sector()==SectorClassification.Sector.UNKNOWN) reasons.add("UNKNOWN_SECTOR");
        else if (!reliable) reasons.add("STALE_OR_UNRELIABLE_CLASSIFICATION");
        if (profile==null) reasons.add("UNSUPPORTED_PROFILE");
        var validator=new ScoringEligibilityValidator();
        var earnings=validator.context(input,AnalysisMetric.NET_INCOME,model,now);
        var leverage=validator.context(input,AnalysisMetric.NET_DEBT_EBITDA,model,now);
        boolean distressed=earnings.reason()==null && earnings.observation().value().signum()<=0
                || !profileId.equals("BANK") && leverage.reason()==null && earnings.reason()==null
                && Objects.equals(leverage.observation().snapshotId(),earnings.observation().snapshotId())
                && Objects.equals(leverage.observation().reportingEndDate(),earnings.observation().reportingEndDate())
                && leverage.observation().value().compareTo(model.highLeverage())>=0;
        if (profile!=null) for (var code:profile.requiredContext()) {
            var c=validator.context(input,code,model,now); if (c.reason()!=null) reasons.add(c.reason());
        }
        for (var p:Pillar.values()) {
            BigDecimal sum=BigDecimal.ZERO,covered=BigDecimal.ZERO; boolean complete=true; FinancialObservation anchor=null;
            if (profile!=null) for (var rule:profile.rules().stream().filter(r -> r.pillar()==p).toList()) {
                var selection=validator.select(input,rule,model,now); var o=selection.observation(); String reason=selection.reason();
                if (!reliable) reason="STALE_OR_UNRELIABLE_CLASSIFICATION";
                if (reason==null && p!=Pillar.D && anchor!=null) {
                    boolean periods=Objects.equals(anchor.reportingEndDate(),o.reportingEndDate())
                            && (anchor.observationType()==FinancialObservation.ObservationType.POINT_IN_TIME || o.observationType()==FinancialObservation.ObservationType.POINT_IN_TIME || anchor.compatible(o));
                    if (!periods) reason="INCOMPATIBLE_REPORTING_PERIOD_"+rule.metric();
                    else if (p==Pillar.V && !Objects.equals(anchor.providerAsOfDate().atOffset(ZoneOffset.UTC).toLocalDate(),o.providerAsOfDate().atOffset(ZoneOffset.UTC).toLocalDate())) reason="INCOMPATIBLE_VALUATION_AS_OF";
                }
                if (reason==null && p!=Pillar.D && anchor==null) anchor=o;
                BigDecimal normalized=o==null?null:o.value();
                if (reason==null && rule.transformation()==MetricScoringRule.Transformation.DISTINCT_RECORDED_YEARS)
                    normalized=BigDecimal.valueOf(input.dividends().distinctRecordYears());
                BigDecimal points=reason==null?rule.score(normalized):null;
                boolean stabilityFloor=contributions.stream().anyMatch(c -> c.pillar()==Pillar.F && c.metricWeight().signum()>0 && c.metricScore()!=null && c.metricScore().signum()==0);
                boolean capped=p==Pillar.V && (distressed || stabilityFloor) && points!=null;
                if (capped) points=BigDecimal.ZERO.setScale(6);
                BigDecimal contribution=points==null?null:points.multiply(rule.weight());
                if (points!=null) { sum=sum.add(contribution); covered=covered.add(rule.weight()); }
                else if (rule.weight().signum()>0) complete=false;
                if (reason!=null && rule.required() && model.weights().get(p).signum()>0) reasons.add(reason);
                contributions.add(new Contribution(p,rule.metric(),o==null?null:o.id(),normalized,points,rule.id(),rule.weight(),model.weights().get(p),
                        contribution,points==null?"UNAVAILABLE":capped?"CONTEXT_CAPPED":"EVALUATED",capped?"DISTRESS_VALUATION_CAP":reason,
                        "ABSOLUTE_PIECEWISE_LINEAR_V1; "+rule.direction()+"; "+rule.transformation()));
            }
            if (covered.compareTo(BigDecimal.ONE)!=0) complete=false;
            scores.put(p,profile!=null && complete && reliable?sum.setScale(6,RoundingMode.HALF_UP):null); coverage.put(p,covered);
            if (model.weights().get(p).signum()>0 && scores.get(p)==null && profile!=null) reasons.add("MISSING_"+p+"_PILLAR_EVIDENCE");
        }
        boolean eligible=profile!=null && reliable && reasons.isEmpty() && model.mandatoryPillars().stream().allMatch(p -> scores.get(p)!=null);
        BigDecimal overall=null;
        if (eligible) {
            overall=BigDecimal.ZERO;
            for (var p:model.mandatoryPillars()) overall=overall.add(scores.get(p).multiply(model.weights().get(p)));
            overall=overall.setScale(6,RoundingMode.HALF_UP);
        }
        Status status=profile==null?Status.UNSUPPORTED_PROFILE:eligible?Status.SCORED:reasons.stream().anyMatch(r -> r.startsWith("STALE"))?Status.STALE_DATA
                :scores.values().stream().anyMatch(Objects::nonNull)?Status.PARTIAL:Status.INSUFFICIENT_DATA;
        var risks=new ArrayList<>(new FundamentalRiskEvaluator().evaluate(input,model,List.copyOf(reasons),now));
        for (var c:contributions) if (c.pillar()==Pillar.F && c.metricWeight().signum()>0 && c.metricScore()!=null && c.metricScore().signum()==0)
            risks.add(new RiskFlag("STABILITY_RULE_FLOOR",Severity.CRITICAL,c.metric(),c.observationId()==null?List.of():List.of(c.observationId()),c.normalizedValue(),
                    "eligible weighted stability rule "+c.ruleId()+" scores zero under its configured curve",
                    "The profile stability floor caps valuation at zero; this is a model condition, not a regulatory or account restriction",
                    input.observations().stream().filter(o -> Objects.equals(o.id(),c.observationId())).map(FinancialObservation::fetchedAt).findFirst().orElse(null)));
        if (model.mandatoryPillars().size()!=4) risks.add(new RiskFlag("CUSTOM_PILLAR_POLICY",Severity.INFO,null,List.of(),null,
                "explicit zero-weight/nonmandatory pillars in versioned configuration","This model variant is not comparable to the four-pillar baseline",null));
        var snapshots=input.observations().stream().filter(o -> o.snapshotId()!=null).map(o -> "fund:"+o.snapshotId()).distinct().sorted().toList();
        Instant cutoff=input.observations().stream().map(FinancialObservation::fetchedAt).filter(Objects::nonNull).filter(t -> !t.isAfter(now)).max(Comparator.naturalOrder()).orElse(null);
        return new ScoringResult(null,input.instrumentUid(),input.assetUid(),input.name(),input.classification(),profileId,model.modelVersion(),model.configurationVersion(),
                configurationHash,inputHash,now,cutoff,status,overall,scores,contributions,coverage,risks,List.copyOf(reasons),snapshots,input,
                contributions.stream().map(Contribution::ruleId).sorted().toList());
    }
}
