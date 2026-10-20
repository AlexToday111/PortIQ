package ru.ba6kir.tradelab.dividends;

import java.math.BigDecimal;
import java.util.*;
import org.junit.jupiter.api.Test;
import ru.tinkoff.piapi.contract.v1.MoneyValue;
import ru.ba6kir.tradelab.dividends.domain.model.DividendEvent;
import ru.ba6kir.tradelab.dividends.domain.model.DividendEvent.Observation;
import ru.ba6kir.tradelab.dividends.domain.service.DividendAnalytics;
import static org.assertj.core.api.Assertions.*;
import static ru.ba6kir.tradelab.dividends.DividendTestData.*;

class DividendAnalyticsTest {
    final DividendAnalytics analytics=new DividendAnalytics();
    DividendAnalytics.ReferencePrice price(String amount,String currency) { return new DividendAnalytics.ReferencePrice(new BigDecimal(amount),currency,TODAY); }
    @Test void usesExactDecimalPerShareAndProviderDefinedLotAmount() {
        assertThat(event().amountPerShare()).isEqualByComparingTo("10.5");
        assertThat(analytics.perLot(event(),10).value()).isEqualByComparingTo("105");
        assertThat(analytics.perLot(event(),null).value()).isNull();
    }
    @Test void eventYieldAndTrailingYieldHaveExplicitSameCurrencyReferenceDate() {
        var observation=observation(event());
        assertThat(analytics.eventYield(observation,price("200","RUB"),TODAY).value()).isEqualByComparingTo("5.25");
        var sum=analytics.trailingSum(List.of(observation),TODAY,true);
        assertThat(sum.value()).isEqualByComparingTo("10.5");
        assertThat(analytics.trailingYield(sum,price("200","RUB"),TODAY).value()).isEqualByComparingTo("5.25");
    }
    @Test void missingZeroNegativeAndWrongCurrencyPricesAreUnavailable() {
        var o=observation(event());
        assertThat(analytics.eventYield(o,null,TODAY).reason()).isEqualTo("MISSING_PRICE");
        for (String n:List.of("0","-1")) assertThat(analytics.eventYield(o,price(n,"RUB"),TODAY).reason()).isEqualTo("INVALID_PRICE");
        assertThat(analytics.eventYield(o,price("200","USD"),TODAY).reason()).isEqualTo("CURRENCY_MISMATCH");
    }
    @Test void undatedFutureAndOldPricesAreUnavailable() {
        for (var date:Arrays.asList(null,TODAY.plusDays(1),TODAY.minusDays(8)))
            assertThat(analytics.eventYield(observation(event()),new DividendAnalytics.ReferencePrice(BigDecimal.TEN,"RUB",date),TODAY).value()).isNull();
    }
    @Test void previous365DaysUseExclusiveLowerAndInclusiveUpperRecordDates() {
        var before=event(raw().setRecordDate(day(TODAY.minusDays(365))).clearLastBuyDate().clearDeclaredDate().clearPaymentDate());
        var included=event(raw().setRecordDate(day(TODAY.minusDays(364))).clearLastBuyDate().clearDeclaredDate().clearPaymentDate());
        var today=event(raw().setRecordDate(day(TODAY)).clearPaymentDate());
        var future=event(raw().setRecordDate(day(TODAY.plusDays(1))).clearPaymentDate());
        assertThat(analytics.trailingSum(List.of(observation(before),observation(included),observation(today),observation(future)),TODAY,true).value())
                .isEqualByComparingTo("21");
    }
    @Test void cancellationsAreExcludedWithoutInventingPaymentConfirmation() {
        var cancelled=event(raw().setDividendType("Cancelled"));
        assertThat(cancelled.status(TODAY)).isEqualTo(DividendEvent.Status.CANCELLED);
        assertThat(event().status(TODAY)).isEqualTo(DividendEvent.Status.HISTORICAL_RECORDED);
        assertThat(analytics.trailingSum(List.of(observation(event()),observation(cancelled)),TODAY,true).value()).isEqualByComparingTo("10.5");
        assertThat(analytics.eventYield(observation(cancelled),price("200","RUB"),TODAY).value()).isNull();
    }
    @Test void identityConflictIncompleteCoverageAndUnknownRecordDateBlockSum() {
        var e=event();
        assertThat(analytics.trailingSum(List.of(new Observation(e,true,NOW,NOW)),TODAY,true).value()).isNull();
        assertThat(analytics.trailingSum(List.of(observation(e)),TODAY,false).value()).isNull();
        assertThat(analytics.trailingSum(List.of(observation(event(raw().clearRecordDate()))),TODAY,true).reason()).isEqualTo("MISSING_RECORD_DATE");
        assertThat(analytics.trailingSum(List.of(observation(event(raw().clearCreatedAt()))),TODAY,true).value()).isNull();
    }
    @Test void missingPaymentDateDoesNotInvalidateRecordedAmount() {
        var e=event(raw().clearPaymentDate());
        assertThat(e.paymentDate()).isNull(); assertThat(e.issues()).contains("MISSING_PAYMENT_DATE");
        assertThat(analytics.trailingSum(List.of(observation(e)),TODAY,true).value()).isEqualByComparingTo("10.5");
    }
    @Test void mixedCurrenciesZeroNegativeMissingAndSuspiciousAmountsCannotBeSummed() {
        var usd=event(raw().setDividendNet(MoneyValue.newBuilder().setCurrency("usd").setUnits(5)));
        assertThat(analytics.trailingSum(List.of(observation(event()),observation(usd)),TODAY,true).reason()).isEqualTo("CURRENCY_MISMATCH");
        for (long units:new long[]{0,-1}) {
            var e=event(raw().setDividendNet(MoneyValue.newBuilder().setCurrency("rub").setUnits(units)));
            assertThat(e.usable()).isFalse(); assertThat(analytics.perLot(e,10).value()).isNull();
        }
        assertThat(event(raw().clearDividendNet()).usable()).isFalse();
        assertThat(event(raw().setLastBuyDate(day(TODAY))).usable()).isFalse();
    }
    @Test void emptyPeriodMeansNoObservedEventsRatherThanGuaranteedZeroIncome() {
        assertThat(analytics.trailingSum(List.of(),TODAY,true).reason()).isEqualTo("NO_OBSERVED_EVENTS");
        var stats=analytics.statistics(List.of(observation(event())),TODAY.minusYears(2),TODAY);
        assertThat(stats).hasSize(3); assertThat(stats.getFirst().recordedEvents()).isZero();
        assertThat(stats.getLast().observedAmounts()).containsKey("RUB");
    }
    @Test void exactDuplicateObservationsAreNotCountedTwiceAndNonCashTypesAreNotDividendYield() {
        var o=observation(event());
        assertThat(analytics.trailingSum(List.of(o,o),TODAY,true).value()).isEqualByComparingTo("10.5");
        for (String type:List.of("Return of Capital","Daily Accrual","Other")) {
            var nonCash=observation(event(raw().setDividendType(type)));
            assertThat(analytics.trailingSum(List.of(nonCash),TODAY,true).value()).isNull();
            assertThat(analytics.eventYield(nonCash,price("200","RUB"),TODAY).value()).isNull();
        }
    }
    @Test void comparesOnlyExplicitComparableHistoricalAnnualEvents() {
        var previous=event(raw().setRecordDate(day(TODAY.minusYears(1))).clearDeclaredDate().clearLastBuyDate().clearPaymentDate()
                .setDividendNet(MoneyValue.newBuilder().setCurrency("rub").setUnits(5)));
        assertThat(analytics.comparableChange(observation(previous),observation(event()),TODAY).value()).isEqualByComparingTo("110");
        assertThat(analytics.comparableChange(observation(previous),observation(event(raw().setRegularity("Semi-Anl"))),TODAY).value()).isNull();
        assertThat(analytics.latestComparableChange(List.of(observation(previous),observation(event())),TODAY).value()).isEqualByComparingTo("110");
        assertThat(analytics.latestComparableChange(List.of(observation(previous),observation(event()),observation(event())),TODAY).reason()).isEqualTo("MULTIPLE_EVENTS_IN_YEAR");
    }
}
