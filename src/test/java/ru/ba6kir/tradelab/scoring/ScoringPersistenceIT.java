package ru.ba6kir.tradelab.scoring;

import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.*;
import org.springframework.boot.test.context.*;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;
import org.springframework.transaction.*;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.telegram.telegrambots.longpolling.*;
import org.telegram.telegrambots.meta.api.methods.GetMe;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.objects.User;
import org.telegram.telegrambots.meta.generics.TelegramClient;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;
import ru.ba6kir.tradelab.analysis.application.port.AnalysisStore;
import ru.ba6kir.tradelab.analysis.application.service.FinancialMetricService;
import ru.ba6kir.tradelab.analysis.domain.metric.FinancialObservation;
import ru.ba6kir.tradelab.analysis.scoring.application.service.*;
import ru.ba6kir.tradelab.analysis.scoring.domain.model.*;
import ru.ba6kir.tradelab.analysis.scoring.domain.profile.ScoringModel;
import ru.ba6kir.tradelab.analysis.scoring.infrastructure.configuration.*;
import ru.ba6kir.tradelab.dividends.application.service.DividendService;
import ru.ba6kir.tradelab.fundamentals.application.port.FundamentalsStore;
import ru.ba6kir.tradelab.fundamentals.application.service.FundamentalsService;
import ru.ba6kir.tradelab.fundamentals.domain.model.*;
import ru.ba6kir.tradelab.market.application.InstrumentStore;
import ru.ba6kir.tradelab.market.domain.StockInstrument;
import ru.ba6kir.tradelab.telegram.adapter.TelegramUpdateConsumer;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static ru.ba6kir.tradelab.scoring.ScoringTestData.*;
import static ru.ba6kir.tradelab.telegram.TelegramTestData.*;

@SpringBootTest(properties={"tradelab.telegram.enabled=true","tradelab.telegram.bot-token="+TOKEN,
        "tradelab.telegram.owner-id="+OWNER,"tradelab.invest.enabled=false","tradelab.invest.token="})
@Import(ScoringPersistenceIT.Fakes.class)
@Testcontainers(disabledWithoutDocker=true)
class ScoringPersistenceIT {
    @Container static final PostgreSQLContainer<?> POSTGRES=new PostgreSQLContainer<>("postgres:17-bookworm");
    @DynamicPropertySource static void database(DynamicPropertyRegistry r) {
        r.add("tradelab.database.host",POSTGRES::getHost);r.add("tradelab.database.port",POSTGRES::getFirstMappedPort);r.add("tradelab.database.name",POSTGRES::getDatabaseName);
        r.add("tradelab.database.username",POSTGRES::getUsername);r.add("tradelab.database.password",POSTGRES::getPassword);
    }
    @Autowired FundamentalScoringService service;
    @Autowired ScoringResultRepository repository;
    @Autowired ScoringFingerprints fingerprints;
    @Autowired @Qualifier("fakeScoringInputs") ScoringInputProvider inputs;
    @Autowired FinancialMetricService metrics;
    @Autowired FundamentalsService fundamentals;
    @Autowired FundamentalsStore fundamentalStore;
    @Autowired DividendService dividends;
    @Autowired ru.ba6kir.tradelab.dividends.application.port.DividendStore dividendStore;
    @Autowired AnalysisStore classifications;
    @Autowired InstrumentStore instruments;
    @Autowired PlatformTransactionManager manager;
    @Autowired JdbcTemplate jdbc;
    @Autowired TelegramUpdateConsumer telegram;
    @Autowired TelegramClient client;
    ScoringInput fixture;
    @BeforeEach void setup() {
        // Only this test's disposable database is touched.
        for (String table:List.of("scoring_risk_flags","scoring_metric_contributions","stock_score_snapshots","scoring_model_versions","derived_financial_observations",
                "analysis_metric_definitions","issuer_sector_classifications","fundamental_metric_values","fundamental_snapshots","instrument_asset_links","asset_identities",
                "dividend_events","dividend_sync_state","market_quotes","instruments")) jdbc.update("DELETE FROM "+table);
        reset(inputs);clearInvocations(client);
        instruments.upsert(new StockInstrument(UID,"SYNTH","TQBR","SYNTHETIC TEST COMPANY",null,null,"RUB","MOEX",10,"NORMAL_TRADING",true,NOW,"SYNTHETIC"));
        fundamentalStore.saveIdentity(new AssetIdentity(ASSET,"SYNTHETIC ASSET","SYNTHETIC ISSUER",null,UID,"SYNTHETIC",NOW,"energy"));
        assertThat(fundamentalStore.tryStart(ASSET,NOW,Duration.ZERO)).isTrue();
        var raw=new FundamentalSnapshot(null,ASSET,"SYNTHETIC",NOW,NOW,null,Instant.parse("2025-01-01T00:00:00Z"),Instant.parse("2025-12-31T00:00:00Z"),"a".repeat(64),"PARTIAL",
                List.of(new MetricValue(MetricCode.PE,new BigDecimal("6"),MetricCode.Unit.MULTIPLE,null,"TTM",QualityStatus.VALID,null,"6"),
                        new MetricValue(MetricCode.ROE,new BigDecimal("0.2"),MetricCode.Unit.UNVERIFIED_RATE,null,null,QualityStatus.SUSPECT,"RATE_SCALE_UNDOCUMENTED","0.2"),
                        new MetricValue(MetricCode.NET_INCOME,new BigDecimal("100"),MetricCode.Unit.MONEY,"RUB","TTM",QualityStatus.VALID,null,"100")),Map.of("synthetic","true"));
        long snapshotId=fundamentalStore.saveSnapshot(raw,NOW).snapshot().id();
        var original=input("OIL_AND_GAS");
        fixture=withRows(original,original.observations().stream().map(o -> new FinancialObservation(o.id(),o.assetUid(),o.snapshotId()==null?null:snapshotId,o.metricCode(),o.value(),o.unit(),o.currency(),
                o.observationType(),o.reportingPeriod(),o.periodStart(),o.reportingEndDate(),o.providerAsOfDate(),o.fetchedAt(),o.source(),o.origin(),o.qualityStatus(),o.qualityReason(),o.methodology(),o.inputIds(),o.instrumentUid())).toList());
        when(inputs.instrument(UID)).thenAnswer(i -> { assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse(); return fixture; });
        when(inputs.asset(ASSET)).thenAnswer(i -> fixture);
    }
    @Test void ownerTelegramCommandsPersistExactInputsModelContributionsAndHistory() throws Exception {
        telegram.consume(List.of(update("/score "+UID),update("/scoreinfo "+UID),update("/scorehistory "+UID),update("/help"),update("/ping")));
        assertReply("79.975");assertReply("ABSOLUTE_PIECEWISE_LINEAR_V1");assertReply("synthetic-config-1");assertReply("/scorehistory");assertReply("TradeLab Portfolio: online");
        assertThat(count("stock_score_snapshots")).isEqualTo(1);assertThat(count("scoring_metric_contributions")).isEqualTo(8);
        assertThat(count("scoring_model_versions")).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT input_evidence::text FROM stock_score_snapshots",String.class)).contains("SYNTHETIC","synthetic:ROIC","periodStart");
        assertThat(service.getLatestScore(UID).orElseThrow().input()).isEqualTo(fixture);
    }
    @Test void identicalEvaluationsDeduplicateWithoutChangingOriginalTimestampAndUpdatedInputAddsHistory() {
        var first=service.evaluateInstrument(UID);var repeated=at(NOW.plusSeconds(60),model()).evaluateInstrument(UID);
        assertThat(repeated.id()).isEqualTo(first.id());assertThat(repeated.evaluatedAt()).isEqualTo(first.evaluatedAt());assertThat(count("stock_score_snapshots")).isEqualTo(1);
        fixture=replace(fixture,ru.ba6kir.tradelab.analysis.domain.metric.AnalysisMetric.PE,observation(ru.ba6kir.tradelab.analysis.domain.metric.AnalysisMetric.PE,"10"));
        var changed=service.evaluateInstrument(UID);assertThat(changed.inputFingerprint()).isNotEqualTo(first.inputFingerprint());assertThat(count("stock_score_snapshots")).isEqualTo(2);
        assertThat(service.getScoreHistory(UID)).hasSize(2).first().extracting(ScoringResult::id).isEqualTo(changed.id());
    }
    @Test void laterExpiryWithIdenticalRawInputCannotResurrectPreviousScoredResult() {
        var first=service.evaluateInstrument(UID);var expired=at(NOW.plus(Duration.ofDays(2)),model()).evaluateInstrument(UID);
        assertThat(first.status()).isEqualTo(ScoringResult.Status.SCORED);assertThat(expired.status()).isEqualTo(ScoringResult.Status.STALE_DATA);assertThat(expired.overallScore()).isNull();
        assertThat(expired.inputFingerprint()).isEqualTo(first.inputFingerprint());assertThat(count("stock_score_snapshots")).isEqualTo(2);
        assertThat(service.getLatestScore(UID).orElseThrow().status()).isEqualTo(ScoringResult.Status.STALE_DATA);
    }
    @Test void newModelVersionAppendsAndConfigurationCannotBeChangedUnderAnExistingVersion() {
        service.evaluateInstrument(UID);var m=model();
        var next=new ScoringModel("synthetic-model-2",m.configurationVersion(),m.weights(),m.mandatoryPillars(),m.observationMaxAge(),m.financialPeriodMaxAge(),1095,3,m.extremeYield(),m.highLeverage(),m.profiles());
        assertThat(at(NOW,next).evaluateInstrument(UID).status()).isEqualTo(ScoringResult.Status.SCORED);assertThat(count("scoring_model_versions")).isEqualTo(2);
        var conflict=new ScoringModel(m.modelVersion(),m.configurationVersion(),m.weights(),m.mandatoryPillars(),m.observationMaxAge(),m.financialPeriodMaxAge(),1095,3,new BigDecimal("21"),m.highLeverage(),m.profiles());
        var rejected=at(NOW,conflict).evaluateInstrument(UID);assertThat(rejected.status()).isEqualTo(ScoringResult.Status.FAILED);
        assertThat(rejected.eligibilityReasons()).containsExactly("MODEL_VERSION_CONFLICT");assertThat(count("stock_score_snapshots")).isEqualTo(2);
    }
    @Test void failedContributionBatchRollsBackSnapshotAndLeavesOldHistoryIntact() {
        var r=service.evaluateInstrument(UID);var duplicate=new ArrayList<>(r.contributions());duplicate.add(duplicate.getFirst());
        var broken=new ScoringResult(null,r.instrumentUid(),r.assetUid(),r.name(),r.classification(),r.profileId(),r.modelVersion(),r.configurationVersion(),r.configurationFingerprint(),r.inputFingerprint(),
                r.evaluatedAt(),r.dataCutoff(),r.status(),r.overallScore(),r.pillarScores(),duplicate,r.coverage(),r.risks(),r.eligibilityReasons(),r.inputSnapshotIds(),r.input(),r.explanationReferences());
        assertThatThrownBy(() -> repository.save(broken,model())).isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThat(count("stock_score_snapshots")).isEqualTo(1);assertThat(count("scoring_metric_contributions")).isEqualTo(8);
        assertThat(service.getLatestScore(UID).orElseThrow().id()).isEqualTo(r.id());
    }
    @Test void actualCacheAdapterUsesOneReadOnlyRepeatableReadSnapshotAndWritesNothing() {
        classifications.saveClassification(classification("OIL_AND_GAS"));
        var observed=spy(metrics);
        doAnswer(i -> { assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();assertThat(TransactionSynchronizationManager.isCurrentTransactionReadOnly()).isTrue();
            assertThat(TransactionSynchronizationManager.getCurrentTransactionIsolationLevel()).isEqualTo(TransactionDefinition.ISOLATION_REPEATABLE_READ);return i.callRealMethod(); }).when(observed).cached(UID);
        var real=new CachedScoringInputAdapter(observed,fundamentals,dividends,manager);var captured=real.instrument(UID);
        assertThat(captured.assetUid()).isEqualTo(ASSET);assertThat(captured.observations()).anyMatch(o -> o.unit()==ru.ba6kir.tradelab.analysis.domain.metric.AnalysisMetric.Unit.UNVERIFIED_RATE);
        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
        assertThat(count("derived_financial_observations")).isZero();assertThat(count("stock_score_snapshots")).isZero();
        var result=new ru.ba6kir.tradelab.analysis.scoring.domain.rule.FundamentalScoringEngine().evaluate(captured,model(),fingerprints.hash(model()),fingerprints.hash(captured),NOW);
        assertThat(result.overallScore()).isNull();assertThat(result.status()).isNotEqualTo(ScoringResult.Status.SCORED);
    }
    @Test void dryRunIsReadOnlyAndRiskFlagsRetainNumericEvidence() {
        assertThat(service.getScoringEligibility(UID).status()).isEqualTo(ScoringResult.Status.SCORED);assertThat(count("scoring_model_versions")).isZero();
        fixture=replace(fixture,ru.ba6kir.tradelab.analysis.domain.metric.AnalysisMetric.FREE_CASH_FLOW,observation(ru.ba6kir.tradelab.analysis.domain.metric.AnalysisMetric.FREE_CASH_FLOW,"-10"));
        service.evaluateInstrument(UID);
        assertThat(jdbc.queryForObject("SELECT actual_value FROM scoring_risk_flags WHERE rule_id='NEGATIVE_FCF'",BigDecimal.class)).isEqualByComparingTo("-10");
        assertThat(jdbc.queryForObject("SELECT supporting_inputs::text FROM scoring_risk_flags WHERE rule_id='NEGATIVE_FCF'",String.class)).contains("synthetic:FREE_CASH_FLOW");
    }
    @Test void assetEvaluationRequiresAnUnambiguousCachedSecurityContext() {
        assertThat(service.evaluateAsset(ASSET).instrumentUid()).isEqualTo(UID);
        instruments.upsert(new StockInstrument(UID2,"SYNTHP","TQBR","SYNTHETIC PREFERRED",null,null,"RUB","MOEX",10,null,true,NOW,"SYNTHETIC"));
        fundamentalStore.saveIdentity(new AssetIdentity(ASSET,"SYNTHETIC ASSET",null,null,UID2,"SYNTHETIC",NOW));
        var real=new CachedScoringInputAdapter(metrics,fundamentals,dividends,manager);
        assertThatThrownBy(() -> real.asset(ASSET)).hasMessage("ASSET_INSTRUMENT_AMBIGUOUS");
    }
    @Test void archivedEventsOutsideTheLatestDividendWindowDoNotInvalidateCoveredHistory() {
        classifications.saveClassification(classification("OIL_AND_GAS"));
        var dates=List.of(LocalDate.of(2022,10,15),LocalDate.of(2023,11,1),LocalDate.of(2024,11,1),LocalDate.of(2025,11,1),LocalDate.of(2026,9,1));
        var originals=dates.stream().map(date -> syntheticDividend(date,NOW.minusSeconds(120))).toList();
        assertThat(dividendStore.tryStart(UID,NOW.minusSeconds(120),Duration.ZERO)).isTrue();
        dividendStore.save(UID,originals,TODAY.minusYears(5),TODAY.plusDays(365),NOW.minusSeconds(120));
        assertThat(dividendStore.tryStart(UID,NOW,Duration.ZERO)).isTrue();
        dividendStore.save(UID,dates.stream().filter(d -> !d.isBefore(TODAY.minusYears(3))).map(d -> syntheticDividend(d,NOW)).toList(),TODAY.minusYears(3),TODAY.plusDays(365),NOW);
        jdbc.update("INSERT INTO market_quotes(instrument_uid,price,currency,quote_time,fetched_at,source) VALUES(?,?,?,?,?,?)",UID,new BigDecimal("100"),"RUB",NOW.atOffset(ZoneOffset.UTC),NOW.atOffset(ZoneOffset.UTC),"SYNTHETIC");
        var captured=new CachedScoringInputAdapter(metrics,fundamentals,dividends,manager).instrument(UID);
        assertThat(dividendStore.find(UID)).hasSize(5);assertThat(captured.dividends().reliable()).isTrue();assertThat(captured.dividends().recordedEvents()).isEqualTo(4);
        assertThat(captured.observations()).anySatisfy(o -> {assertThat(o.metricCode()).isEqualTo(ru.ba6kir.tradelab.analysis.domain.metric.AnalysisMetric.DIVIDEND_HISTORY);assertThat(o.origin()).isEqualTo(FinancialObservation.Origin.DERIVED);assertThat(o.value()).isEqualByComparingTo("4");});
    }
    private ru.ba6kir.tradelab.dividends.domain.model.DividendEvent syntheticDividend(LocalDate date,Instant retrieved) {
        java.util.function.Function<LocalDate,com.google.protobuf.Timestamp> timestamp=d -> com.google.protobuf.Timestamp.newBuilder().setSeconds(d.atStartOfDay(ZoneOffset.UTC).toEpochSecond()).build();
        var raw=ru.tinkoff.piapi.contract.v1.Dividend.newBuilder().setDividendNet(ru.tinkoff.piapi.contract.v1.MoneyValue.newBuilder().setCurrency("rub").setUnits(1))
                .setRecordDate(timestamp.apply(date)).setDeclaredDate(timestamp.apply(date.minusDays(30))).setLastBuyDate(timestamp.apply(date.minusDays(2)))
                .setPaymentDate(timestamp.apply(date.plusDays(10))).setCreatedAt(timestamp.apply(date.minusDays(30))).setDividendType("Regular Cash").setRegularity("Annual").build();
        return ru.ba6kir.tradelab.dividends.infrastructure.tinvest.TInvestDividendAdapter.normalize(UID,raw,retrieved);
    }
    private FundamentalScoringService at(Instant time,ScoringModel model) { return new FundamentalScoringService(inputs,repository,model,fingerprints,Clock.fixed(time,ZoneOffset.UTC)); }
    private long count(String table) { return jdbc.queryForObject("SELECT count(*) FROM "+table,Long.class); }
    private void assertReply(String text) throws Exception { verify(client,atLeastOnce()).execute(argThat((org.telegram.telegrambots.meta.api.methods.botapimethods.BotApiMethod<?> m) -> m instanceof SendMessage s && s.getText().contains(text))); }
    @TestConfiguration(proxyBeanMethods=false) static class Fakes {
        @Bean @Primary ScoringInputProvider fakeScoringInputs() { return mock(ScoringInputProvider.class); }
        @Bean @Primary FundamentalScoringService fixedScoring(ScoringInputProvider inputs,ScoringResultRepository repo,ScoringFingerprints hashes) { return new FundamentalScoringService(inputs,repo,model(),hashes,CLOCK); }
        @Bean @Primary TelegramClient fakeTelegramClient() throws Exception { var client=mock(TelegramClient.class);when(client.execute(any(GetMe.class))).thenReturn(User.builder().id(123456789L).isBot(true).firstName("test").userName("TradeLabTestBot").build());return client; }
        @Bean(destroyMethod="") @Primary TelegramBotsLongPollingApplication fakePollingApplication() throws Exception { var p=mock(TelegramBotsLongPollingApplication.class);var s=mock(BotSession.class);when(s.isRunning()).thenReturn(true);when(p.registerBot(anyString(),any(),any(),any())).thenReturn(s);return p; }
    }
}
