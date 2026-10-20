package ru.ba6kir.tradelab.analysis.scoring.domain.model;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import ru.ba6kir.tradelab.analysis.domain.metric.AnalysisMetric;
import ru.ba6kir.tradelab.analysis.domain.sector.SectorClassification;

public record ScoringResult(Long id,String instrumentUid,String assetUid,String name,SectorClassification classification,String profileId,
        String modelVersion,String configurationVersion,String configurationFingerprint,String inputFingerprint,Instant evaluatedAt,Instant dataCutoff,
        Status status,BigDecimal overallScore,Map<Pillar,BigDecimal> pillarScores,List<Contribution> contributions,
        Map<Pillar,BigDecimal> coverage,List<RiskFlag> risks,List<String> eligibilityReasons,List<String> inputSnapshotIds,
        ScoringInput input,List<String> explanationReferences) {
    public enum Pillar { Q,F,V,D }
    public enum Status { SCORED,PARTIAL,INSUFFICIENT_DATA,STALE_DATA,UNSUPPORTED_PROFILE,FAILED }
    public enum Severity { INFO,WARNING,CRITICAL }
    public record Contribution(Pillar pillar,AnalysisMetric metric,String observationId,BigDecimal normalizedValue,BigDecimal metricScore,
            String ruleId,BigDecimal metricWeight,BigDecimal pillarWeight,BigDecimal contribution,String status,String reason,String methodology) {}
    public record RiskFlag(String ruleId,Severity severity,AnalysisMetric metric,List<String> observationIds,BigDecimal actualValue,
            String condition,String explanation,Instant observedAt) { public RiskFlag { observationIds=List.copyOf(observationIds); } }
    public ScoringResult {
        var scores=new EnumMap<Pillar,BigDecimal>(Pillar.class); scores.putAll(pillarScores); pillarScores=Collections.unmodifiableMap(scores);
        var covered=new EnumMap<Pillar,BigDecimal>(Pillar.class); covered.putAll(coverage); coverage=Collections.unmodifiableMap(covered);
        contributions=List.copyOf(contributions); risks=List.copyOf(risks); eligibilityReasons=List.copyOf(eligibilityReasons);
        inputSnapshotIds=List.copyOf(inputSnapshotIds); explanationReferences=List.copyOf(explanationReferences);
    }
    public ScoringResult withId(long value) { return new ScoringResult(value,instrumentUid,assetUid,name,classification,profileId,modelVersion,configurationVersion,
            configurationFingerprint,inputFingerprint,evaluatedAt,dataCutoff,status,overallScore,pillarScores,contributions,coverage,risks,eligibilityReasons,inputSnapshotIds,input,explanationReferences); }
}
