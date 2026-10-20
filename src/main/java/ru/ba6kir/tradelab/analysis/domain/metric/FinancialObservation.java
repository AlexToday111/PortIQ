package ru.ba6kir.tradelab.analysis.domain.metric;

import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import ru.ba6kir.tradelab.fundamentals.domain.model.QualityStatus;
import ru.ba6kir.tradelab.analysis.domain.metric.AnalysisMetric.Unit;

public record FinancialObservation(String id,String assetUid,Long snapshotId,AnalysisMetric metricCode,BigDecimal value,Unit unit,String currency,
        ObservationType observationType,String reportingPeriod,LocalDate periodStart,LocalDate reportingEndDate,Instant providerAsOfDate,
        Instant fetchedAt,String source,Origin origin,Set<QualityStatus> qualityStatus,String qualityReason,String methodology,List<String> inputIds,
        String instrumentUid) {
    public enum ObservationType { ANNUAL,QUARTERLY,TTM,POINT_IN_TIME,UNKNOWN }
    public enum Origin { PROVIDER,DERIVED }
    public FinancialObservation { qualityStatus=Set.copyOf(qualityStatus); inputIds=List.copyOf(inputIds); }
    public boolean validValue() {
        if (value==null || !qualityStatus.equals(Set.of(QualityStatus.VALID)) || unit==Unit.UNVERIFIED_RATE) return false;
        if ((unit==Unit.MONEY || unit==Unit.PER_SHARE) && (currency==null || !currency.matches("[A-Z]{3}"))) return false;
        if (Set.of(AnalysisMetric.PE,AnalysisMetric.PB,AnalysisMetric.PS,AnalysisMetric.EV_EBITDA).contains(metricCode) && value.signum()<=0) return false;
        return true;
    }
    public boolean usable() {
        return validValue() && observationType!=ObservationType.UNKNOWN && reportingEndDate!=null
                && (observationType==ObservationType.POINT_IN_TIME || periodStart!=null && !periodStart.isAfter(reportingEndDate));
    }
    public boolean compatible(FinancialObservation other) {
        return usable() && other.usable() && observationType==other.observationType && Objects.equals(periodStart,other.periodStart)
                && Objects.equals(reportingEndDate,other.reportingEndDate) && Objects.equals(reportingPeriod,other.reportingPeriod);
    }
}
