package ru.ba6kir.tradelab.scoring;

import java.math.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import ru.ba6kir.tradelab.analysis.domain.metric.*;
import ru.ba6kir.tradelab.analysis.domain.sector.SectorClassification;
import ru.ba6kir.tradelab.analysis.scoring.domain.model.*;
import ru.ba6kir.tradelab.analysis.scoring.domain.profile.*;
import ru.ba6kir.tradelab.analysis.scoring.domain.rule.*;
import static ru.ba6kir.tradelab.analysis.domain.metric.AnalysisMetric.*;
import static ru.ba6kir.tradelab.analysis.scoring.domain.model.ScoringResult.*;
import static org.assertj.core.api.Assertions.*;
import static ru.ba6kir.tradelab.scoring.ScoringTestData.*;

class FundamentalScoringEngineTest {
    @Test void fourPillarsUseExactConfiguredWeightsAndProduceTraceableOverallScore() {
        var r=evaluate(input("OIL_AND_GAS"));
        assertThat(r.status()).isEqualTo(Status.SCORED); assertThat(r.overallScore()).isEqualByComparingTo("79.975");
        assertThat(r.pillarScores().get(Pillar.Q)).isEqualByComparingTo("81"); assertThat(r.pillarScores().get(Pillar.F)).isEqualByComparingTo("83.5");
        assertThat(r.pillarScores().get(Pillar.V)).isEqualByComparingTo("78"); assertThat(r.pillarScores().get(Pillar.D)).isEqualByComparingTo("75");
        assertThat(r.contributions()).hasSize(8).allMatch(c -> c.observationId()!=null && c.ruleId()!=null && c.contribution()!=null);
        assertThat(r.inputSnapshotIds()).containsExactly("fund:1"); assertThat(r.input()).isNotNull();
    }
    @ParameterizedTest @ValueSource(strings={"BANK","OIL_AND_GAS","METALS_AND_MINING","GENERAL_NON_FINANCIAL"})
    void selectsSupportedProfilesAndRepeatsDeterministically(String profile) {
        var first=evaluate(input(profile)); assertThat(first.profileId()).isEqualTo(profile); assertThat(first.status()).isEqualTo(Status.SCORED);
        assertThat(first).isEqualTo(evaluate(input(profile))); assertThat(first.overallScore()).isBetween(BigDecimal.ZERO,BigDecimal.valueOf(100));
        if (profile.equals("BANK")) assertThat(first.contributions()).noneMatch(c -> c.metric()==NET_DEBT_EBITDA || c.metric()==EV_EBITDA);
    }
    @Test void weightsMustSumToOneAndPillarsCannotBeDroppedSilently() {
        var m=model(); var bad=new EnumMap<>(m.weights()); bad.put(Pillar.D,new BigDecimal("0.10"));
        assertThatThrownBy(() -> new ScoringModel(m.modelVersion(),m.configurationVersion(),bad,m.mandatoryPillars(),m.observationMaxAge(),m.financialPeriodMaxAge(),1095,3,m.extremeYield(),m.highLeverage(),m.profiles())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ScoringModel(m.modelVersion(),m.configurationVersion(),m.weights(),Set.of(Pillar.Q,Pillar.F,Pillar.V),m.observationMaxAge(),m.financialPeriodMaxAge(),1095,3,m.extremeYield(),m.highLeverage(),m.profiles())).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void unknownAndNonBankFinancialBusinessesDoNotInheritBankOrIndustrialRules() {
        var i=input("BANK"); var unknown=new ScoringInput(i.instrumentUid(),i.assetUid(),i.name(),SectorClassification.unknown(ASSET,"financial"),false,i.observations(),i.dividends(),null,true,NOW,List.of());
        assertThat(evaluate(unknown).status()).isEqualTo(Status.UNSUPPORTED_PROFILE);
        var c=new SectorClassification(ASSET,"test",SectorClassification.Sector.FINANCIALS,SectorClassification.BusinessType.NON_BANK_FINANCIAL,SectorClassification.Source.MANUAL_VERIFIED,NOW,false,"synthetic",null);
        var nonbank=new ScoringInput(UID,ASSET,i.name(),c,true,i.observations(),i.dividends(),null,true,NOW,List.of());
        assertThat(evaluate(nonbank).overallScore()).isNull(); assertThat(evaluate(nonbank).status()).isEqualTo(Status.UNSUPPORTED_PROFILE);
    }
    @Test void missingMandatoryMetricNeverBecomesZeroFiftyOrReweightedScore() {
        var r=evaluate(replace(input("OIL_AND_GAS"),CURRENT_RATIO,null));
        assertThat(r.status()).isEqualTo(Status.PARTIAL); assertThat(r.overallScore()).isNull(); assertThat(r.pillarScores().get(Pillar.F)).isNull();
        assertThat(r.coverage().get(Pillar.F)).isEqualByComparingTo("0.7");
        assertThat(r.contributions()).anySatisfy(c -> { assertThat(c.metric()).isEqualTo(CURRENT_RATIO); assertThat(c.metricScore()).isNull(); assertThat(c.contribution()).isNull(); });
        assertThat(r.eligibilityReasons()).contains("MISSING_CURRENT_RATIO");
    }
    @Test void emptyFinancialEvidenceRemainsVisibleAsInsufficient() {
        var r=evaluate(withRows(input("OIL_AND_GAS"),List.of())); assertThat(r.status()).isEqualTo(Status.INSUFFICIENT_DATA); assertThat(r.overallScore()).isNull();
    }
    @Test void staleObservationsOrClassificationCannotRetainComparableFinalScore() {
        var i=input("OIL_AND_GAS"); var old=withRows(i,i.observations().stream().map(o -> dated(o,o.reportingEndDate(),NOW.minus(Duration.ofDays(2)))).toList());
        assertThat(evaluate(old).status()).isEqualTo(Status.STALE_DATA); assertThat(evaluate(old).overallScore()).isNull();
        var c=new ScoringInput(UID,ASSET,i.name(),i.classification(),false,i.observations(),i.dividends(),null,true,NOW,List.of());
        assertThat(evaluate(c).status()).isEqualTo(Status.STALE_DATA);
    }
    @Test void mismatchedPeriodsAndUnknownMetricDatesBlockTheirPillar() {
        var i=input("OIL_AND_GAS");
        for (LocalDate end:Arrays.asList(null,LocalDate.of(2024,12,31))) {
            var r=evaluate(replace(i,NET_MARGIN,dated(observation(NET_MARGIN,"15"),end,NOW)));
            assertThat(r.overallScore()).isNull(); assertThat(r.pillarScores().get(Pillar.Q)).isNull();
        }
    }
    @Test void negativeEarningsAndHighLeverageHaveEvidenceAndCannotRewardCheapMultiples() {
        var i=input("OIL_AND_GAS"); var loss=evaluate(replace(i,NET_INCOME,observation(NET_INCOME,"-10")));
        assertThat(loss.pillarScores().get(Pillar.V)).isEqualByComparingTo("0");
        assertThat(loss.risks()).anySatisfy(f -> { assertThat(f.ruleId()).isEqualTo("NEGATIVE_NET_INCOME"); assertThat(f.actualValue()).isEqualByComparingTo("-10"); assertThat(f.observationIds()).containsExactly("synthetic:NET_INCOME"); assertThat(f.observedAt()).isEqualTo(NOW); });
        var debt=evaluate(replace(i,NET_DEBT_EBITDA,observation(NET_DEBT_EBITDA,"7")));
        assertThat(debt.pillarScores().get(Pillar.V)).isEqualByComparingTo("0"); assertThat(debt.risks()).anyMatch(f -> f.ruleId().equals("HIGH_INDUSTRIAL_LEVERAGE"));
    }
    @ParameterizedTest @ValueSource(strings={"0","-1"}) void undefinedPENeverBecomesAnAttractiveValuation(String pe) {
        var r=evaluate(replace(input("OIL_AND_GAS"),PE,observation(PE,pe))); assertThat(r.overallScore()).isNull(); assertThat(r.pillarScores().get(Pillar.V)).isNull();
    }
    @Test void extremeDividendYieldAndMissingHistoryBlockDInsteadOfRewardingMissingness() {
        var r=evaluate(replace(input("OIL_AND_GAS"),DIVIDEND_YIELD,observation(DIVIDEND_YIELD,"25")));
        assertThat(r.overallScore()).isNull(); assertThat(r.pillarScores().get(Pillar.D)).isNull();
        assertThat(r.risks()).anyMatch(f -> f.ruleId().equals("EXTREME_DIVIDEND_YIELD"));
        var i=input("OIL_AND_GAS"); var empty=new ScoringInput(UID,ASSET,i.name(),i.classification(),true,i.observations(),null,null,true,NOW,List.of());
        assertThat(evaluate(empty).eligibilityReasons()).contains("UNRELIABLE_DIVIDEND_HISTORY");
    }
    @Test void zeroDebtAndVeryHighROEAreNotAlwaysOptimalAndNoPeerMedianIsInvented() {
        var profile=model().profiles().get("BANK"); var roe=profile.rules().stream().filter(r -> r.metric()==ROE).findFirst().orElseThrow();
        assertThat(roe.score(new BigDecimal("100"))).isLessThan(roe.score(new BigDecimal("20")));
        var rule=model().profiles().get("OIL_AND_GAS").rules().stream().filter(r -> r.metric()==NET_DEBT_EBITDA).findFirst().orElseThrow();
        assertThat(rule.score(BigDecimal.ZERO)).isLessThan(rule.score(BigDecimal.ONE));
        assertThat(evaluate(input("OIL_AND_GAS")).status()).isEqualTo(Status.SCORED); // absolute curves require no peer sample or fallback median
    }
    @Test void allCurveValuesAreBoundedAndInterpolationUsesBigDecimal() {
        for (var profile:model().profiles().values()) for (var rule:profile.rules()) for (int n=-100;n<=150;n++)
            assertThat(rule.score(BigDecimal.valueOf(n).divide(BigDecimal.valueOf(7),12,RoundingMode.HALF_UP))).isBetween(BigDecimal.ZERO,BigDecimal.valueOf(100));
        var rule=model().profiles().get("OIL_AND_GAS").rules().stream().filter(r -> r.metric()==PE).findFirst().orElseThrow();
        assertThat(rule.score(new BigDecimal("4"))).isEqualByComparingTo("37.5");
    }
    @Test void duplicatedActualVariantsAreAmbiguousAndDoNotSilentlyPickTheHighestScore() {
        var i=input("OIL_AND_GAS"); var rows=new ArrayList<>(i.observations()); rows.add(observation(PE,"10"));
        var r=evaluate(withRows(i,rows)); assertThat(r.overallScore()).isNull(); assertThat(r.eligibilityReasons()).contains("AMBIGUOUS_PE");
    }
    @Test void bankStabilityAtConfiguredFloorCannotReceiveAnAttractiveValuationPillar() {
        var r=evaluate(replace(input("BANK"),CAPITAL_ADEQUACY,observation(CAPITAL_ADEQUACY,"0")));
        assertThat(r.pillarScores().get(Pillar.V)).isEqualByComparingTo("0");
        assertThat(r.risks()).anyMatch(f -> f.ruleId().equals("STABILITY_RULE_FLOOR") && f.metric()==CAPITAL_ADEQUACY);
    }
    @Test void explicitZeroWeightPolicyIsVersionedAndFlaggedRatherThanRenormalizingMissingDividends() {
        var m=model();var w=new EnumMap<>(m.weights());w.put(Pillar.Q,new BigDecimal("0.4"));w.put(Pillar.F,new BigDecimal("0.3"));w.put(Pillar.V,new BigDecimal("0.3"));w.put(Pillar.D,BigDecimal.ZERO);
        var changed=new ScoringModel(m.modelVersion(),"explicit-three-pillars",w,Set.of(Pillar.Q,Pillar.F,Pillar.V),m.observationMaxAge(),m.financialPeriodMaxAge(),1095,3,m.extremeYield(),m.highLeverage(),m.profiles());
        var i=input("OIL_AND_GAS");i=replace(replace(i,DIVIDEND_YIELD,null),DIVIDEND_HISTORY,null);
        var r=new FundamentalScoringEngine().evaluate(i,changed,"a".repeat(64),"b".repeat(64),NOW);
        assertThat(r.status()).isEqualTo(Status.SCORED);assertThat(r.overallScore()).isEqualByComparingTo("80.85");assertThat(r.risks()).anyMatch(f -> f.ruleId().equals("CUSTOM_PILLAR_POLICY"));
    }
    @Test void unknownRateUnitsAndUnverifiedBankDefinitionsCannotBePromotedByNumericThresholds() {
        var raw=observation(ROE,"0.2");
        var unverified=new FinancialObservation(raw.id(),raw.assetUid(),raw.snapshotId(),raw.metricCode(),raw.value(),AnalysisMetric.Unit.UNVERIFIED_RATE,null,raw.observationType(),
                raw.reportingPeriod(),raw.periodStart(),raw.reportingEndDate(),raw.providerAsOfDate(),raw.fetchedAt(),raw.source(),raw.origin(),raw.qualityStatus(),null,raw.methodology(),raw.inputIds(),null);
        assertThat(evaluate(replace(input("BANK"),ROE,unverified)).pillarScores().get(Pillar.Q)).isNull();
        raw=observation(CAPITAL_ADEQUACY,"16");
        var wrong=new FinancialObservation(raw.id(),raw.assetUid(),raw.snapshotId(),raw.metricCode(),raw.value(),raw.unit(),null,raw.observationType(),raw.reportingPeriod(),raw.periodStart(),raw.reportingEndDate(),
                raw.providerAsOfDate(),raw.fetchedAt(),raw.source(),raw.origin(),raw.qualityStatus(),null,"unverified:ambiguous-capital-ratio",raw.inputIds(),null);
        assertThat(evaluate(replace(input("BANK"),CAPITAL_ADEQUACY,wrong)).eligibilityReasons()).contains("UNVERIFIED_DEFINITION_CAPITAL_ADEQUACY");
    }
    @Test void futureObservationDatesAndDifferentValuationReferenceDatesAreNotCombined() {
        var future=evaluate(replace(input("OIL_AND_GAS"),ROIC,dated(observation(ROIC,"15"),END,NOW.plusSeconds(1))));
        assertThat(future.overallScore()).isNull();assertThat(future.eligibilityReasons()).contains("FUTURE_SOURCE_DATE_ROIC");
        var o=observation(PE,"6");
        var olderReference=new FinancialObservation(o.id(),o.assetUid(),o.snapshotId(),o.metricCode(),o.value(),o.unit(),o.currency(),o.observationType(),o.reportingPeriod(),o.periodStart(),o.reportingEndDate(),
                NOW.minus(Duration.ofDays(1)),o.fetchedAt(),o.source(),o.origin(),o.qualityStatus(),o.qualityReason(),o.methodology(),o.inputIds(),o.instrumentUid());
        assertThat(evaluate(replace(input("OIL_AND_GAS"),PE,olderReference)).eligibilityReasons()).contains("INCOMPATIBLE_VALUATION_AS_OF");
    }
}
