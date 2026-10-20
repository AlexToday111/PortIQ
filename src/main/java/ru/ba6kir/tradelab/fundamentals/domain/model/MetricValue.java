package ru.ba6kir.tradelab.fundamentals.domain.model;

import java.math.BigDecimal;

public record MetricValue(MetricCode code, BigDecimal value, MetricCode.Unit unit, String currency,
                          String reportingPeriod, QualityStatus quality, String reason, String rawValue) { }
