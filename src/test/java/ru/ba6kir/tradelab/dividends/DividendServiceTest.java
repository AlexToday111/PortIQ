package ru.ba6kir.tradelab.dividends;

import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import ru.ba6kir.tradelab.market.application.*;
import ru.ba6kir.tradelab.market.application.price.MarketPriceService;
import ru.ba6kir.tradelab.dividends.application.port.*;
import ru.ba6kir.tradelab.dividends.application.service.DividendService;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static ru.ba6kir.tradelab.dividends.DividendTestData.*;

class DividendServiceTest {
    final InstrumentService instruments=mock(InstrumentService.class);
    final MarketPriceService prices=mock(MarketPriceService.class);
    final DividendProvider provider=mock(DividendProvider.class);
    final DividendStore store=mock(DividendStore.class);
    DividendService service(boolean enabled,List<String> tracked) { return new DividendService(instruments,prices,provider,store,CLOCK,
            Duration.ofDays(1),Duration.ofSeconds(60),3,365,tracked,enabled); }
    final DividendService service=service(true,List.of("SBER"));
    @BeforeEach void setup() {
        when(instruments.resolve("SBER")).thenReturn(stock(UID));
        when(store.state(UID)).thenReturn(new DividendStore.State(null,null,null,null,null));
        when(store.find(UID)).thenReturn(List.of(observation(event())));
        when(store.tryStart(eq(UID),eq(NOW),any())).thenReturn(true);
        when(store.save(eq(UID),anyList(),any(),any(),eq(NOW))).thenReturn(new DividendStore.Counts(1,0,0,0,0));
    }
    @Test void refreshUsesPaddedRecordDateBoundsAndKeepsUnknownDateDiagnostics() {
        var unknown=event(raw().clearRecordDate()); var outside=event(raw().setRecordDate(day(TODAY.minusYears(4))));
        when(provider.fetchDividends(eq(UID),any(),any())).thenReturn(List.of(event(),unknown,outside));
        assertThat(service.refresh("SBER").outcome()).isEqualTo("SUCCESS");
        verify(provider).fetchDividends(UID,TODAY.minusYears(3).minusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant(),
                TODAY.plusDays(367).atStartOfDay(ZoneOffset.UTC).toInstant());
        verify(store).save(eq(UID),argThat(e -> e.size()==2 && e.contains(unknown) && !e.contains(outside)),eq(TODAY.minusYears(3)),eq(TODAY.plusDays(365)),eq(NOW));
    }
    @Test void validCoverageUsesCacheAndFiveYearHistoryExtendsFetchWindow() {
        when(store.state(UID)).thenReturn(new DividendStore.State(TODAY.minusYears(3),TODAY.plusDays(365),NOW,null,null));
        assertThat(service.get("SBER",1).trailingSum().value()).isEqualByComparingTo("10.5");
        verifyNoInteractions(provider);
        when(provider.fetchDividends(eq(UID),any(),any())).thenReturn(List.of());
        service.get("SBER",5);
        verify(store).save(eq(UID),anyList(),eq(TODAY.minusYears(5)),eq(TODAY.plusDays(365)),eq(NOW));
    }
    @Test void cooldownPreventsProviderCallAndReturnsExplicitCachedWarning() {
        when(store.tryStart(eq(UID),any(),any())).thenReturn(false);
        var r=service.refresh("SBER"); assertThat(r.outcome()).isEqualTo("COOLDOWN");
        assertThat(r.view().warning()).isEqualTo("COOLDOWN"); assertThat(r.view().events()).hasSize(1); verifyNoInteractions(provider);
    }
    @Test void apiFailurePreservesCacheAndRecordsSanitizedReason() {
        when(provider.fetchDividends(eq(UID),any(),any())).thenThrow(new MarketException(MarketException.Reason.TIMEOUT));
        var r=service.refresh("SBER"); assertThat(r.outcome()).isEqualTo("FAILED"); assertThat(r.view().events()).hasSize(1);
        assertThat(r.view().trailingYield().value()).isNull(); verify(store).fail(UID,NOW,"TIMEOUT");
        verify(store,never()).save(any(),any(),any(),any(),any());
    }
    @Test void emptyProviderResponseIsSavedWithoutDeletingHistory() {
        when(provider.fetchDividends(eq(UID),any(),any())).thenReturn(List.of());
        assertThat(service.refresh("SBER").view().events()).hasSize(1);
        verify(store).save(eq(UID),eq(List.of()),any(),any(),eq(NOW));
    }
    @Test void instrumentMismatchRejectsEntireResponseBeforePersistence() {
        var wrong=ru.ba6kir.tradelab.dividends.infrastructure.tinvest.TInvestDividendAdapter.normalize(UID2,raw().build(),NOW);
        when(provider.fetchDividends(eq(UID),any(),any())).thenReturn(List.of(wrong));
        assertThat(service.refresh("SBER").outcome()).isEqualTo("FAILED"); verify(store,never()).save(any(),any(),any(),any(),any());
    }
    @Test void calendarSortsAllKindsIncludesCancellationAndLimitsRows() {
        when(store.state(UID)).thenReturn(new DividendStore.State(TODAY.minusYears(3),TODAY.plusDays(365),NOW,null,null));
        var rows=new ArrayList<ru.ba6kir.tradelab.dividends.domain.model.DividendEvent.Observation>();
        for (int i=1;i<=12;i++) rows.add(observation(event(raw().setRecordDate(day(TODAY.plusDays(i))).setLastBuyDate(day(TODAY.plusDays(i-1)))
                .setPaymentDate(day(TODAY.plusDays(i+3))).setDividendType(i==1?"Cancelled":"Regular Cash"))));
        when(store.find(UID)).thenReturn(rows);
        var calendar=service.calendar(); assertThat(calendar.rows()).hasSize(20); assertThat(calendar.omitted()).isPositive();
        assertThat(calendar.rows()).extracting(DividendService.CalendarRow::date).isSorted();
        assertThat(calendar.rows()).anyMatch(r -> r.kind().contains("отмен")); verifyNoInteractions(provider);
    }
    @Test void emptyUniverseAndAmbiguousTickersAreReportedWithoutSelectingAShareClass() {
        assertThat(service(true,List.of()).calendar().warnings()).contains("INVEST_TRACKED_TICKERS: список пуст");
        when(instruments.resolve("SBER")).thenThrow(new AmbiguousInstrumentException(List.of(stock(UID),stock(UID2))));
        assertThat(service.calendar().warnings()).contains("SBER: AMBIGUOUS_INSTRUMENT"); verifyNoInteractions(provider);
    }
    @Test void disabledFeatureDoesNotTouchProviderOrStorageAndUnsupportedWindowRejected() {
        assertThatThrownBy(() -> service(false,List.of()).get("SBER",1)).isInstanceOf(MarketException.class);
        assertThatThrownBy(() -> service.get("SBER",2)).isInstanceOf(MarketException.class); verifyNoInteractions(provider,store);
    }
    @Test void unavailableQuoteDoesNotTurnSuccessfulDividendSyncIntoFailure() {
        when(provider.fetchDividends(eq(UID),any(),any())).thenReturn(List.of(event()));
        when(prices.cachedQuote(UID)).thenThrow(new IllegalStateException("private-db"));
        assertThat(service.refresh("SBER").outcome()).isEqualTo("SUCCESS");
    }
    @Test void oldIndividualObservationCannotBecomeFreshMerelyBecauseEmptyRangeWasRequeried() {
        when(store.state(UID)).thenReturn(new DividendStore.State(TODAY.minusYears(3),TODAY.plusDays(365),NOW,null,null));
        when(store.find(UID)).thenReturn(List.of(new ru.ba6kir.tradelab.dividends.domain.model.DividendEvent.Observation(
                event(),false,NOW.minusSeconds(172800),NOW.minusSeconds(172800))));
        assertThat(service.get("SBER",1).trailingSum().reason()).isEqualTo("STALE_OR_INCOMPLETE_COVERAGE");
        assertThat(service.get("SBER",1).stale()).isTrue();
        verifyNoInteractions(provider);
    }
}
