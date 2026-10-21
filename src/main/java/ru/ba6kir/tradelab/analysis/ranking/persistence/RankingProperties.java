package ru.ba6kir.tradelab.analysis.ranking.persistence;

import java.time.Duration;
import java.util.*;
import org.springframework.boot.context.properties.ConfigurationProperties;
import ru.ba6kir.tradelab.analysis.ranking.domain.RankingPolicy;
import ru.ba6kir.tradelab.market.application.InstrumentService;

@ConfigurationProperties("tradelab.ranking")
public record RankingProperties(String policyVersion,Duration snapshotTtl,Duration refreshCooldown,Duration scoreMaxAge,
        Duration quoteMaxAge,Duration metadataMaxAge,List<String> instrumentUids) {
    public RankingProperties {
        instrumentUids=instrumentUids==null?List.of():instrumentUids.stream().map(InstrumentService::validateUid).distinct().sorted().toList();
        if (instrumentUids.size()>30) throw new IllegalArgumentException("At most 30 ranking UIDs supported");
        new RankingPolicy(policyVersion,snapshotTtl,refreshCooldown,scoreMaxAge,quoteMaxAge,metadataMaxAge,Duration.ofDays(365));
    }
    public RankingPolicy policy(Duration classificationAge) { return new RankingPolicy(policyVersion,snapshotTtl,refreshCooldown,scoreMaxAge,quoteMaxAge,metadataMaxAge,classificationAge); }
}
