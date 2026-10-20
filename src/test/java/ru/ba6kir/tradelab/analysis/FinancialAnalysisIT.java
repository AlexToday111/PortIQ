package ru.ba6kir.tradelab.analysis;

import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.*;
import org.springframework.boot.test.context.*;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.telegram.telegrambots.longpolling.*;
import org.telegram.telegrambots.meta.api.methods.GetMe;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.objects.User;
import org.telegram.telegrambots.meta.generics.TelegramClient;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;
import ru.ba6kir.tradelab.analysis.application.port.AnalysisStore;
import ru.ba6kir.tradelab.analysis.application.service.*;
import ru.ba6kir.tradelab.analysis.domain.metric.*;
import ru.ba6kir.tradelab.analysis.domain.sector.*;
import ru.ba6kir.tradelab.fundamentals.application.port.FundamentalsStore;
import ru.ba6kir.tradelab.fundamentals.application.service.*;
import ru.ba6kir.tradelab.dividends.application.service.DividendService;
import ru.ba6kir.tradelab.market.application.*;
import ru.ba6kir.tradelab.telegram.adapter.TelegramUpdateConsumer;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static ru.ba6kir.tradelab.analysis.AnalysisTestData.*;
import static ru.ba6kir.tradelab.telegram.TelegramTestData.*;

@SpringBootTest(properties={"tradelab.telegram.enabled=true","tradelab.telegram.bot-token="+TOKEN,
        "tradelab.telegram.owner-id="+OWNER,"tradelab.invest.enabled=false","tradelab.invest.token="})
@Import(FinancialAnalysisIT.Fakes.class)
@Testcontainers(disabledWithoutDocker=true)
class FinancialAnalysisIT {
    @Container static final PostgreSQLContainer<?> POSTGRES=new PostgreSQLContainer<>("postgres:17-bookworm");
    @DynamicPropertySource static void database(DynamicPropertyRegistry r) {
        r.add("tradelab.database.host",POSTGRES::getHost); r.add("tradelab.database.port",POSTGRES::getFirstMappedPort);
        r.add("tradelab.database.name",POSTGRES::getDatabaseName); r.add("tradelab.database.username",POSTGRES::getUsername); r.add("tradelab.database.password",POSTGRES::getPassword);
    }
    @Autowired AnalysisStore store;
    @Autowired FundamentalsStore fundamentalsStore;
    @Autowired InstrumentStore instruments;
    @Autowired FinancialMetricService metrics;
    @Autowired SectorComparisonService comparisons;
    @Autowired @Qualifier("fakeAnalysisFundamentals") FundamentalsService fundamentals;
    @Autowired @Qualifier("fakeAnalysisDividends") DividendService dividends;
    @Autowired JdbcTemplate jdbc;
    @Autowired TelegramUpdateConsumer telegram;
    @Autowired TelegramClient client;
    @BeforeEach void setup() {
        // This context connects only to the disposable PostgreSQL container above.
        for (String table:List.of("derived_financial_observations","analysis_metric_definitions","issuer_sector_classifications","fundamental_metric_values",
                "fundamental_snapshots","instrument_asset_links","asset_identities","instruments")) jdbc.update("DELETE FROM "+table);
        reset(fundamentals,dividends); clearInvocations(client);
        instruments.upsert(stock(UID)); instruments.upsert(stock(UID2)); fundamentalsStore.saveIdentity(identity(UID,"energy"));
        fundamentalsStore.saveIdentity(identity(UID2,"energy"));
        assertThat(fundamentalsStore.tryStart(ASSET,NOW,Duration.ZERO)).isTrue();
        var saved=fundamentalsStore.saveSnapshot(snapshot(null),NOW).snapshot();
        var input=new FundamentalAnalysisView(stock(UID),identity(UID,"energy"),saved,true,false,false,false,null,new FundamentalsStore.RequestState(NOW,null,null));
        when(fundamentals.getLatest(anyString())).thenAnswer(i -> { assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse(); return input; });
        when(fundamentals.cachedAsset(ASSET)).thenReturn(Optional.of(saved));
        when(dividends.get(anyString(),eq(1))).thenThrow(new MarketException(MarketException.Reason.UNAVAILABLE));
    }
    @Test void ownerCommandsPersistClassificationAndDerivedLineageThroughRealDatabase() throws Exception {
        telegram.consume(List.of(update("/sector TEST"),update("/metrics TEST"),update("/compare TEST"),update("/help"),update("/ping"),update("/fundamentals TEST")));
        assertReply("OIL_AND_GAS"); assertReply("EARNINGS_YIELD [DERIVED] = 20"); assertReply("MISSING_INVALID_OR_UNDATED_SUBJECT"); assertReply("/compare"); assertReply("TradeLab Portfolio: online");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM issuer_sector_classifications",Long.class)).isEqualTo(1L);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM derived_financial_observations",Long.class)).isEqualTo(7L);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM fundamental_metric_values",Long.class)).isEqualTo(4L);
    }
    @Test void repeatedAnalysisDoesNotDuplicateProviderValuesDerivedRowsOrClassifications() {
        metrics.get("TEST"); metrics.get("TEST");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM derived_financial_observations",Long.class)).isEqualTo(7L);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM fundamental_snapshots",Long.class)).isEqualTo(1L);
        assertThat(jdbc.queryForObject("SELECT metric_value FROM derived_financial_observations WHERE metric_code='EARNINGS_YIELD'",java.math.BigDecimal.class)).isEqualByComparingTo("20");
        assertThat(jdbc.queryForObject("SELECT input_lineage::text FROM derived_financial_observations WHERE metric_code='EARNINGS_YIELD'",String.class)).contains("fund:",":PE");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM analysis_metric_definitions",Long.class)).isEqualTo((long)AnalysisMetric.values().length);
    }
    @Test void commonAndPreferredShareLinksRetainOneAssetClassification() {
        metrics.sector(UID); metrics.sector(UID2);
        assertThat(fundamentalsStore.findIdentity(UID).orElseThrow().assetUid()).isEqualTo(fundamentalsStore.findIdentity(UID2).orElseThrow().assetUid());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM issuer_sector_classifications",Long.class)).isEqualTo(1L);
        assertThat(fundamentalsStore.findIdentity(UID).orElseThrow().providerSector()).isEqualTo("energy");
    }
    @Test void overrideSurvivesLaterProviderWriteAndOriginalPrecisionIsRetained() {
        var override=new SectorClassification(ASSET,"issuer:0",SectorClassification.Sector.OIL_AND_GAS,SectorClassification.BusinessType.NON_FINANCIAL,
                SectorClassification.Source.MANUAL_VERIFIED,NOW,false,"manual",null);
        store.saveClassification(override);
        var forced=new SectorClassification(ASSET,"issuer:0",SectorClassification.Sector.CHEMICALS,SectorClassification.BusinessType.NON_FINANCIAL,
                SectorClassification.Source.MANUAL_VERIFIED,NOW.minusSeconds(10),true,"explicit override",null);
        store.saveClassification(forced); store.saveClassification(override);
        assertThat(store.classification(ASSET)).contains(forced);
        assertThat(jdbc.queryForObject("SHOW TIME ZONE",String.class)).isEqualTo("UTC");
    }
    @Test void derivedBatchIsAtomicAndCannotAttachOneAssetsSnapshotToAnotherAsset() {
        var view=metrics.get("TEST"); var good=view.observations().stream().filter(o -> o.origin()==FinancialObservation.Origin.DERIVED && o.snapshotId()!=null).findFirst().orElseThrow();
        jdbc.update("DELETE FROM derived_financial_observations");
        var other=new ru.ba6kir.tradelab.fundamentals.domain.model.AssetIdentity(asset(99),"synthetic","synthetic",null,UID2,"SYNTHETIC",NOW);
        fundamentalsStore.saveIdentity(other);
        var bad=new FinancialObservation("invalid-composite-lineage",asset(99),good.snapshotId(),good.metricCode(),good.value(),good.unit(),good.currency(),good.observationType(),
                good.reportingPeriod(),good.periodStart(),good.reportingEndDate(),good.providerAsOfDate(),good.fetchedAt(),good.source(),good.origin(),good.qualityStatus(),good.qualityReason(),good.methodology(),good.inputIds(),null);
        assertThatThrownBy(() -> store.saveDerived(List.of(good,bad))).isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM derived_financial_observations",Long.class)).isZero();
    }
    @Test void comparisonKeepsAnExplicitBoundedUniverseWithoutRemotePeerFetches() {
        metrics.get("TEST"); clearInvocations(fundamentals);
        var comparison=comparisons.compare("TEST");
        assertThat(comparison.comparisons()).isNotEmpty().allMatch(r -> r.relativePositionPercent()==null);
        assertThat(comparison.comparisons().getFirst().universe()).hasSize(1);
        verify(fundamentals,times(1)).getLatest("TEST"); verify(fundamentals).cachedAsset(ASSET);
    }
    private void assertReply(String text) throws Exception {
        verify(client,atLeastOnce()).execute(argThat((org.telegram.telegrambots.meta.api.methods.botapimethods.BotApiMethod<?> m) -> m instanceof SendMessage s && s.getText().contains(text)));
    }
    @TestConfiguration(proxyBeanMethods=false)
    static class Fakes {
        @Bean @Primary FundamentalsService fakeAnalysisFundamentals() { return mock(FundamentalsService.class); }
        @Bean @Primary DividendService fakeAnalysisDividends() { return mock(DividendService.class); }
        @Bean @Primary SectorClassificationService fixedAnalysisSectors(AnalysisStore store) { return new SectorClassificationService(store,List.of(classification(0)),CLOCK,Duration.ofDays(365)); }
        @Bean @Primary FinancialMetricService fixedAnalysisMetrics(FundamentalsService fundamentals,DividendService dividends,SectorClassificationService sectors,AnalysisStore store) {
            return new FinancialMetricService(fundamentals,dividends,sectors,store,CLOCK,Map.of());
        }
        @Bean @Primary SectorComparisonService fixedAnalysisComparisons(FinancialMetricService metrics,FundamentalsService fundamentals,SectorClassificationService sectors) {
            return new SectorComparisonService(metrics,fundamentals,sectors,CLOCK,Duration.ofDays(1),Duration.ofDays(548),5);
        }
        @Bean @Primary TelegramClient fakeTelegramClient() throws Exception {
            var client=mock(TelegramClient.class); when(client.execute(any(GetMe.class))).thenReturn(User.builder().id(123456789L).isBot(true).firstName("test").userName("TradeLabTestBot").build()); return client;
        }
        @Bean(destroyMethod="") @Primary TelegramBotsLongPollingApplication fakePollingApplication() throws Exception {
            var polling=mock(TelegramBotsLongPollingApplication.class); var session=mock(BotSession.class); when(session.isRunning()).thenReturn(true);
            when(polling.registerBot(anyString(),any(),any(),any())).thenReturn(session); return polling;
        }
    }
}
