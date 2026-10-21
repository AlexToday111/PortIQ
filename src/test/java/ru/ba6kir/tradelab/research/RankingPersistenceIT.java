package ru.ba6kir.tradelab.research;

import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.*;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;
import ru.ba6kir.tradelab.analysis.application.service.FinancialMetricService;
import ru.ba6kir.tradelab.analysis.ranking.application.*;
import ru.ba6kir.tradelab.analysis.ranking.domain.*;
import ru.ba6kir.tradelab.analysis.ranking.persistence.*;
import ru.ba6kir.tradelab.analysis.reporting.application.*;
import ru.ba6kir.tradelab.analysis.reporting.presentation.StockAnalysisFormatter;
import ru.ba6kir.tradelab.analysis.scoring.infrastructure.persistence.JdbcScoringResultRepository;
import ru.ba6kir.tradelab.dividends.application.service.DividendService;
import ru.ba6kir.tradelab.fundamentals.application.service.FundamentalAnalysisView;
import ru.ba6kir.tradelab.fundamentals.domain.model.AssetIdentity;
import ru.ba6kir.tradelab.fundamentals.infrastructure.persistence.JdbcFundamentalsStore;
import ru.ba6kir.tradelab.market.application.*;
import ru.ba6kir.tradelab.market.application.price.MarketPriceService;
import ru.ba6kir.tradelab.market.infrastructure.persistence.JdbcInstrumentStore;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static ru.ba6kir.tradelab.research.ResearchTestData.*;

@Testcontainers(disabledWithoutDocker=true)
class RankingPersistenceIT {
    @Container static final PostgreSQLContainer<?> POSTGRES=new PostgreSQLContainer<>("postgres:17-bookworm");
    JdbcTemplate jdbc;DataSourceTransactionManager manager;JdbcRankingStore store;JdbcScoringResultRepository scores;JdbcInstrumentStore instruments;
    ObjectMapper json=new ObjectMapper().findAndRegisterModules();
    @BeforeEach void setup() {
        var source=new DriverManagerDataSource(POSTGRES.getJdbcUrl(),POSTGRES.getUsername(),POSTGRES.getPassword());jdbc=new JdbcTemplate(source);manager=new DataSourceTransactionManager(source);
        Flyway.configure().dataSource(source).load().migrate();
        // This is the disposable Testcontainers database, never a configured application database.
        jdbc.execute("TRUNCATE ranking_entries,ranking_runs,stock_score_snapshots,scoring_model_versions,asset_identities,instruments RESTART IDENTITY CASCADE");
        jdbc.update("UPDATE ranking_refresh_state SET last_attempt=NULL,lease_until=NULL,last_success=NULL,last_failure=NULL");
        store=new JdbcRankingStore(jdbc,json,manager,CLOCK);scores=new JdbcScoringResultRepository(jdbc,manager,json,HASHES);instruments=new JdbcInstrumentStore(jdbc);
    }
    AnalysisSnapshotService.StockSnapshot persist(int n,String profile) {
        var s=snapshot(n,profile);instruments.upsert(s.instrument());new JdbcFundamentalsStore(jdbc,manager,json).saveIdentity(new AssetIdentity(asset(n),"SYNTHETIC ASSET "+n,"SYNTHETIC ISSUER "+n,null,uid(n),"SYNTHETIC",NOW));
        return withScore(s,scores.save(s.score(),model()),model());
    }
    StockRankingService service(RankingStore persistence,List<AnalysisSnapshotService.StockSnapshot> values,Clock clock) {
        var members=values.stream().map(AnalysisSnapshotService.StockSnapshot::member).toList();return new StockRankingService(persistence,(m,asOf) -> values,() -> members,() -> members.stream().map(RankingRun.Member::key).toList(),model(),policy(),HASHES,clock);
    }
    @Test void rankingRoundTripRetainsExactUniverseScoresExclusionsAndSourceReferences() {
        var a=persist(1,"BANK");var b=withQuote(persist(2,"OIL_AND_GAS"),null);var refreshed=service(store,List.of(a,b),CLOCK).refresh();
        assertThat(refreshed.outcome()).isEqualTo("SUCCESS");var stored=store.latest().orElseThrow();assertThat(stored).isEqualTo(refreshed.run());
        assertThat(stored.eligibleCount()).isEqualTo(1);assertThat(stored.excludedCount()).isEqualTo(1);assertThat(stored.exclusionCounts()).containsKey("NO_MARKET_PRICE");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ranking_entries e JOIN stock_score_snapshots s ON s.id=e.score_snapshot_id",Long.class)).isEqualTo(2);
        assertThat(stored.entries().getFirst().sourceReferences()).anySatisfy(ref -> assertThat(ref).contains("status=NORMAL_TRADING","api=true"));
        assertThat(scores.model(HASHES.hash(model()))).contains(model());
    }
    @Test void failureAfterNewRunInsertRollsBackAndKeepsPreviousCompleteRanking() {
        var s=persist(1,"BANK");var previous=service(store,List.of(s),CLOCK).refresh().run();var token=NOW.plusSeconds(61);assertThat(store.tryStart(token,policy().refreshCooldown())).isTrue();
        var duplicate=new RankingRun(null,token,token.plusSeconds(3600),previous.modelVersion(),previous.configurationFingerprint(),policy(),previous.policyFingerprint(),previous.universeHash(),previous.universe(),List.of(previous.entries().getFirst(),previous.entries().getFirst()));
        assertThatThrownBy(() -> store.save(duplicate,token)).isInstanceOf(RuntimeException.class);store.fail(token,"RANKING_GENERATION_FAILED");
        assertThat(store.latest().orElseThrow().id()).isEqualTo(previous.id());assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ranking_runs",Long.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ranking_entries",Long.class)).isEqualTo(1);assertThat(store.state().lastFailure()).isEqualTo("RANKING_GENERATION_FAILED");
    }
    @Test void durableLeaseAllowsExactlyOneConcurrentRefreshAndCooldownSurvivesNewAdapter() throws Exception {
        try (var pool=Executors.newFixedThreadPool(2)) {
            var barrier=new CyclicBarrier(2);var attempts=pool.invokeAll(List.<Callable<Boolean>>of(() -> {barrier.await();return store.tryStart(NOW,policy().refreshCooldown());},
                    () -> {barrier.await();return new JdbcRankingStore(jdbc,json,manager,CLOCK).tryStart(NOW,policy().refreshCooldown());}));
            assertThat(attempts.stream().map(f -> {try{return f.get();}catch(Exception e){throw new RuntimeException(e);}}).toList()).containsExactlyInAnyOrder(true,false);
        }
        store.fail(NOW,"TEST_FAILURE");assertThat(new JdbcRankingStore(jdbc,json,manager,CLOCK).tryStart(NOW.plusSeconds(30),policy().refreshCooldown())).isFalse();
        assertThat(store.tryStart(NOW.plusSeconds(61),policy().refreshCooldown())).isTrue();
    }
    @Test void successfulRegenerationAppendsAndFailureDoesNotChangeLastSuccess() {
        var s=persist(1,"BANK");var first=service(store,List.of(s),CLOCK).refresh().run();var later=NOW.plusSeconds(61);
        var second=service(store,List.of(s),Clock.fixed(later,ZoneOffset.UTC)).refresh().run();assertThat(second.id()).isGreaterThan(first.id());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ranking_runs",Long.class)).isEqualTo(2);assertThat(store.state().lastSuccess()).isEqualTo(later);
    }
    @Test void noEligibleOrUnresolvedUniverseIsPersistedWithoutInventingScoresOrModelRows() {
        var member=new RankingRun.Member("uid:"+uid(9),uid(9),uid(9),"UID_NOT_IN_LOCAL_CATALOG");
        var s=new AnalysisSnapshotService.StockSnapshot(member,null,null,null,null,null,List.of(),null,List.of());
        var run=service(store,List.of(s),CLOCK).refresh().run();assertThat(run).isNotNull();assertThat(run.eligibleCount()).isZero();assertThat(run.excludedCount()).isEqualTo(1);
        assertThat(store.latest().orElseThrow().entries().getFirst().instrumentUid()).isEqualTo(uid(9));assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM stock_score_snapshots",Long.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM scoring_model_versions",Long.class)).isZero();
    }
    @Test void realSnapshotReaderIsRepeatableReadReadOnlyAndMakesNoExternalCallsOrWrites() {
        var s=persist(1,"BANK");var provider=mock(StockInstrumentProvider.class);var instrumentService=new InstrumentService(provider,instruments,CLOCK,Duration.ofDays(1),true);
        var metrics=mock(FinancialMetricService.class);var dividends=mock(DividendService.class);var prices=mock(MarketPriceService.class);
        when(metrics.cached(uid(1))).thenAnswer(call -> {
            assertThat(TransactionSynchronizationManager.isCurrentTransactionReadOnly()).isTrue();assertThat(TransactionSynchronizationManager.getCurrentTransactionIsolationLevel()).isEqualTo(TransactionDefinition.ISOLATION_REPEATABLE_READ);
            var input=new FundamentalAnalysisView(s.instrument(),new AssetIdentity(asset(1),"SYNTHETIC",null,null,uid(1),"SYNTHETIC",NOW),null,true,false,false,false,null,null);
            return new FinancialMetricService.View(new FinancialMetricService.SectorView(input,s.classification(),true),null,null,s.observations(),List.of(),NOW);
        });
        when(dividends.cached(uid(1))).thenReturn(s.dividends());when(prices.cachedQuote(uid(1))).thenReturn(Optional.of(s.quote()));
        var reader=new CachedAnalysisSnapshotService(instrumentService,prices,dividends,metrics,scores,manager);var captured=reader.capture(List.of(s.member()),NOW).getFirst();
        assertThat(captured.score()).isEqualTo(s.score());assertThat(captured.model()).isEqualTo(model());assertThat(captured.observations()).isEqualTo(s.score().input().observations());
        assertThat(captured.warnings()).doesNotContain("CURRENT_ASSET_MAPPING_MISMATCH");verifyNoInteractions(provider);verify(prices,never()).lastPrice(any());verify(dividends,never()).get(any(),anyInt());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM stock_score_snapshots",Long.class)).isEqualTo(1);assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ranking_runs",Long.class)).isZero();
        var report=new StockAnalysisService(instrumentService,reader,new AnalysisExplanationService(),new RankingEligibilityService(model(),policy(),HASHES),CLOCK).analyze(uid(1));
        assertThat(new StockAnalysisFormatter().format(report,false)).contains("Анализ компании","BANK","score:"+s.score().id());verifyNoInteractions(provider);
    }
    @Test void cachedRankingReadDoesNotResolveUniverseOrCaptureNewInputs() {
        var s=persist(1,"BANK");var run=service(store,List.of(s),CLOCK).refresh().run();var provider=mock(AnalysisSnapshotService.class);
        var cached=new StockRankingService(store,provider,() -> {throw new AssertionError("No provider resolution");},() -> run.universe().stream().map(RankingRun.Member::key).toList(),model(),policy(),HASHES,CLOCK);
        assertThat(cached.current().run()).isEqualTo(run);verifyNoInteractions(provider);
    }
}
