package ru.ba6kir.tradelab.research;

import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import ru.ba6kir.tradelab.analysis.domain.metric.*;
import ru.ba6kir.tradelab.analysis.domain.sector.SectorClassification;
import ru.ba6kir.tradelab.analysis.ranking.application.*;
import ru.ba6kir.tradelab.analysis.ranking.domain.*;
import ru.ba6kir.tradelab.analysis.reporting.application.*;
import ru.ba6kir.tradelab.analysis.scoring.domain.model.*;
import ru.ba6kir.tradelab.analysis.scoring.domain.profile.ScoringModel;
import ru.ba6kir.tradelab.market.domain.*;
import ru.ba6kir.tradelab.market.domain.price.MarketQuote;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static ru.ba6kir.tradelab.research.ResearchTestData.*;

class RankingServiceTest {
    RankingStore store;AnalysisSnapshotService snapshots;RankingEligibilityService eligibility;
    @BeforeEach void setup() {
        store=mock(RankingStore.class);snapshots=mock(AnalysisSnapshotService.class);eligibility=new RankingEligibilityService(model(),policy(),HASHES);
        when(store.tryStart(any(),any())).thenReturn(true);when(store.save(any(),any())).thenAnswer(i -> ((RankingRun)i.getArgument(0)).withId(1));
        when(store.state()).thenReturn(new RankingStore.State(null,null,null,null));when(store.latest()).thenReturn(Optional.empty());
    }
    StockRankingService service(List<AnalysisSnapshotService.StockSnapshot> values) {
        var members=values.stream().map(AnalysisSnapshotService.StockSnapshot::member).toList();when(snapshots.capture(eq(members),any())).thenReturn(values);
        return new StockRankingService(store,snapshots,() -> members,() -> members.stream().map(RankingRun.Member::key).toList(),model(),policy(),HASHES,CLOCK);
    }
    @ParameterizedTest @ValueSource(strings={"BANK","OIL_AND_GAS","METALS_AND_MINING","GENERAL_NON_FINANCIAL"})
    void fullySupportedProfilesAreEligible(String profile) {assertThat(eligibility.assess(snapshot(1,profile),NOW).status()).isEqualTo(RankingRun.Status.ELIGIBLE);}
    @Test void higherScoresSortFirstAndTiesUseStableUidIndependentOfInputOrder() {
        var a=snapshot(1,"OIL_AND_GAS");var b=snapshot(2,"OIL_AND_GAS");var lower=snapshot(3,replace(input(3,"OIL_AND_GAS"),AnalysisMetric.ROIC,row(3,AnalysisMetric.ROIC,"1")),model());
        var run=service(List.of(lower,b,a)).refresh().run();assertThat(run.entries()).extracting(RankingRun.Entry::instrumentUid).containsExactlyElementsOf(
                java.util.stream.Stream.concat(List.of(a.instrument().uid(),b.instrument().uid()).stream().sorted(),java.util.stream.Stream.of(lower.instrument().uid())).toList());
        assertThat(run.entries()).extracting(RankingRun.Entry::rank).containsExactly(1,2,3);assertThat(run.eligibleCount()).isEqualTo(3);
    }
    @Test void differentProfilesAndReportingPeriodsNeverShareAComparativeRank() {
        var one=input(3,"OIL_AND_GAS");var rows=one.observations().stream().map(o -> o.instrumentUid()!=null?o:new FinancialObservation(o.id(),o.assetUid(),o.snapshotId(),o.metricCode(),o.value(),o.unit(),o.currency(),o.observationType(),o.reportingPeriod(),
                o.periodStart(),END.minusDays(1),o.providerAsOfDate(),o.fetchedAt(),o.source(),o.origin(),o.qualityStatus(),o.qualityReason(),o.methodology(),o.inputIds(),o.instrumentUid())).toList();
        var run=service(List.of(snapshot(1,"BANK"),snapshot(2,"OIL_AND_GAS"),snapshot(3,rows(one,rows),model()))).refresh().run();
        assertThat(run.entries()).extracting(RankingRun.Entry::comparisonGroup).doesNotHaveDuplicates();assertThat(run.entries()).extracting(RankingRun.Entry::rank).containsOnly(1);
    }
    @Test void noPricePartialInformationAndUnsupportedSectorStayVisibleAsExclusions() {
        var partial=snapshot(2,replace(input(2,"OIL_AND_GAS"),AnalysisMetric.NET_DEBT_EBITDA,null),model());
        var base=input(3,"OIL_AND_GAS");var unknown=new ScoringInput(base.instrumentUid(),base.assetUid(),base.name(),SectorClassification.unknown(base.assetUid(),null),false,base.observations(),base.dividends(),base.tradingStatus(),base.apiTradeAvailable(),NOW,List.of());
        var run=service(List.of(withQuote(snapshot(1,"BANK"),null),partial,snapshot(3,unknown,model()))).refresh().run();
        assertThat(run.eligibleCount()).isZero();assertThat(run.excludedCount()).isEqualTo(3);assertThat(run.exclusionCounts()).containsKey("NO_MARKET_PRICE").containsKey("MISSING_NET_DEBT_EBITDA").containsKey("SCORE_UNSUPPORTED_PROFILE");
        assertThat(run.entries()).allSatisfy(e -> assertThat(e.rank()).isNull());
    }
    @Test void tradingMetadataCannotBeTreatedAsCurrentAvailabilityOrInvented() {
        var s=snapshot(1,"OIL_AND_GAS");var i=s.instrument();var stopped=new StockInstrument(i.uid(),i.ticker(),i.classCode(),i.name(),null,null,i.currency(),i.exchange(),1,"NOT_AVAILABLE_FOR_TRADING",false,NOW,i.source());
        assertThat(eligibility.assess(withInstrument(s,stopped),NOW).status()).isEqualTo(RankingRun.Status.TRADING_UNAVAILABLE);
        assertThat(eligibility.assess(withQuote(s,new MarketQuote(i.uid(),new java.math.BigDecimal("100"),"USD",NOW,NOW,"SYNTHETIC")),NOW).reasons()).contains("QUOTE_IDENTITY_OR_CURRENCY_MISMATCH");
    }
    @Test void expiredScoresQuotesAndDividendAsOfAreNotMixedWithFreshEvidence() {
        var s=snapshot(1,"OIL_AND_GAS");assertThat(eligibility.assess(s,NOW.plus(Duration.ofDays(2))).status()).isEqualTo(RankingRun.Status.STALE_DATA);
        assertThat(eligibility.assess(withQuote(s,new MarketQuote(uid(1),new java.math.BigDecimal("100"),"RUB",NOW.minus(Duration.ofDays(4)),NOW,"SYNTHETIC")),NOW).reasons()).contains("STALE_MARKET_PRICE");
        assertThat(eligibility.assess(s,NOW.plusSeconds(13*3600)).reasons()).contains("UNRELIABLE_DIVIDEND_HISTORY");
    }
    @Test void modelVersionMismatchRemainsExplicit() {
        var m=model();var other=new ScoringModel("synthetic-model-2",m.configurationVersion(),m.weights(),m.mandatoryPillars(),m.observationMaxAge(),m.financialPeriodMaxAge(),1095,3,m.extremeYield(),m.highLeverage(),m.profiles());
        assertThat(eligibility.assess(snapshot(1,input(1,"BANK"),other),NOW).reasons()).contains("MODEL_OR_CONFIGURATION_MISMATCH");
    }
    @Test void lowScoreAndFinancialCriticalRiskRemainEligibleRatherThanDisappearing() {
        var loss=replace(input(1,"OIL_AND_GAS"),AnalysisMetric.NET_INCOME,row(1,AnalysisMetric.NET_INCOME,"-1"));
        var e=eligibility.assess(snapshot(1,loss,model()),NOW);assertThat(e.status()).isEqualTo(RankingRun.Status.ELIGIBLE);assertThat(e.riskCodes()).contains("CRITICAL:NEGATIVE_NET_INCOME");
    }
    @Test void alternateShareClassesUseOneDeterministicSecurityAndDoNotCopyInputs() {
        var s1=snapshot(1,"OIL_AND_GAS");var base=input(2,"OIL_AND_GAS");var c=base.classification();var sameIssuer=new SectorClassification(c.assetUid(),s1.score().classification().issuerKey(),c.sector(),c.businessType(),c.source(),c.verifiedAt(),false,c.evidence(),null);
        var other=new ScoringInput(base.instrumentUid(),base.assetUid(),base.name(),sameIssuer,true,base.observations(),base.dividends(),base.tradingStatus(),true,NOW,List.of());
        var run=service(List.of(s1,snapshot(2,other,model()))).refresh().run();assertThat(run.eligibleCount()).isEqualTo(1);assertThat(run.excludedCount()).isEqualTo(1);
        assertThat(run.entries().stream().filter(e -> e.status()==RankingRun.Status.ELIGIBLE).findFirst().orElseThrow().instrumentUid()).isEqualTo(List.of(uid(1),uid(2)).stream().sorted().findFirst().orElseThrow());
        assertThat(run.entries().stream().filter(e -> e.rank()==null).findFirst().orElseThrow().reasons().getFirst()).startsWith("ALTERNATE_SECURITY_SAME_ISSUER_OR_ASSET:");
    }
    @Test void refreshFailurePreservesPreviousAndConcurrentOrCooldownRequestDoesNotCapture() {
        var s=service(List.of(snapshot(1,"OIL_AND_GAS")));when(snapshots.capture(anyList(),any())).thenThrow(new IllegalStateException("secret=private"));
        assertThat(s.refresh().outcome()).isEqualTo("FAILED_PREVIOUS_PRESERVED");verify(store).fail(NOW,"RANKING_GENERATION_FAILED");verify(store,never()).save(any(),any());
        clearInvocations(snapshots);when(store.tryStart(any(),any())).thenReturn(false);assertThat(s.refresh().outcome()).isEqualTo("COOLDOWN_OR_RUNNING");verifyNoInteractions(snapshots);
    }
    @Test void cachedCurrentOnlyReadsPersistedRankingsAndDetectsExpiryOrConfigurationChange() {
        var s=service(List.of(snapshot(1,"BANK")));var run=s.refresh().run();when(store.latest()).thenReturn(Optional.of(run));clearInvocations(snapshots);
        assertThat(s.current().expired()).isFalse();assertThat(s.current().configurationChanged()).isFalse();verifyNoInteractions(snapshots);
        var expired=new StockRankingService(store,snapshots,() -> {throw new AssertionError("No resolution during read");},() -> run.universe().stream().map(RankingRun.Member::key).toList(),model(),policy(),HASHES,Clock.fixed(NOW.plusSeconds(3601),ZoneOffset.UTC));
        assertThat(expired.current().expired()).isTrue();
        var changed=new StockRankingService(store,snapshots,List::of,List::of,model(),policy(),HASHES,CLOCK);assertThat(changed.current().configurationChanged()).isTrue();
    }
    @Test void commonCutoffIsCapturedAfterTickerResolutionAndIncludesFreshlyResolvedMetadata() {
        var s=snapshot(1,"BANK");var resolvedAt=NOW.plusSeconds(120);var i=s.instrument();
        var updated=withInstrument(s,new StockInstrument(i.uid(),i.ticker(),i.classCode(),i.name(),null,null,i.currency(),i.exchange(),1,i.tradingStatus(),true,resolvedAt,i.source()));
        var time=new java.util.concurrent.atomic.AtomicReference<>(NOW);var clock=new Clock() {
            public ZoneId getZone(){return ZoneOffset.UTC;}public Clock withZone(ZoneId zone){return this;}public Instant instant(){return time.get();}
        };
        when(snapshots.capture(List.of(s.member()),resolvedAt)).thenReturn(List.of(updated));
        var service=new StockRankingService(store,snapshots,() -> {time.set(resolvedAt);return List.of(s.member());},() -> List.of(s.member().key()),model(),policy(),HASHES,clock);
        var run=service.refresh().run();assertThat(run.createdAt()).isEqualTo(resolvedAt);assertThat(run.eligibleCount()).isEqualTo(1);
    }
}
