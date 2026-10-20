package ru.ba6kir.tradelab.fundamentals.domain.model;

import java.time.Instant;

/** Company/brand labels are provider metadata, not a fabricated legal issuer identifier. */
public record AssetIdentity(String assetUid, String assetName, String companyName, String brandUid,
                            String instrumentUid, String source, Instant verifiedAt, String providerSector) {
    public AssetIdentity(String assetUid,String assetName,String companyName,String brandUid,String instrumentUid,String source,Instant verifiedAt) {
        this(assetUid,assetName,companyName,brandUid,instrumentUid,source,verifiedAt,null);
    }
}
