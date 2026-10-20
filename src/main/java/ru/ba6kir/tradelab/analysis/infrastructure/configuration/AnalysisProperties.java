package ru.ba6kir.tradelab.analysis.infrastructure.configuration;

import java.time.*;
import java.util.*;
import org.springframework.boot.context.properties.ConfigurationProperties;
import ru.ba6kir.tradelab.analysis.domain.sector.*;
import ru.ba6kir.tradelab.analysis.domain.metric.AnalysisMetric;
import static ru.ba6kir.tradelab.analysis.domain.sector.SectorClassification.*;

@ConfigurationProperties("tradelab.analysis")
public record AnalysisProperties(int minimumPeers,Duration classificationMaxAge,List<Seed> classifications,List<Profile> profiles) {
    public record Seed(String assetUid,String issuerKey,Sector sector,BusinessType businessType,Instant verifiedAt,boolean override,String evidence) {
        public Seed {
            if (assetUid==null || !assetUid.matches("[0-9a-fA-F]{8}(-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12}")
                    || issuerKey==null || !issuerKey.matches("[A-Za-z0-9:._-]{3,80}") || sector==null || sector==Sector.UNKNOWN
                    || businessType==null || businessType==BusinessType.UNKNOWN || verifiedAt==null || verifiedAt.isAfter(Instant.now())
                    || evidence==null || evidence.isBlank() || evidence.length()>1000)
                throw new IllegalArgumentException("Manual classification requires verified asset/issuer identity, sector/type, timestamp and evidence");
            assetUid=assetUid.toLowerCase(Locale.ROOT);
            new SectorClassification(assetUid,issuerKey,sector,businessType,Source.MANUAL_VERIFIED,verifiedAt,override,evidence,null);
        }
        public SectorClassification classification() { return new SectorClassification(assetUid,issuerKey,sector,businessType,Source.MANUAL_VERIFIED,verifiedAt,override,evidence,null); }
    }
    public record Profile(Sector sector,BusinessType businessType,Set<AnalysisMetric> required,Set<AnalysisMetric> optional) {
        public Profile {
            if (sector==null || sector==Sector.UNKNOWN || businessType==null || businessType==BusinessType.UNKNOWN) throw new IllegalArgumentException("Explicit profile group required");
            if (optional==null) optional=Set.of();
            var profile=new MetricProfile(sector+"/"+businessType,required==null?Set.of():required,optional);
            required=profile.required(); optional=profile.optional();
            new SectorClassification("profile",null,sector,businessType,Source.UNKNOWN,null,false,null,null);
            if (businessType==BusinessType.BANK && !Collections.disjoint(profile.metrics(),Set.of(AnalysisMetric.DEBT_EBITDA,AnalysisMetric.NET_DEBT_EBITDA,
                    AnalysisMetric.EV_EBITDA,AnalysisMetric.CURRENT_RATIO,AnalysisMetric.DEBT_EQUITY)))
                throw new IllegalArgumentException("Industrial leverage/liquidity metrics cannot define bank profiles");
        }
        public MetricProfile profile() { return new MetricProfile("CONFIGURED_"+sector+"_"+businessType,required,optional); }
    }
    public AnalysisProperties {
        if (minimumPeers<5 || minimumPeers>100 || classificationMaxAge==null || classificationMaxAge.compareTo(Duration.ofDays(1))<0
                || classificationMaxAge.compareTo(Duration.ofDays(3650))>0) throw new IllegalArgumentException("Invalid peer minimum or classification age");
        classifications=classifications==null?List.of():List.copyOf(classifications); profiles=profiles==null?List.of():List.copyOf(profiles);
        if (classifications.size()>200 || classifications.stream().map(Seed::assetUid).distinct().count()!=classifications.size()
                || profiles.stream().map(p -> p.sector()+"/"+p.businessType()).distinct().count()!=profiles.size()) throw new IllegalArgumentException("Duplicate or excessive analysis configuration");
        var groups=new HashMap<String,String>();
        for (var seed:classifications) {
            String group=seed.sector()+"/"+seed.businessType(); var previous=groups.putIfAbsent(seed.issuerKey(),group);
            if (previous!=null && !previous.equals(group)) throw new IllegalArgumentException("Issuer classification conflict");
        }
    }
}
