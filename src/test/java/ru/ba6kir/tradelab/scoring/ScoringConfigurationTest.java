package ru.ba6kir.tradelab.scoring;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.*;
import ru.ba6kir.tradelab.analysis.scoring.infrastructure.configuration.ScoringProperties;
import static org.assertj.core.api.Assertions.*;

class ScoringConfigurationTest {
    final ApplicationContextRunner runner=new ApplicationContextRunner().withInitializer(new ConfigDataApplicationContextInitializer()).withUserConfiguration(Config.class);
    @Test void defaultModelBindsExperimentalFourPillarWeightsAndProfilesWithoutCredentials() {
        runner.run(c -> { assertThat(c).hasNotFailed(); var p=c.getBean(ScoringProperties.class); assertThat(p.model().profiles()).hasSize(4); assertThat(p.model().mandatoryPillars()).hasSize(4); });
    }
    @Test void invalidWeightsEvidenceAndUnversionedModelsFailStartup() {
        for (String property:java.util.List.of("SCORING_WEIGHT_Q=0.7","SCORING_WEIGHT_Q=-0.1","SCORING_MANDATORY_PILLARS=Q,F,V",
                "SCORING_OBSERVATION_MAX_AGE=0s","SCORING_MINIMUM_DIVIDEND_YEARS=0","SCORING_MINIMUM_DIVIDEND_DAYS=1","SCORING_EXTREME_YIELD=0","SCORING_MODEL_VERSION="))
            runner.withPropertyValues(property).run(c -> assertThat(c).hasFailed());
        var bank=ru.ba6kir.tradelab.analysis.scoring.domain.profile.ScoringProfiles.defaults().get("BANK");
        var weakened=bank.rules().stream().map(r -> r.metric()==ru.ba6kir.tradelab.analysis.domain.metric.AnalysisMetric.CAPITAL_ADEQUACY
                ?new ru.ba6kir.tradelab.analysis.scoring.domain.rule.MetricScoringRule(r.id(),r.pillar(),r.metric(),r.unit(),r.origin(),r.direction(),r.required(),r.weight(),r.points(),null,r.transformation()):r).toList();
        assertThatThrownBy(() -> new ru.ba6kir.tradelab.analysis.scoring.domain.profile.ScoringModel.Profile("BANK",weakened,bank.requiredContext(),bank.optionalContext())).isInstanceOf(IllegalArgumentException.class);
    }
    @Configuration(proxyBeanMethods=false) @EnableConfigurationProperties(ScoringProperties.class) static class Config {}
}
