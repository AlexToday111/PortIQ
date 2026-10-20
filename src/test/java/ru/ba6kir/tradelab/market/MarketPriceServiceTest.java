package ru.ba6kir.tradelab.market;

import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.mockito.ArgumentCaptor;
import ru.ba6kir.tradelab.market.application.*;
import ru.ba6kir.tradelab.market.application.price.*;
import ru.ba6kir.tradelab.market.domain.price.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static ru.ba6kir.tradelab.market.InstrumentServiceTest.*;
import static ru.ba6kir.tradelab.market.PriceDomainTest.*;

class MarketPriceServiceTest {
    private final InstrumentService instruments=mock(InstrumentService.class);
    private final MarketPriceProvider provider=mock(MarketPriceProvider.class);
    private final MarketPriceStore store=mock(MarketPriceStore.class);
    private final ru.ba6kir.tradelab.market.domain.StockInstrument instrument=stock(UID,"Сбербанк",NOW);
    private final MarketPriceService service=new MarketPriceService(instruments,provider,store,Clock.fixed(NOW,ZoneOffset.UTC),
            Duration.ofSeconds(60),365,List.of("SBER","MISSING"),true);
    @BeforeEach void setup() {
        when(instruments.findByTicker("SBER")).thenReturn(List.of(instrument));
        when(instruments.findByUid(UID,false)).thenReturn(new InstrumentService.Metadata(instrument,true));
        when(provider.getLastPrice(instrument)).thenReturn(Optional.of(quote(NOW.minusSeconds(60))));
        when(store.tryStart(eq(UID),any(),any())).thenReturn(true);
        when(store.findCandles(eq(UID),any(),any())).thenReturn(List.of(candle(NOW.minusSeconds(3600),"100",true)));
    }
    @Test void latestPriceUsesResolvedUidAndPreservesTradeTime() {
        var result=service.lastPrice("SBER");
        assertThat(result.cached()).isFalse();
        assertThat(result.quote().quoteTime()).isEqualTo(NOW.minusSeconds(60));
        verify(store).saveQuote(result.quote());
        verify(provider).getLastPrice(instrument);
    }
    @Test void noLatestQuoteIsNotZeroAndCanUseExplicitlyMarkedCache() {
        when(provider.getLastPrice(instrument)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.lastPrice(UID)).isInstanceOfSatisfying(PriceException.class,
                e -> assertThat(e.reason()).isEqualTo(PriceException.Reason.NO_PRICE));
        when(store.findQuote(UID)).thenReturn(Optional.of(quote(NOW.minusSeconds(3600))));
        var result=service.lastPrice(UID);
        assertThat(result.cached()).isTrue();
        assertThat(result.warning()).isEqualTo("NO_PRICE");
        assertThat(result.quote().quoteTime()).isEqualTo(NOW.minusSeconds(3600));
        verify(store,never()).saveQuote(any());
    }
    @Test void apiFailurePreservesCachedValuesAndOriginalTimestamp() {
        when(provider.getLastPrice(instrument)).thenThrow(new MarketException(MarketException.Reason.TIMEOUT));
        when(store.findQuote(UID)).thenReturn(Optional.of(quote(NOW.minusSeconds(3600))));
        assertThat(service.lastPrice(UID).warning()).isEqualTo("TIMEOUT");
        verify(store,never()).saveQuote(any());
    }
    @Test void initialLoadIsSplitAndSubsequentRefreshIsAdmittedOnlyOnce() {
        when(store.tryStart(eq(UID),any(),any())).thenReturn(true,false);
        var result=service.refresh("SBER");
        assertThat(result.outcome()).isEqualTo(MarketPriceService.Outcome.SUCCESS);
        var from=ArgumentCaptor.forClass(Instant.class); var to=ArgumentCaptor.forClass(Instant.class);
        verify(provider,times(3)).getDailyCandles(eq(instrument),from.capture(),to.capture());
        for (int i=0;i<from.getAllValues().size();i++) {
            assertThat(Duration.between(from.getAllValues().get(i),to.getAllValues().get(i))).isLessThanOrEqualTo(Duration.ofDays(180));
            if (i>0) assertThat(from.getAllValues().get(i)).isEqualTo(to.getAllValues().get(i-1));
        }
        assertThat(service.refresh(UID).outcome()).isEqualTo(MarketPriceService.Outcome.COOLDOWN);
        verify(provider,times(1)).getLastPrice(instrument);
        verify(store).finish(eq(UID),eq(NOW),eq(true),isNull());
    }
    @Test void incrementalRefreshRequestsOnlyTailAndOlderIncompleteCandle() {
        when(store.syncState(UID)).thenReturn(Optional.of(new MarketPriceStore.SyncState(NOW.minus(Duration.ofDays(365)),
                NOW.minus(Duration.ofDays(1)),NOW.minus(Duration.ofDays(1)),NOW.minus(Duration.ofDays(1)),null,null)));
        when(store.earliestIncomplete(eq(UID),any())).thenReturn(Optional.of(NOW.minus(Duration.ofDays(4))));
        service.refresh(UID);
        verify(provider).getDailyCandles(instrument,NOW.minus(Duration.ofDays(4)),NOW);
    }
    @Test void aLongerRequestedWindowBackfillsOlderGapWithoutDownloadingExistingMiddle() {
        Instant covered=NOW.minus(Duration.ofDays(30));
        when(store.syncState(UID)).thenReturn(Optional.of(new MarketPriceStore.SyncState(covered,NOW.minusSeconds(60),NOW,null,null,null)));
        service.refresh(UID);
        var from=ArgumentCaptor.forClass(Instant.class); var to=ArgumentCaptor.forClass(Instant.class);
        verify(provider,atLeastOnce()).getDailyCandles(eq(instrument),from.capture(),to.capture());
        assertThat(to.getAllValues()).contains(covered);
        assertThat(from.getAllValues().getLast()).isAfter(NOW.minus(Duration.ofDays(3)));
        assertThat(from.getAllValues().stream().noneMatch(t -> t.isAfter(covered) && t.isBefore(NOW.minus(Duration.ofDays(3))))).isTrue();
    }
    @Test void quoteAndHistoryFailureAreIndependentAndDoNotAdvanceFailedHistoryCoverage() {
        when(provider.getDailyCandles(eq(instrument),any(),any())).thenThrow(new MarketException(MarketException.Reason.RATE_LIMIT));
        var partial=service.refresh(UID);
        assertThat(partial.outcome()).isEqualTo(MarketPriceService.Outcome.PARTIAL);
        assertThat(partial.historyError()).isEqualTo("RATE_LIMIT");
        verify(store).saveQuote(any());
        verify(store,never()).saveCandles(any());
        verify(store,never()).coverHistory(any(),any(),any());
        verify(store).finish(eq(UID),eq(NOW),eq(false),contains("RATE_LIMIT"));
    }
    @Test void totalProviderOutageDoesNotWriteMarketData() {
        when(provider.getLastPrice(instrument)).thenThrow(new MarketException(MarketException.Reason.UNAVAILABLE));
        when(provider.getDailyCandles(eq(instrument),any(),any())).thenThrow(new MarketException(MarketException.Reason.UNAVAILABLE));
        assertThat(service.refresh(UID).outcome()).isEqualTo(MarketPriceService.Outcome.FAILED);
        verify(store,never()).saveQuote(any()); verify(store,never()).saveCandles(any());
    }
    @Test void successfulHistoryWithNoNewTradesIsPartialSuccessWhenQuoteIsMissing() {
        when(provider.getLastPrice(instrument)).thenReturn(Optional.empty());
        var result=service.refresh(UID);
        assertThat(result.outcome()).isEqualTo(MarketPriceService.Outcome.PARTIAL);
        assertThat(result.quoteError()).isEqualTo("NO_PRICE");
        assertThat(result.historyError()).isNull();
    }
    @Test void invalidWholeBatchIsRejectedBeforePersistence() {
        when(provider.getDailyCandles(eq(instrument),any(),any())).thenReturn(List.of(candle(NOW.plusSeconds(1),"1",false)));
        assertThat(service.refresh(UID).historyError()).isEqualTo("INVALID_DATA");
        verify(store,never()).saveCandles(any());
    }
    @Test void cachedHistoryCalculatesOnlyFinalizedDataAndReportsIncompleteCount() {
        when(store.tryStart(eq(UID),any(),any())).thenReturn(false);
        when(store.findCandles(eq(UID),any(),any())).thenReturn(List.of(candle(NOW.minus(Duration.ofDays(4)),"100",true),
                candle(NOW.minus(Duration.ofDays(1)),"90",true),candle(NOW,"999",false)));
        var result=service.history(UID,30);
        assertThat(result.summary().orElseThrow().percentChange()).isEqualByComparingTo("-10");
        assertThat(result.incompleteCount()).isEqualTo(1);
        assertThat(result.synchronization().outcome()).isEqualTo(MarketPriceService.Outcome.COOLDOWN);
        verifyNoInteractions(provider);
    }
    @Test void unknownAmbiguousInvalidAndDisabledRequestsNeverFetchPrices() {
        when(instruments.findByTicker("MISSING")).thenReturn(List.of());
        assertThatThrownBy(() -> service.lastPrice("MISSING")).isInstanceOf(MarketException.class);
        when(instruments.findByTicker("MULTI")).thenReturn(List.of(instrument,stock("22222222-2222-2222-2222-222222222222","other",NOW)));
        assertThatThrownBy(() -> service.refresh("MULTI")).isInstanceOf(AmbiguousInstrumentException.class);
        assertThatThrownBy(() -> service.history(UID,366)).isInstanceOf(MarketException.class);
        var disabled=new MarketPriceService(instruments,provider,store,Clock.fixed(NOW,ZoneOffset.UTC),Duration.ofMinutes(1),365,List.of(),false);
        assertThatThrownBy(() -> disabled.lastPrice(UID)).isInstanceOf(MarketException.class);
        verifyNoInteractions(provider);
    }
    @Test void configuredUniverseReportsMissingStocksAndDoesNotAssumeTradability() {
        when(instruments.findByTicker("MISSING")).thenReturn(List.of());
        var results=service.refreshTracked();
        assertThat(results).hasSize(2);
        assertThat(results.getFirst().synchronization().outcome()).isEqualTo(MarketPriceService.Outcome.SUCCESS);
        assertThat(results.getLast().error()).isEqualTo("NOT_FOUND");
    }
}
