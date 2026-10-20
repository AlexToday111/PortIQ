package ru.ba6kir.tradelab.fundamentals;

import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import ru.ba6kir.tradelab.fundamentals.domain.model.*;
import ru.ba6kir.tradelab.fundamentals.domain.validation.FundamentalNormalizer;
import static org.assertj.core.api.Assertions.*;
import static ru.ba6kir.tradelab.fundamentals.FundamentalsTestData.*;

class FundamentalNormalizerTest {
    private final FundamentalNormalizer normalizer=new FundamentalNormalizer();
    private MetricValue value(FundamentalSnapshot snapshot,MetricCode code) { return snapshot.metrics().stream().filter(m -> m.code()==code).findFirst().orElseThrow(); }
    @Test void distinguishesAbsentUnsupportedAndUncertainZero() {
        var s=snapshot();
        assertThat(value(s,MetricCode.ROA).quality()).isEqualTo(QualityStatus.MISSING);
        assertThat(value(s,MetricCode.EBITDA_MARGIN).quality()).isEqualTo(QualityStatus.UNSUPPORTED);
        assertThat(value(s,MetricCode.TOTAL_DEBT).value()).isEqualByComparingTo("0");
        assertThat(value(s,MetricCode.TOTAL_DEBT).quality()).isEqualTo(QualityStatus.SUSPECT);
        assertThat(value(s,MetricCode.TOTAL_DEBT).reason()).contains("ZERO_WITHOUT_PRESENCE");
    }
    @Test void explicitLegitimateZeroIsValidAndLossesAndNegativeCashFlowAreRetained() {
        var original=data("5.25",NOW);
        var values=new EnumMap<MetricCode,FundamentalData.RawNumber>(original.values());
        values.put(MetricCode.TOTAL_DEBT,new FundamentalData.RawNumber("0.0",true));
        var s=normalizer.normalize(new FundamentalData(ASSET,"USD",null,null,null,NOW,SOURCE,values));
        assertThat(value(s,MetricCode.TOTAL_DEBT).quality()).isEqualTo(QualityStatus.VALID);
        assertThat(value(s,MetricCode.NET_INCOME).value()).isEqualByComparingTo("-100.5");
        assertThat(value(s,MetricCode.NET_INCOME).quality()).isEqualTo(QualityStatus.VALID);
        assertThat(value(s,MetricCode.FREE_CASH_FLOW).quality()).isEqualTo(QualityStatus.VALID);
    }
    @Test void rejectsNonFiniteNumbersWithoutLosingDiagnostics() {
        for (String raw:List.of("NaN","Infinity","-Infinity","invalid")) {
            var s=normalizer.normalize(new FundamentalData(ASSET,"rub",null,null,null,NOW,SOURCE,
                    Map.of(MetricCode.PE,new FundamentalData.RawNumber(raw,false))));
            var metric=value(s,MetricCode.PE);
            assertThat(metric.value()).isNull(); assertThat(metric.quality()).isEqualTo(QualityStatus.SUSPECT);
            assertThat(metric.rawValue()).isEqualTo(raw);
            assertThat(s.rawPayload().get("pe_ratio_ttm")).contains(raw);
        }
    }
    @Test void uncertainRateScaleIsNeverConvertedOrMislabelledAsPercent() {
        var metric=value(snapshot(),MetricCode.ROE);
        assertThat(metric.value()).isEqualByComparingTo("0.25");
        assertThat(metric.unit()).isEqualTo(MetricCode.Unit.UNVERIFIED_RATE);
        assertThat(metric.reason()).contains("RATE_SCALE_UNDOCUMENTED");
        assertThat(metric.quality()).isEqualTo(QualityStatus.SUSPECT);
    }
    @Test void preservesProviderCurrencyAndHorizonWithoutInventingPublicationDate() {
        var s=snapshot(); var metric=value(s,MetricCode.REVENUE);
        assertThat(metric.currency()).isEqualTo("USD"); assertThat(metric.reportingPeriod()).isEqualTo("TTM");
        assertThat(s.providerAsOf()).isNull(); assertThat(s.periodEnd()).isEqualTo(Instant.parse("2025-12-31T00:00:00Z"));
        assertThat(metric.value()).isEqualByComparingTo("1000.25");
    }
    @Test void negativeMultiplesAndMissingCurrencyRequireContext() {
        var s=normalizer.normalize(new FundamentalData(ASSET,null,null,null,null,NOW,SOURCE,
                Map.of(MetricCode.PE,new FundamentalData.RawNumber("-3.2",false),MetricCode.REVENUE,new FundamentalData.RawNumber("10",false))));
        assertThat(value(s,MetricCode.PE).reason()).contains("NEGATIVE_MULTIPLE_CONTEXT_REQUIRED");
        assertThat(value(s,MetricCode.REVENUE).currency()).isNull();
        assertThat(value(s,MetricCode.REVENUE).reason()).contains("CURRENCY_UNKNOWN");
    }
    @Test void fingerprintIgnoresRetrievalTimeButTracksCorrectionsAndFiscalDates() {
        assertThat(normalizer.normalize(data("5.25",NOW.plusSeconds(60))).contentHash()).isEqualTo(snapshot().contentHash());
        assertThat(normalizer.normalize(data("6.25",NOW)).contentHash()).isNotEqualTo(snapshot().contentHash());
        assertThatThrownBy(() -> normalizer.normalize(new FundamentalData(ASSET,"USD",NOW,NOW.minusSeconds(1),null,NOW,SOURCE,Map.of())))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
