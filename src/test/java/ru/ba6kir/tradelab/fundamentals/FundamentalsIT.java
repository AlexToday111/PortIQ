package ru.ba6kir.tradelab.fundamentals;

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
import ru.ba6kir.tradelab.market.application.*;
import ru.ba6kir.tradelab.fundamentals.application.port.*;
import ru.ba6kir.tradelab.fundamentals.application.service.*;
import ru.ba6kir.tradelab.fundamentals.domain.model.*;
import ru.ba6kir.tradelab.fundamentals.domain.validation.FundamentalNormalizer;
import ru.ba6kir.tradelab.telegram.adapter.TelegramUpdateConsumer;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static ru.ba6kir.tradelab.fundamentals.FundamentalsTestData.*;
import static ru.ba6kir.tradelab.telegram.TelegramTestData.*;

@SpringBootTest(properties={"tradelab.telegram.enabled=true","tradelab.telegram.bot-token="+TOKEN,
        "tradelab.telegram.owner-id="+OWNER,"tradelab.invest.enabled=true","tradelab.invest.token=synthetic-test-token"})
@Import(FundamentalsIT.Fakes.class)
@Testcontainers(disabledWithoutDocker=true)
class FundamentalsIT {
    @Container static final PostgreSQLContainer<?> POSTGRES=new PostgreSQLContainer<>("postgres:17-bookworm");
    @DynamicPropertySource static void database(DynamicPropertyRegistry r) {
        r.add("tradelab.database.host",POSTGRES::getHost); r.add("tradelab.database.port",POSTGRES::getFirstMappedPort);
        r.add("tradelab.database.name",POSTGRES::getDatabaseName); r.add("tradelab.database.username",POSTGRES::getUsername);
        r.add("tradelab.database.password",POSTGRES::getPassword);
    }
    @Autowired FundamentalsStore store;
    @Autowired FundamentalsService service;
    @Autowired InstrumentStore instruments;
    @Autowired JdbcTemplate jdbc;
    @Autowired @Qualifier("fakeFundamentals") FundamentalsProvider provider;
    @Autowired @Qualifier("fakeResolver") AssetIdentityResolver resolver;
    @Autowired @Qualifier("fakeInstruments") StockInstrumentProvider stocks;
    @Autowired TelegramClient client;
    @Autowired TelegramUpdateConsumer telegram;
    @BeforeEach void setup() {
        // Every connection in this context targets the disposable Testcontainer above.
        for (String table:List.of("fundamental_metric_values","fundamental_snapshots","financial_report_events","financial_report_sync",
                "instrument_asset_links","asset_identities","market_quotes","daily_candles","market_sync_state","instruments")) jdbc.update("DELETE FROM "+table);
        reset(provider,resolver,stocks); clearInvocations(client);
        instruments.upsert(stock(UID)); instruments.upsert(stock(UID2));
        when(stocks.findShares("SBER")).thenReturn(List.of(stock(UID))); when(stocks.findShares("SBERP")).thenReturn(List.of(stock(UID2)));
        when(stocks.findByUid(UID)).thenReturn(Optional.of(stock(UID))); when(stocks.findByUid(UID2)).thenReturn(Optional.of(stock(UID2)));
        when(resolver.resolve(UID)).thenReturn(Optional.of(identity(UID))); when(resolver.resolve(UID2)).thenReturn(Optional.of(identity(UID2)));
        when(provider.fetch(ASSET)).thenAnswer(i -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            return Optional.of(data("5.25",NOW));
        });
        when(provider.fetchReports(eq(UID),any(),any())).thenReturn(List.of(new FinancialReportEvent(UID,NOW.plusSeconds(86400),2026,3,
                "PERIOD_TYPE_QUARTER",NOW.minusSeconds(1),SOURCE,NOW)));
    }
    private FundamentalsStore.SaveResult persist(String pe,Instant fetched) {
        store.saveIdentity(identity(UID)); assertThat(store.tryStart(ASSET,fetched,Duration.ZERO)).isTrue();
        return store.saveSnapshot(new FundamentalNormalizer().normalize(data(pe,fetched)),fetched);
    }
    @Test void completeOwnerTelegramFlowUsesRealDatabaseAndNoExternalTransaction() throws Exception {
        telegram.consume(List.of(update("/fundrefresh SBER"),update("/fundamentals SBER"),update("/fundstatus SBER"),
                update("/reports SBER"),update("/help"),update("/ping")));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM fundamental_snapshots",Long.class)).isEqualTo(1L);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM fundamental_metric_values",Long.class)).isEqualTo(27L);
        assertThat(store.latest(ASSET).orElseThrow().providerAsOf()).isNull();
        assertReply("P/E: 5.25x"); assertReply("не финансовые отчёты и не подтверждение публикации");
        assertReply("Asset UID: "+ASSET); assertReply("/fundrefresh"); assertReply("TradeLab Portfolio: online");
        verify(provider,times(1)).fetch(ASSET);
    }
    @Test void twoInstrumentsShareOneAssetSnapshotAndOneSuccessfulProviderFetch() {
        var first=service.getLatest("SBER"); var second=service.getLatest("SBERP");
        assertThat(first.identity().assetUid()).isEqualTo(second.identity().assetUid());
        assertThat(first.snapshot().id()).isEqualTo(second.snapshot().id());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM asset_identities",Long.class)).isEqualTo(1L);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM instrument_asset_links",Long.class)).isEqualTo(2L);
        verify(provider,times(1)).fetch(ASSET);
    }
    @Test void identicalRetrievalOnlyUpdatesObservationMetadataWithoutDuplicateMetrics() {
        var first=persist("5.25",NOW); var again=persist("5.25",NOW.plusSeconds(120));
        assertThat(first.changed()).isTrue(); assertThat(again.changed()).isFalse(); assertThat(again.updatedMetrics()).isZero();
        assertThat(first.snapshot().id()).isEqualTo(again.snapshot().id());
        assertThat(again.snapshot().fetchedAt()).isEqualTo(NOW); assertThat(again.snapshot().lastFetchedAt()).isEqualTo(NOW.plusSeconds(120));
        assertThat(jdbc.queryForObject("SELECT retrieval_count FROM fundamental_snapshots",Long.class)).isEqualTo(2L);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM fundamental_metric_values",Long.class)).isEqualTo(27L);
    }
    @Test void correctionAndReversionAreTraceableNewVersions() {
        var original=persist("5.25",NOW); var correction=persist("6.25",NOW.plusSeconds(120)); var reverted=persist("5.25",NOW.plusSeconds(240));
        assertThat(correction.updatedMetrics()).isEqualTo(1); assertThat(reverted.updatedMetrics()).isEqualTo(1);
        assertThat(reverted.snapshot().id()).isNotEqualTo(original.snapshot().id());
        assertThat(reverted.snapshot().contentHash()).isEqualTo(original.snapshot().contentHash());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM fundamental_snapshots",Long.class)).isEqualTo(3L);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM fundamental_metric_values",Long.class)).isEqualTo(81L);
    }
    @Test void numericNullQualityAndRawDiagnosticsSurviveRoundTrip() {
        var stored=persist("5.25",NOW).snapshot();
        assertThat(stored.metrics()).anySatisfy(m -> {
            assertThat(m.code()).isEqualTo(MetricCode.NET_INCOME); assertThat(m.value()).isEqualByComparingTo("-100.5");
            assertThat(m.currency()).isEqualTo("USD"); assertThat(m.quality()).isEqualTo(QualityStatus.VALID);
        });
        assertThat(stored.metrics()).anySatisfy(m -> {
            assertThat(m.code()).isEqualTo(MetricCode.EBITDA_MARGIN); assertThat(m.value()).isNull(); assertThat(m.quality()).isEqualTo(QualityStatus.UNSUPPORTED);
        });
        assertThat(stored.rawPayload().get("net_income_ttm")).contains("-100.5");
        assertThat(jdbc.queryForObject("SHOW TIME ZONE",String.class)).isEqualTo("UTC");
        assertThat(jdbc.queryForObject("SELECT data_type FROM information_schema.columns WHERE table_name='fundamental_snapshots' AND column_name='fetched_at'",String.class))
                .isEqualTo("timestamp with time zone");
    }
    @Test void inconsistentSnapshotBatchRollsBackCompletely() {
        var before=persist("5.25",NOW).snapshot();
        var incoming=new FundamentalNormalizer().normalize(data("6.25",NOW.plusSeconds(120)));
        var duplicate=new ArrayList<>(incoming.metrics()); duplicate.add(duplicate.getFirst());
        var broken=new FundamentalSnapshot(null,ASSET,SOURCE,incoming.fetchedAt(),incoming.lastFetchedAt(),incoming.providerAsOf(),
                incoming.periodStart(),incoming.periodEnd(),incoming.contentHash(),incoming.completeness(),duplicate,incoming.rawPayload());
        assertThat(store.tryStart(ASSET,NOW.plusSeconds(120),Duration.ZERO)).isTrue();
        assertThatThrownBy(() -> store.saveSnapshot(broken,NOW.plusSeconds(120))).isInstanceOf(org.springframework.dao.DuplicateKeyException.class);
        assertThat(store.latest(ASSET).orElseThrow().id()).isEqualTo(before.id());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM fundamental_snapshots",Long.class)).isEqualTo(1L);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM fundamental_metric_values",Long.class)).isEqualTo(27L);
    }
    @Test void outagePreservesSnapshotAndRecordsFailedAttempt() {
        service.refresh("SBER"); var before=store.latest(ASSET).orElseThrow();
        jdbc.update("UPDATE asset_identities SET last_attempt=last_attempt-INTERVAL '2 minutes'");
        doThrow(new MarketException(MarketException.Reason.UNAVAILABLE)).when(provider).fetch(ASSET);
        var result=service.refresh("SBER"); assertThat(result.outcome()).isEqualTo(FundamentalsService.Outcome.FAILED);
        assertThat(store.latest(ASSET).orElseThrow().id()).isEqualTo(before.id());
        assertThat(store.requestState(ASSET).lastSuccess()).isEqualTo(NOW); assertThat(store.requestState(ASSET).failureCode()).isEqualTo("UNAVAILABLE");
    }
    @Test void calendarIsIdempotentAndReschedulingAndEmptyResponsesDoNotExposeObsoleteEvents() {
        var event=new FinancialReportEvent(UID,NOW.plusSeconds(86400),2026,3,"PERIOD_TYPE_QUARTER",NOW.minusSeconds(1),SOURCE,NOW);
        store.saveReports(UID,List.of(event),NOW,NOW.minusSeconds(100),NOW.plusSeconds(200000));
        store.saveReports(UID,List.of(event),NOW.plusSeconds(60),NOW.minusSeconds(100),NOW.plusSeconds(200000));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM financial_report_events",Long.class)).isEqualTo(1L);
        var revised=new FinancialReportEvent(UID,NOW.plusSeconds(172800),2026,3,"PERIOD_TYPE_QUARTER",NOW.minusSeconds(1),SOURCE,NOW.plusSeconds(120));
        store.saveReports(UID,List.of(revised),NOW.plusSeconds(120),NOW.minusSeconds(100),NOW.plusSeconds(200000));
        assertThat(store.reports(UID).orElseThrow().events()).hasSize(1);
        assertThat(store.reports(UID).orElseThrow().events().getFirst().reportDate()).isEqualTo(revised.reportDate());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM financial_report_events",Long.class)).isEqualTo(2L);
        store.saveReports(UID,List.of(),NOW.plusSeconds(180),NOW.minusSeconds(100),NOW.plusSeconds(200000));
        assertThat(store.reports(UID).orElseThrow().events()).isEmpty();
    }
    private void assertReply(String fragment) throws Exception {
        verify(client,atLeastOnce()).execute(argThat((org.telegram.telegrambots.meta.api.methods.botapimethods.BotApiMethod<?> method) ->
                method instanceof SendMessage message && message.getChatId().equals(Long.toString(OWNER)) && message.getText().contains(fragment)));
    }
    @TestConfiguration(proxyBeanMethods=false)
    static class Fakes {
        @Bean @Primary StockInstrumentProvider fakeInstruments() { return mock(StockInstrumentProvider.class); }
        @Bean @Primary AssetIdentityResolver fakeResolver() { return mock(AssetIdentityResolver.class); }
        @Bean @Primary FundamentalsProvider fakeFundamentals() { return mock(FundamentalsProvider.class); }
        @Bean @Primary FundamentalsService fixedFundamentals(InstrumentService instruments,AssetIdentityResolver resolver,FundamentalsProvider provider,FundamentalsStore store) {
            return new FundamentalsService(instruments,resolver,provider,store,new FundamentalNormalizer(),CLOCK,Duration.ofDays(1),Duration.ofDays(548),Duration.ofMinutes(1),180,true);
        }
        @Bean @Primary TelegramClient fakeTelegramClient() throws Exception {
            var client=mock(TelegramClient.class); when(client.execute(any(GetMe.class))).thenReturn(User.builder().id(123456789L).isBot(true).firstName("test").userName("TradeLabTestBot").build()); return client;
        }
        @Bean(destroyMethod="") @Primary TelegramBotsLongPollingApplication fakePollingApplication() throws Exception {
            var polling=mock(TelegramBotsLongPollingApplication.class); var session=mock(BotSession.class);
            when(session.isRunning()).thenReturn(true); when(polling.registerBot(anyString(),any(),any(),any())).thenReturn(session); return polling;
        }
    }
}
