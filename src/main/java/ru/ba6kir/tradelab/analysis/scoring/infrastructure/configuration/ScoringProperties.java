package ru.ba6kir.tradelab.analysis.scoring.infrastructure.configuration;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.*;
import org.springframework.boot.context.properties.ConfigurationProperties;
import ru.ba6kir.tradelab.analysis.domain.metric.AnalysisMetric;
import ru.ba6kir.tradelab.analysis.scoring.domain.model.ScoringResult.Pillar;
import ru.ba6kir.tradelab.analysis.scoring.domain.profile.*;

@ConfigurationProperties("tradelab.scoring")
public record ScoringProperties(String modelVersion,String configurationVersion,Map<Pillar,BigDecimal> weights,Set<Pillar> mandatoryPillars,
        Duration observationMaxAge,Duration financialPeriodMaxAge,int minimumDividendDays,int minimumDividendYears,
        BigDecimal extremeYield,BigDecimal highLeverage,List<ScoringModel.Profile> profiles) {
    public ScoringProperties {
        profiles=profiles==null?List.of():List.copyOf(profiles);
        build(modelVersion,configurationVersion,weights,mandatoryPillars,observationMaxAge,financialPeriodMaxAge,minimumDividendDays,minimumDividendYears,extremeYield,highLeverage,profiles);
    }
    public ScoringModel model() { return build(modelVersion,configurationVersion,weights,mandatoryPillars,observationMaxAge,financialPeriodMaxAge,minimumDividendDays,minimumDividendYears,extremeYield,highLeverage,profiles); }
    private static ScoringModel build(String version,String config,Map<Pillar,BigDecimal> weights,Set<Pillar> mandatory,Duration age,Duration periodAge,int days,int years,
            BigDecimal yield,BigDecimal leverage,List<ScoringModel.Profile> overrides) {
        var profiles=new TreeMap<>(ScoringProfiles.defaults()); var seen=new HashSet<String>();
        for (var p:overrides) {
            if (!profiles.containsKey(p.id()) || !seen.add(p.id())) throw new IllegalArgumentException("Unknown/duplicate scoring profile");
            if (p.id().equals("BANK") && p.rules().stream().anyMatch(r -> Set.of(AnalysisMetric.NET_DEBT_EBITDA,AnalysisMetric.DEBT_EBITDA,
                    AnalysisMetric.CURRENT_RATIO,AnalysisMetric.EV_EBITDA).contains(r.metric()))) throw new IllegalArgumentException("Industrial rules cannot score banks");
            profiles.put(p.id(),p);
        }
        return new ScoringModel(version,config,weights,mandatory,age,periodAge,days,years,yield,leverage,profiles);
    }
}
