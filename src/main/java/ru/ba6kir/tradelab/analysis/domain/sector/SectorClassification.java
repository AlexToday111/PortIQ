package ru.ba6kir.tradelab.analysis.domain.sector;

import java.time.*;
import java.util.Objects;

public record SectorClassification(String assetUid,String issuerKey,Sector sector,BusinessType businessType,
        Source source,Instant verifiedAt,boolean override,String evidence,String providerSector) {
    public enum Sector { FINANCIALS,OIL_AND_GAS,METALS_AND_MINING,INFORMATION_TECHNOLOGY,TELECOMMUNICATIONS,
        UTILITIES,CONSUMER,TRANSPORT,CONSTRUCTION,CHEMICALS,INDUSTRIALS,OTHER,UNKNOWN }
    public enum BusinessType { BANK,NON_BANK_FINANCIAL,NON_FINANCIAL,UNKNOWN }
    public enum Source { PROVIDER_VERIFIED,MANUAL_VERIFIED,PROVIDER_AND_MANUAL,UNKNOWN }
    public SectorClassification {
        Objects.requireNonNull(assetUid); Objects.requireNonNull(sector); Objects.requireNonNull(businessType); Objects.requireNonNull(source);
        if ((businessType==BusinessType.BANK || businessType==BusinessType.NON_BANK_FINANCIAL) && sector!=Sector.FINANCIALS
                || businessType==BusinessType.NON_FINANCIAL && (sector==Sector.FINANCIALS || sector==Sector.UNKNOWN))
            throw new IllegalArgumentException("Inconsistent sector and business type");
        if (source!=Source.UNKNOWN && (verifiedAt==null || evidence==null || evidence.isBlank()))
            throw new IllegalArgumentException("Verified classification requires evidence and timestamp");
    }
    public boolean fresh(Instant now,Duration maxAge) {
        return source!=Source.UNKNOWN && verifiedAt!=null && !verifiedAt.isAfter(now) && verifiedAt.plus(maxAge).isAfter(now);
    }
    public static SectorClassification unknown(String uid,String hint) {
        return new SectorClassification(uid,null,Sector.UNKNOWN,BusinessType.UNKNOWN,Source.UNKNOWN,null,false,"NO_VERIFIED_CLASSIFICATION",hint);
    }
}
