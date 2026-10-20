package ru.ba6kir.tradelab.market;

import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import ru.ba6kir.tradelab.market.application.*;
import ru.ba6kir.tradelab.market.domain.StockInstrument;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class InstrumentServiceTest {
    public static final String UID = "11111111-1111-1111-1111-111111111111";
    public static final Instant NOW = Instant.parse("2026-10-20T10:00:00Z");
    private final StockInstrumentProvider provider = mock(StockInstrumentProvider.class);
    private final InstrumentStore store = mock(InstrumentStore.class);
    private final InstrumentService service = service(true);

    private InstrumentService service(boolean enabled) {
        return new InstrumentService(provider, store, Clock.fixed(NOW, ZoneOffset.UTC), Duration.ofHours(24), enabled);
    }

    public static StockInstrument stock(String uid, String name, Instant updated) {
        return new StockInstrument(uid, "SBER", "TQBR", name, null, null, "rub", "MOEX", 10,
                "SECURITY_TRADING_STATUS_NORMAL_TRADING", true, updated, "T_INVEST_PRODUCTION");
    }

    @Test void lookupSavesFreshMetadataAndUsesUidCacheNextTime() {
        var s = stock(UID, "Сбербанк", NOW);
        when(store.findByUid(UID)).thenReturn(Optional.empty(), Optional.of(s));
        when(provider.findByUid(UID)).thenReturn(Optional.of(s));
        assertThat(service.findByUid(UID, false)).isEqualTo(new InstrumentService.Metadata(s, false));
        assertThat(service.findByUid(UID, false)).isEqualTo(new InstrumentService.Metadata(s, true));
        verify(provider, times(1)).findByUid(UID);
        verify(store).upsert(s);
    }

    @Test void expiredAndForcedRefreshFetchAgain() {
        var old = stock(UID, "old", NOW.minus(Duration.ofHours(24)));
        var current = stock(UID, "current", NOW);
        when(store.findByUid(UID)).thenReturn(Optional.of(old));
        when(provider.findByUid(UID)).thenReturn(Optional.of(current));
        assertThat(service.findByUid(UID, false).cached()).isFalse();
        assertThat(service.refresh(UID).instrument().name()).isEqualTo("current");
        verify(provider, times(2)).findByUid(UID);
        verify(store, times(2)).upsert(current);
    }

    @Test void tickerResolutionIsExactAndNeverAssumesCachedUniqueness() {
        var first = stock(UID, "first", NOW);
        var second = stock("22222222-2222-2222-2222-222222222222", "second", NOW);
        when(provider.findShares("SBER")).thenReturn(List.of(first, second));
        assertThat(service.findByTicker("sber")).containsExactly(first, second);
        verifyNoInteractions(store);
    }

    @Test void unknownTickerAndUidAreHandled() {
        when(provider.findShares("UNKNOWN")).thenReturn(List.of());
        when(provider.findByUid(UID)).thenReturn(Optional.empty());
        assertThat(service.findByTicker("UNKNOWN")).isEmpty();
        assertThatThrownBy(() -> service.refresh(UID)).isInstanceOfSatisfying(MarketException.class,
                e -> assertThat(e.reason()).isEqualTo(MarketException.Reason.NOT_FOUND));
        verify(store, never()).upsert(any());
    }

    @Test void errorsDoNotReturnStaleOrFabricatedMetadata() {
        when(store.findByUid(UID)).thenReturn(Optional.of(stock(UID, "old", NOW.minus(Duration.ofDays(2)))));
        when(provider.findByUid(UID)).thenThrow(new MarketException(MarketException.Reason.TIMEOUT));
        assertThatThrownBy(() -> service.findByUid(UID, false)).isInstanceOfSatisfying(MarketException.class,
                e -> assertThat(e.reason()).isEqualTo(MarketException.Reason.TIMEOUT));
        verify(store, never()).upsert(any());
    }

    @Test void validatesInputBeforeProviderOrDatabaseAccess() {
        for (var query : List.of("", " ", "x".repeat(65), "/bad", "x\nsecret")) {
            assertThatThrownBy(() -> service.search(query)).isInstanceOf(MarketException.class);
        }
        assertThatThrownBy(() -> service.findByTicker("SBER*" )).isInstanceOf(MarketException.class);
        assertThatThrownBy(() -> service.refresh("bad-uid")).isInstanceOf(MarketException.class);
        verifyNoInteractions(provider, store);
    }

    @Test void disabledIntegrationStillAllowsExplicitLocalRead() {
        var disabled = service(false);
        when(store.findByUid(UID)).thenReturn(Optional.of(stock(UID, "cached", NOW)));
        assertThat(disabled.getCached(UID)).isPresent();
        assertThatThrownBy(() -> disabled.search("SBER")).isInstanceOfSatisfying(MarketException.class,
                e -> assertThat(e.reason()).isEqualTo(MarketException.Reason.DISABLED));
        assertThatThrownBy(() -> disabled.findByUid(UID, false)).isInstanceOf(MarketException.class);
        verifyNoInteractions(provider);
    }
}
