package ru.ba6kir.tradelab.analysis;

import java.util.*;
import org.junit.jupiter.api.Test;
import ru.ba6kir.tradelab.analysis.domain.metric.*;
import ru.ba6kir.tradelab.analysis.application.service.FinancialMetricNormalizer;
import static ru.ba6kir.tradelab.analysis.domain.metric.AnalysisMetric.*;
import static ru.ba6kir.tradelab.analysis.domain.metric.FinancialObservation.*;
import static org.assertj.core.api.Assertions.*;
import static ru.ba6kir.tradelab.analysis.AnalysisTestData.*;

class DerivedMetricCalculatorTest {
    List<FinancialObservation> calculate(FinancialObservation... rows) { return new DerivedMetricCalculator().calculate(ASSET,1L,List.of(rows),NOW); }
    FinancialObservation find(AnalysisMetric code,FinancialObservation... rows) { return calculate(rows).stream().filter(o -> o.metricCode()==code).findFirst().orElseThrow(); }
    @Test void earningsYieldUsesPositivePEWithExplicitDerivedLineageAndPreservesProvider() {
        var pe=obs(PE,"5"); var result=find(EARNINGS_YIELD,pe);
        assertThat(result.value()).isEqualByComparingTo("20"); assertThat(result.origin()).isEqualTo(Origin.DERIVED);
        assertThat(result.inputIds()).containsExactly(pe.id()); assertThat(pe.origin()).isEqualTo(Origin.PROVIDER);
        assertThat(result.methodology()).contains("earnings-yield-v1");
    }
    @Test void negativeProfitProducesNegativeMarginAndROEWithVerifiedAverageEquity() {
        var loss=obs(NET_INCOME,"-10"); var revenue=obs(REVENUE,"100");
        assertThat(find(NET_MARGIN,loss,revenue).value()).isEqualByComparingTo("-10");
        assertThat(find(ROE,loss,obs(AVERAGE_EQUITY,"50")).value()).isEqualByComparingTo("-20");
        assertThat(find(ROE,loss).value()).isNull();
        assertThat(find(ROIC,obs(NOPAT,"-5"),obs(AVERAGE_INVESTED_CAPITAL,"100")).value()).isEqualByComparingTo("-5");
    }
    @Test void netDebtCanBeNegativeAndMustUsePointInTimeInputs() {
        var debt=obs(ASSET,TOTAL_DEBT,"20","RUB",ObservationType.POINT_IN_TIME);
        var cash=obs(ASSET,CASH,"30","RUB",ObservationType.POINT_IN_TIME);
        assertThat(find(NET_DEBT,debt,cash).value()).isEqualByComparingTo("-10");
        assertThat(find(NET_DEBT_EBITDA,debt,cash,obs(EBITDA,"5")).value()).isEqualByComparingTo("-2");
        assertThat(find(NET_DEBT,obs(TOTAL_DEBT,"20"),obs(CASH,"30")).value()).isNull();
    }
    @Test void zeroNegativeAndMissingDenominatorsAreUnavailableNotZero() {
        for (String n:List.of("0","-5")) {
            assertThat(find(EARNINGS_YIELD,obs(PE,n)).value()).isNull();
            assertThat(find(NET_MARGIN,obs(NET_INCOME,"-5"),obs(REVENUE,n)).value()).isNull();
        }
        assertThat(find(ROIC,obs(NET_INCOME,"5"),obs(AVERAGE_INVESTED_CAPITAL,"10")).qualityReason()).isEqualTo("MISSING_INPUTS");
    }
    @Test void incompatibleCurrenciesPeriodsAndIdentitiesDoNotCombine() {
        var income=obs(NET_INCOME,"5");
        assertThat(find(NET_MARGIN,income,obs(ASSET,REVENUE,"100","USD",ObservationType.TTM)).qualityReason()).isEqualTo("CURRENCY_MISMATCH");
        assertThat(find(NET_MARGIN,income,obs(ASSET,REVENUE,"100","RUB",ObservationType.ANNUAL)).qualityReason()).isEqualTo("INCOMPATIBLE_PERIODS");
        assertThat(find(NET_MARGIN,income,obs(asset(1),REVENUE,"100","RUB",ObservationType.TTM)).qualityReason()).isEqualTo("IDENTITY_OR_SNAPSHOT_MISMATCH");
    }
    @Test void normalizationPreservesUnknownScaleAndDoesNotAssignSnapshotDatesToEveryMetric() {
        var rows=new FinancialMetricNormalizer().normalize(input());
        var roe=rows.stream().filter(o -> o.metricCode()==ROE).findFirst().orElseThrow();
        assertThat(roe.value()).isEqualByComparingTo("0.25"); assertThat(roe.unit()).isEqualTo(Unit.UNVERIFIED_RATE); assertThat(roe.validValue()).isFalse();
        assertThat(rows).allMatch(o -> o.reportingEndDate()==null);
        assertThat(find(NET_MARGIN,rows.toArray(FinancialObservation[]::new)).qualityReason()).isEqualTo("INCOMPATIBLE_PERIODS");
        assertThat(find(EARNINGS_YIELD,rows.toArray(FinancialObservation[]::new)).value()).isEqualByComparingTo("20");
    }
    @Test void derivedIdentityIsIdempotentAndProviderAndLocalVariantsStaySeparate() {
        assertThat(calculate(obs(PE,"5"))).isEqualTo(calculate(obs(PE,"5")));
        var provider=obs(NET_MARGIN,"12"); var derived=find(NET_MARGIN,provider,obs(NET_INCOME,"-10"),obs(REVENUE,"100"));
        assertThat(provider.value()).isEqualByComparingTo("12"); assertThat(derived.value()).isEqualByComparingTo("-10"); assertThat(derived.id()).isNotEqualTo(provider.id());
    }
}
