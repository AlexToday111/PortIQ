package ru.ba6kir.tradelab.fundamentals.domain.model;

import java.time.Instant;

/** Calendar event only; neither a statement nor proof that publication took place. */
public record FinancialReportEvent(String instrumentUid, Instant reportDate, Integer reportingYear, Integer periodNumber,
                                   String periodType, Instant providerCreatedAt, String source, Instant fetchedAt) { }
