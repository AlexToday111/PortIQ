package ru.ba6kir.tradelab.analysis.reporting.domain;

import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import ru.ba6kir.tradelab.analysis.domain.metric.AnalysisMetric;

public record AnalysisExplanation(String code,AnalysisMetric metric,BigDecimal observedValue,AnalysisMetric.Unit unit,String currency,
        BigDecimal normalizedValue,String ruleId,String thresholdReference,Direction direction,BigDecimal scoreContribution,
        String observationType,String reportingPeriod,LocalDate periodStart,LocalDate periodEnd,Instant asOf,
        String observationId,String sourceReference,String russianText) {
    public enum Direction { POSITIVE,NEGATIVE,NEUTRAL,UNAVAILABLE }
}
