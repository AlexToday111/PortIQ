package ru.ba6kir.tradelab.dividends.domain.model;

import java.math.BigDecimal;
import java.time.*;
import java.util.Set;

/** A provider observation, never an account cash flow. Dates use the provider's UTC calendar. */
public record DividendEvent(String instrumentUid, BigDecimal amountPerShare, String currency,
        LocalDate declaredDate, LocalDate lastBuyDate, LocalDate recordDate, LocalDate paymentDate,
        String dividendType, String regularity, BigDecimal providerYield, BigDecimal providerReferencePrice,
        String referenceCurrency, Instant providerCreatedAt, Instant retrievedAt, String source,
        String contentHash, String identityAnchor, Set<String> issues, String rawPayload) {
    public DividendEvent { issues=Set.copyOf(issues); }
    public enum Status { ANNOUNCED, CANCELLED, HISTORICAL_RECORDED, UNKNOWN }
    public record Observation(DividendEvent event, boolean ambiguous, Instant firstSeen, Instant lastSeen) {}
    public Status status(LocalDate asOf) {
        if ("Cancelled".equalsIgnoreCase(dividendType)) return Status.CANCELLED;
        if (recordDate==null) return Status.UNKNOWN;
        if (!recordDate.isAfter(asOf)) return Status.HISTORICAL_RECORDED;
        return declaredDate!=null && !declaredDate.isAfter(asOf) ? Status.ANNOUNCED : Status.UNKNOWN;
    }
    public boolean usable() {
        return amountPerShare!=null && amountPerShare.signum()>0 && currency!=null && recordDate!=null
                && issues.stream().noneMatch(s -> s.startsWith("SUSPECT") || s.equals("MISSING_AMOUNT") || s.equals("MISSING_CURRENCY"));
    }
}
