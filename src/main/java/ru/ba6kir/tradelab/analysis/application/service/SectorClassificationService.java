package ru.ba6kir.tradelab.analysis.application.service;

import java.time.*;
import java.util.*;
import ru.ba6kir.tradelab.analysis.application.port.AnalysisStore;
import ru.ba6kir.tradelab.analysis.domain.sector.SectorClassification;
import ru.ba6kir.tradelab.fundamentals.domain.model.AssetIdentity;
import static ru.ba6kir.tradelab.analysis.domain.sector.SectorClassification.*;

public class SectorClassificationService {
    private final AnalysisStore store;
    private final Map<String,SectorClassification> seeds;
    private final Clock clock;
    private final Duration maxAge;
    public SectorClassificationService(AnalysisStore store,List<SectorClassification> seeds,Clock clock,Duration maxAge) {
        this.store=store; this.clock=clock; this.maxAge=maxAge;
        var map=new TreeMap<String,SectorClassification>();
        for (var seed:seeds) if (map.putIfAbsent(seed.assetUid(),seed)!=null) throw new IllegalArgumentException("Duplicate classification asset");
        this.seeds=Map.copyOf(map);
    }
    public SectorClassification classify(AssetIdentity identity) {
        return classify(identity,true);
    }
    public SectorClassification classify(AssetIdentity identity,boolean persist) {
        var saved=store.classification(identity.assetUid()).orElse(null); var seed=seeds.get(identity.assetUid());
        var manual=selectManual(saved,seed); var provider=provider(identity);
        SectorClassification result;
        if (manual!=null && manual.override()) result=manual;
        else if (provider.sector()!=Sector.UNKNOWN) {
            if (manual!=null && manual.fresh(clock.instant(),maxAge) && manual.sector()==provider.sector())
                result=new SectorClassification(identity.assetUid(),manual.issuerKey(),provider.sector(),manual.businessType(),Source.PROVIDER_AND_MANUAL,
                        manual.verifiedAt().isBefore(provider.verifiedAt())?manual.verifiedAt():provider.verifiedAt(),false,
                        manual.source()==Source.PROVIDER_AND_MANUAL?manual.evidence():"provider:GetAssetBy.brand.sector; "+manual.evidence(),identity.providerSector());
            else result=provider;
        } else result=manual==null?provider:manual;
        if (persist && result.source()!=Source.UNKNOWN) store.saveClassification(result);
        return result;
    }
    private SectorClassification selectManual(SectorClassification saved,SectorClassification seed) {
        if (saved!=null && saved.source()!=Source.MANUAL_VERIFIED && saved.source()!=Source.PROVIDER_AND_MANUAL) saved=null;
        if (saved==null) return seed;
        if (seed==null) return saved;
        if (saved.override()!=seed.override()) return seed.override()?seed:saved;
        return seed.verifiedAt().isBefore(saved.verifiedAt())?saved:seed;
    }
    private SectorClassification provider(AssetIdentity identity) {
        String raw=identity.providerSector();
        Sector sector=raw==null?Sector.UNKNOWN:switch(raw.toLowerCase(Locale.ROOT)) {
            case "financial", "financials" -> Sector.FINANCIALS;
            case "it" -> Sector.INFORMATION_TECHNOLOGY;
            case "telecom" -> Sector.TELECOMMUNICATIONS;
            case "utilities" -> Sector.UTILITIES;
            case "consumer" -> Sector.CONSUMER;
            case "industrials" -> Sector.INDUSTRIALS;
            case "other" -> Sector.OTHER;
            default -> Sector.UNKNOWN;
        };
        if (sector==Sector.UNKNOWN || identity.verifiedAt()==null || identity.verifiedAt().isAfter(clock.instant()))
            return SectorClassification.unknown(identity.assetUid(),raw);
        return new SectorClassification(identity.assetUid(),null,sector,sector==Sector.FINANCIALS?BusinessType.UNKNOWN:BusinessType.NON_FINANCIAL,
                Source.PROVIDER_VERIFIED,identity.verifiedAt(),false,"GetAssetBy.brand.sector exact-label mapping",raw);
    }
    public boolean fresh(SectorClassification c) { return c.fresh(clock.instant(),maxAge); }
    public List<SectorClassification> universe() {
        var all=new TreeMap<String,SectorClassification>(); store.classifications().forEach(c -> all.put(c.assetUid(),c));
        seeds.forEach((uid,seed) -> { var existing=all.get(uid); var candidate=selectManual(existing,seed);
            if (candidate!=null && (existing==null || seed.override() || existing.source()!=Source.PROVIDER_VERIFIED || existing.sector()==seed.sector())) all.put(uid,candidate); });
        return all.values().stream().limit(200).toList();
    }
}
