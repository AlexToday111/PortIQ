package ru.ba6kir.tradelab.analysis.scoring.domain.rule;

import java.time.*;
import java.util.*;
import ru.ba6kir.tradelab.analysis.domain.metric.*;
import ru.ba6kir.tradelab.analysis.scoring.domain.model.ScoringInput;
import ru.ba6kir.tradelab.analysis.scoring.domain.profile.ScoringModel;
import ru.ba6kir.tradelab.fundamentals.domain.model.QualityStatus;
import static ru.ba6kir.tradelab.analysis.scoring.domain.model.ScoringResult.Pillar;

public class ScoringEligibilityValidator {
    public record Selection(FinancialObservation observation,String reason) {}
    public Selection select(ScoringInput input,MetricScoringRule rule,ScoringModel model,Instant now) {
        var candidates=input.observations().stream().filter(o -> o.metricCode()==rule.metric() && o.origin()==rule.origin()).toList();
        // A missing placeholder cannot hide a second actual source variant. Definitions never silently select a better number.
        var actual=candidates.stream().filter(o -> o.value()!=null).toList();
        if (actual.size()>1) return new Selection(null,"AMBIGUOUS_"+rule.metric());
        var o=actual.isEmpty()?candidates.stream().findFirst().orElse(null):actual.getFirst();
        if (o==null || o.value()==null) return new Selection(o,"MISSING_"+rule.metric());
        String invalid=basic(input,o,model,now);
        if (invalid!=null) return new Selection(o,invalid+"_"+rule.metric());
        if (o.unit()!=rule.unit()) return new Selection(o,"UNIT_MISMATCH_"+rule.metric());
        if (rule.requiredMethodology()!=null && !rule.requiredMethodology().equals(o.methodology())) return new Selection(o,"UNVERIFIED_DEFINITION_"+rule.metric());
        if (rule.pillar()==Pillar.D) {
            if (!Objects.equals(input.instrumentUid(),o.instrumentUid())) return new Selection(o,"DIVIDEND_INSTRUMENT_MISMATCH");
            String reason=dividendReason(input,model,now);
            if (reason!=null) return new Selection(o,reason);
            if (rule.metric()==AnalysisMetric.DIVIDEND_YIELD && (o.value().signum()<=0 || o.value().compareTo(model.extremeYield())>=0))
                return new Selection(o,"UNRELIABLE_DIVIDEND_YIELD");
            if (rule.metric()==AnalysisMetric.DIVIDEND_YIELD && (o.providerAsOfDate()==null || o.providerAsOfDate().isAfter(now)
                    || o.providerAsOfDate().plus(Duration.ofDays(7)).isBefore(now))) return new Selection(o,"INVALID_DIVIDEND_PRICE_DATE");
            return new Selection(o,null);
        }
        if (!o.usable()) return new Selection(o,"INCOMPATIBLE_REPORTING_PERIOD_"+rule.metric());
        var earnings=context(input,AnalysisMetric.NET_INCOME,model,now);
        if (earnings.reason()!=null && rule.pillar()==Pillar.V) return new Selection(o,"MISSING_OR_INVALID_VALUATION_CONTEXT_NET_INCOME");
        if (earnings.reason()==null && !aligned(earnings.observation(),o)) return new Selection(o,"INCOMPATIBLE_REPORTING_PERIOD_"+rule.metric());
        if (rule.pillar()==Pillar.V && (o.providerAsOfDate()==null || o.providerAsOfDate().isAfter(now)
                || o.providerAsOfDate().plus(Duration.ofDays(7)).isBefore(now))) return new Selection(o,"INVALID_VALUATION_AS_OF_"+rule.metric());
        return new Selection(o,null);
    }
    public Selection context(ScoringInput input,AnalysisMetric code,ScoringModel model,Instant now) {
        var rows=input.observations().stream().filter(o -> o.metricCode()==code && o.value()!=null).toList();
        if (rows.size()!=1) return new Selection(null,"MISSING_OR_AMBIGUOUS_"+code);
        var o=rows.getFirst(); var reason=basic(input,o,model,now);
        if (reason==null && (!o.usable() || o.unit()!=code.unit() || o.instrumentUid()!=null)) reason="INVALID_CONTEXT";
        return new Selection(o,reason==null?null:reason+"_"+code);
    }
    private String basic(ScoringInput input,FinancialObservation o,ScoringModel model,Instant now) {
        if (o.id()==null || o.id().isBlank() || o.source()==null || o.instrumentUid()==null && o.snapshotId()==null) return "SOURCE_LINEAGE_MISSING";
        if (!Objects.equals(input.assetUid(),o.assetUid())) return "ASSET_MISMATCH";
        if (o.instrumentUid()!=null && !Objects.equals(input.instrumentUid(),o.instrumentUid())) return "INSTRUMENT_MISMATCH";
        if (o.qualityStatus().contains(QualityStatus.STALE) || o.fetchedAt()==null || !o.fetchedAt().plus(model.observationMaxAge()).isAfter(now)) return "STALE_FINANCIAL_DATA";
        if (o.fetchedAt().isAfter(now) || o.providerAsOfDate()!=null && o.providerAsOfDate().isAfter(now)
                || o.reportingEndDate()!=null && o.reportingEndDate().isAfter(now.atOffset(ZoneOffset.UTC).toLocalDate())) return "FUTURE_SOURCE_DATE";
        if (o.reportingEndDate()!=null && o.reportingEndDate().atStartOfDay(ZoneOffset.UTC).toInstant().plus(model.financialPeriodMaxAge()).isBefore(now)) return "STALE_FINANCIAL_PERIOD";
        if (!o.validValue()) return "INVALID_QUALITY_OR_VALUE";
        return null;
    }
    private boolean aligned(FinancialObservation earnings,FinancialObservation o) {
        if (!Objects.equals(earnings.snapshotId(),o.snapshotId())) return false;
        if (!Objects.equals(earnings.reportingEndDate(),o.reportingEndDate())) return false;
        if (o.observationType()==FinancialObservation.ObservationType.POINT_IN_TIME) return true;
        return earnings.compatible(o);
    }
    public String dividendReason(ScoringInput input,ScoringModel model,Instant now) {
        var d=input.dividends(); var today=now.atOffset(ZoneOffset.UTC).toLocalDate();
        if (d==null || !d.reliable() || d.coverageFrom()==null || d.coverageTo()==null || d.asOf()==null || !d.asOf().equals(today)
                || d.coverageFrom().isAfter(today.minusDays(model.minimumDividendDays())) || d.coverageTo().isBefore(today)
                || d.recordedEvents()<model.minimumDividendYears() || d.distinctRecordYears()<model.minimumDividendYears()
                || d.distinctRecordYears()>d.recordedEvents() || d.eventHashes().isEmpty()) return "UNRELIABLE_DIVIDEND_HISTORY";
        if (d.fetchedAt()==null || d.fetchedAt().isAfter(now) || !d.fetchedAt().plus(model.observationMaxAge()).isAfter(now)) return "STALE_DIVIDEND_DATA";
        return null;
    }
}
