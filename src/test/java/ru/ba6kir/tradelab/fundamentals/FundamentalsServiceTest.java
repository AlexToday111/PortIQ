package ru.ba6kir.tradelab.fundamentals;

import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import ru.ba6kir.tradelab.market.application.*;
import ru.ba6kir.tradelab.fundamentals.application.port.*;
import ru.ba6kir.tradelab.fundamentals.application.service.*;
import ru.ba6kir.tradelab.fundamentals.domain.model.*;
import ru.ba6kir.tradelab.fundamentals.domain.validation.FundamentalNormalizer;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static ru.ba6kir.tradelab.fundamentals.FundamentalsTestData.*;

class FundamentalsServiceTest {
    private final InstrumentService instruments=mock(InstrumentService.class);
    private final AssetIdentityResolver resolver=mock(AssetIdentityResolver.class);
    private final FundamentalsProvider provider=mock(FundamentalsProvider.class);
    private final FundamentalsStore store=mock(FundamentalsStore.class);
    private final FundamentalsService service=new FundamentalsService(instruments,resolver,provider,store,new FundamentalNormalizer(),CLOCK,
            Duration.ofHours(24),Duration.ofDays(548),Duration.ofSeconds(60),180,true);
    @BeforeEach void setup() {
        when(instruments.resolve("SBER")).thenReturn(stock(UID));
        when(resolver.resolve(UID)).thenReturn(Optional.of(identity(UID)));
        when(provider.fetch(ASSET)).thenReturn(Optional.of(data("5.25",NOW)));
        when(store.tryStart(eq(ASSET),any(),any())).thenReturn(true);
        when(store.requestState(ASSET)).thenReturn(new FundamentalsStore.RequestState(NOW,null,null));
        when(store.saveSnapshot(any(),any())).thenAnswer(i -> new FundamentalsStore.SaveResult(i.getArgument(0),true,27));
    }
    @Test void resolvesAssetBeforeFetchAndNeverSendsInstrumentUidToFundamentals() {
        var result=service.refresh("SBER");
        assertThat(result.outcome()).isEqualTo(FundamentalsService.Outcome.CHANGED);
        verify(resolver).resolve(UID); verify(provider).fetch(ASSET); verify(provider,never()).fetch(UID);
        verify(store).saveIdentity(identity(UID));
        assertThat(result.view().snapshot().assetUid()).isEqualTo(ASSET);
    }
    @Test void cachedSharedAssetSnapshotNeedsNoProviderCall() {
        when(store.findIdentity(UID)).thenReturn(Optional.of(identity(UID)));
        when(store.latest(ASSET)).thenReturn(Optional.of(snapshot()));
        var view=service.getLatest("SBER");
        assertThat(view.cached()).isTrue(); assertThat(view.retrievalStale()).isFalse();
        verifyNoInteractions(provider,resolver);
    }
    @Test void missingMappingAndEmptyResponseDoNotPersistFabricatedSnapshots() {
        when(resolver.resolve(UID)).thenReturn(Optional.empty());
        assertThat(service.refresh("SBER").error()).isEqualTo("MAPPING_UNAVAILABLE");
        verifyNoInteractions(provider);
        when(resolver.resolve(UID)).thenReturn(Optional.of(identity(UID)));
        when(provider.fetch(ASSET)).thenReturn(Optional.empty());
        assertThat(service.refresh("SBER").error()).isEqualTo("EMPTY_RESPONSE");
        verify(store,never()).saveSnapshot(any(),any());
    }
    @Test void failedRefreshKeepsOldObservationAndMarksRetrievalStale() {
        var old=new FundamentalNormalizer().normalize(data("5.25",NOW.minus(Duration.ofDays(2))));
        when(store.findIdentity(UID)).thenReturn(Optional.of(identity(UID)));
        when(store.latest(ASSET)).thenReturn(Optional.of(old));
        when(provider.fetch(ASSET)).thenThrow(new MarketException(MarketException.Reason.UNAVAILABLE));
        var view=service.getLatest("SBER");
        assertThat(view.snapshot()).isEqualTo(old); assertThat(view.warning()).isEqualTo("UNAVAILABLE");
        assertThat(view.retrievalStale()).isTrue(); assertThat(view.usableMetrics()).isEmpty();
        assertThat(view.metrics()).anySatisfy(m -> assertThat(m.qualities()).contains(QualityStatus.STALE,QualityStatus.SUSPECT));
        verify(store,never()).saveSnapshot(any(),any());
        verify(store).failed(eq(ASSET),eq(NOW),eq(NOW),eq("UNAVAILABLE"));
    }
    @Test void financialPeriodAgeIsIndependentOfFreshRetrieval() {
        var original=data("5.25",NOW);
        var oldPeriod=new FundamentalNormalizer().normalize(new FundamentalData(ASSET,"USD",null,NOW.minus(Duration.ofDays(700)),null,NOW,SOURCE,original.values()));
        when(store.findIdentity(UID)).thenReturn(Optional.of(identity(UID))); when(store.latest(ASSET)).thenReturn(Optional.of(oldPeriod));
        var view=service.getLatest("SBER");
        assertThat(view.retrievalStale()).isFalse(); assertThat(view.reportingStale()).isTrue(); assertThat(view.usableMetrics()).isEmpty();
    }
    @Test void persistenceFailureIsSanitizedAndExistingSnapshotSurvives() {
        when(store.findIdentity(UID)).thenReturn(Optional.of(identity(UID))); when(store.latest(ASSET)).thenReturn(Optional.of(snapshot()));
        when(store.saveSnapshot(any(),any())).thenThrow(new IllegalStateException("private-host password=secret"));
        var result=service.refresh("SBER");
        assertThat(result.error()).isEqualTo("INTERNAL"); assertThat(result.view().snapshot()).isEqualTo(snapshot());
        assertThat(result.toString()).doesNotContain("private-host","password=secret");
    }
    @Test void mappingChangeCannotAttachOldSnapshotToNewAssetIfPersistenceFails() {
        var oldIdentity=new AssetIdentity(ASSET,"old",null,null,UID,SOURCE,NOW.minus(Duration.ofDays(2)));
        when(store.findIdentity(UID)).thenReturn(Optional.of(oldIdentity)); when(store.latest(ASSET)).thenReturn(Optional.of(snapshot()));
        var next=new AssetIdentity(ASSET2,"new",null,null,UID,SOURCE,NOW);
        when(resolver.resolve(UID)).thenReturn(Optional.of(next)); doThrow(new IllegalStateException("db")).when(store).saveIdentity(next);
        var result=service.refresh("SBER");
        assertThat(result.view().identity().assetUid()).isEqualTo(ASSET);
        assertThat(result.view().snapshot().assetUid()).isEqualTo(ASSET);
        assertThat(result.view().mappingStale()).isTrue(); verifyNoInteractions(provider);
    }
    @Test void reportsAreSeparateCalendarRequestsAndEmptyDoesNotMeanAStatement() {
        when(provider.fetchReports(eq(UID),any(),any())).thenReturn(List.of());
        var result=service.reports("SBER");
        assertThat(result.batch().events()).isEmpty(); assertThat(result.cached()).isFalse();
        verify(provider,never()).fetch(any()); verifyNoInteractions(resolver);
    }
    @Test void reportFailureReturnsExplicitlyStaleCache() {
        var cached=new FundamentalsStore.ReportBatch(List.of(),NOW.minus(Duration.ofDays(2)),NOW.minus(Duration.ofDays(180)),NOW);
        when(store.reports(UID)).thenReturn(Optional.of(cached));
        when(provider.fetchReports(eq(UID),any(),any())).thenThrow(new MarketException(MarketException.Reason.TIMEOUT));
        var result=service.reports("SBER");
        assertThat(result.stale()).isTrue(); assertThat(result.warning()).isEqualTo("TIMEOUT"); assertThat(result.batch().fetchedAt()).isEqualTo(cached.fetchedAt());
    }
    @Test void cooldownAndDisabledIntegrationMakeNoFundamentalRequests() {
        when(store.tryStart(eq(ASSET),any(),any())).thenReturn(false);
        assertThat(service.refresh("SBER").outcome()).isEqualTo(FundamentalsService.Outcome.COOLDOWN);
        verifyNoInteractions(provider);
        var disabled=new FundamentalsService(instruments,resolver,provider,store,new FundamentalNormalizer(),CLOCK,Duration.ofDays(1),Duration.ofDays(548),Duration.ofMinutes(1),180,false);
        assertThatThrownBy(() -> disabled.getLatest("SBER")).isInstanceOf(MarketException.class);
    }
}
