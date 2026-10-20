package ru.ba6kir.tradelab.dividends;

import java.math.BigDecimal;
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
import ru.tinkoff.piapi.contract.v1.MoneyValue;
import ru.ba6kir.tradelab.market.application.*;
import ru.ba6kir.tradelab.market.application.price.MarketPriceService;
import ru.ba6kir.tradelab.dividends.application.port.*;
import ru.ba6kir.tradelab.dividends.application.service.DividendService;
import ru.ba6kir.tradelab.dividends.domain.model.DividendEvent;
import ru.ba6kir.tradelab.dividends.infrastructure.tinvest.TInvestDividendAdapter;
import ru.ba6kir.tradelab.telegram.adapter.TelegramUpdateConsumer;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static ru.ba6kir.tradelab.dividends.DividendTestData.*;
import static ru.ba6kir.tradelab.telegram.TelegramTestData.*;

@SpringBootTest(properties={"tradelab.telegram.enabled=true","tradelab.telegram.bot-token="+TOKEN,
        "tradelab.telegram.owner-id="+OWNER,"tradelab.invest.enabled=true","tradelab.invest.token=synthetic-test-token"})
@Import(DividendsIT.Fakes.class)
@Testcontainers(disabledWithoutDocker=true)
class DividendsIT {
    @Container static final PostgreSQLContainer<?> POSTGRES=new PostgreSQLContainer<>("postgres:17-bookworm");
    @DynamicPropertySource static void database(DynamicPropertyRegistry r) {
        r.add("tradelab.database.host",POSTGRES::getHost); r.add("tradelab.database.port",POSTGRES::getFirstMappedPort);
        r.add("tradelab.database.name",POSTGRES::getDatabaseName); r.add("tradelab.database.username",POSTGRES::getUsername);
        r.add("tradelab.database.password",POSTGRES::getPassword);
    }
    @Autowired DividendStore store;
    @Autowired DividendService service;
    @Autowired InstrumentStore instruments;
    @Autowired JdbcTemplate jdbc;
    @Autowired @Qualifier("fakeDividends") DividendProvider provider;
    @Autowired @Qualifier("fakeDividendStocks") StockInstrumentProvider stocks;
    @Autowired TelegramClient client;
    @Autowired TelegramUpdateConsumer telegram;
    @BeforeEach void setup() {
        // These deletes target only this test's disposable database.
        for (String table:List.of("dividend_events","dividend_sync_state","market_quotes","instruments")) jdbc.update("DELETE FROM "+table);
        reset(provider,stocks); clearInvocations(client);
        instruments.upsert(stock(UID)); instruments.upsert(stock(UID2));
        when(stocks.findShares("SBER")).thenReturn(List.of(stock(UID))); when(stocks.findShares("SBERP")).thenReturn(List.of(stock(UID2)));
        when(stocks.findByUid(UID)).thenReturn(Optional.of(stock(UID))); when(stocks.findByUid(UID2)).thenReturn(Optional.of(stock(UID2)));
        when(provider.fetchDividends(eq(UID),any(),any())).thenAnswer(i -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse(); return List.of(event());
        });
        when(provider.fetchDividends(eq(UID2),any(),any())).thenReturn(List.of());
    }
    DividendStore.Counts persist(List<DividendEvent> events,Instant time) {
        assertThat(store.tryStart(UID,time,Duration.ZERO)).isTrue();
        return store.save(UID,events,TODAY.minusYears(5),TODAY.plusDays(365),time);
    }
    @Test void ownerCommandsUseActualDatabaseAndDynamicHelpWithNoNetworkTransaction() throws Exception {
        telegram.consume(List.of(update("/divrefresh SBER"),update("/dividends SBER"),update("/divhistory SBER 1y"),
                update("/divcalendar"),update("/help"),update("/stock SBER"),update("/status"),update("/ping")));
        assertThat(store.find(UID)).hasSize(1); assertReply("10.5 RUB"); assertReply("HISTORICAL_RECORDED");
        assertReply("История по датам реестра UTC"); assertReply("Дивидендный календарь UTC"); assertReply("/divhistory");
        assertReply("TradeLab Portfolio: online"); verify(provider,times(1)).fetchDividends(eq(UID),any(),any());
    }
    @Test void exactDuplicatesAcrossOverlappingWindowsRemainOneObservation() {
        assertThat(persist(List.of(event(),event()),NOW).added()).isEqualTo(1);
        assertThat(persist(List.of(event()),NOW.plusSeconds(120)).unchanged()).isEqualTo(1);
        var rows=store.find(UID); assertThat(rows).hasSize(1); assertThat(rows.getFirst().firstSeen()).isEqualTo(NOW);
        assertThat(rows.getFirst().lastSeen()).isEqualTo(NOW.plusSeconds(120)); assertThat(rows.getFirst().ambiguous()).isFalse();
    }
    @Test void distinctSameRecordDateEventsRemainSeparateWithoutDateOnlyDeduplication() {
        var second=event(raw().setDeclaredDate(day(TODAY.minusDays(21))).setCreatedAt(stamp("2026-09-29T12:00:00Z"))
                .setDividendNet(MoneyValue.newBuilder().setCurrency("rub").setUnits(5)));
        persist(List.of(event(),second),NOW);
        assertThat(store.find(UID)).hasSize(2).allMatch(o -> !o.ambiguous());
        assertThat(new ru.ba6kir.tradelab.dividends.domain.service.DividendAnalytics().trailingSum(store.find(UID),TODAY,true).value()).isEqualByComparingTo("15.5");
    }
    @Test void correctionsAndCancellationsRetainAllEvidenceAndSuppressOptimisticSum() {
        persist(List.of(event()),NOW);
        var correction=event(raw().setDividendNet(MoneyValue.newBuilder().setCurrency("rub").setUnits(20)));
        assertThat(persist(List.of(correction),NOW.plusSeconds(120)).updated()).isEqualTo(1);
        assertThat(store.find(UID)).hasSize(2).allMatch(DividendEvent.Observation::ambiguous);
        assertThat(persist(List.of(event(raw().setDividendType("Cancelled"))),NOW.plusSeconds(240)).cancellations()).isEqualTo(1);
        assertThat(store.find(UID)).hasSize(3).anyMatch(o -> o.event().status(TODAY)==DividendEvent.Status.CANCELLED);
        assertThat(new ru.ba6kir.tradelab.dividends.domain.service.DividendAnalytics().trailingSum(store.find(UID),TODAY,true).value()).isNull();
        persist(List.of(event()),NOW.plusSeconds(360)); assertThat(store.find(UID)).hasSize(3).allMatch(DividendEvent.Observation::ambiguous);
    }
    @Test void changedCreationTimestampOrSparseCancellationDoesNotMakePotentialCorrectionAdditive() {
        var original=event(); persist(List.of(original),NOW);
        var changed=event(raw().setCreatedAt(stamp("2026-10-01T12:00:00Z")).setDividendNet(MoneyValue.newBuilder().setCurrency("rub").setUnits(20)));
        persist(List.of(changed),NOW.plusSeconds(120)); assertThat(store.find(UID)).hasSize(2).allMatch(DividendEvent.Observation::ambiguous);
        var cancelled=event(raw().setCreatedAt(stamp("2026-10-02T12:00:00Z")).clearDividendNet().clearDeclaredDate().clearLastBuyDate().clearPaymentDate().setDividendType("Cancelled"));
        persist(List.of(cancelled),NOW.plusSeconds(240)); assertThat(store.find(UID)).hasSize(3).allMatch(DividendEvent.Observation::ambiguous);
    }
    @Test void precisionDatesNullsAndOriginalSourceEvidenceSurvivePostgres() {
        persist(List.of(event(raw().clearPaymentDate())),NOW);
        var e=store.find(UID).getFirst().event(); assertThat(e.amountPerShare()).isEqualByComparingTo("10.5");
        assertThat(e.paymentDate()).isNull(); assertThat(e.providerCreatedAt().getNano()).isEqualTo(123456789);
        assertThat(e.rawPayload()).isEqualTo(event(raw().clearPaymentDate()).rawPayload());
        assertThat(jdbc.queryForObject("SHOW TIME ZONE",String.class)).isEqualTo("UTC");
        assertThat(jdbc.queryForObject("SELECT data_type FROM information_schema.columns WHERE table_name='dividend_events' AND column_name='record_date'",String.class)).isEqualTo("date");
        assertThat(jdbc.queryForObject("SELECT data_type FROM information_schema.columns WHERE table_name='dividend_events' AND column_name='amount_per_share'",String.class)).isEqualTo("numeric");
        var fetchedLater=TInvestDividendAdapter.normalize(UID,raw().clearPaymentDate().build(),NOW.plusSeconds(5));
        persist(List.of(fetchedLater),NOW.plusSeconds(1));
        assertThat(store.find(UID).getFirst().lastSeen()).isEqualTo(NOW.plusSeconds(5));
    }
    @Test void failedBatchRollsBackEveryEventAndDoesNotAdvanceCoverage() {
        persist(List.of(event()),NOW);
        var e=event(raw().setDeclaredDate(day(TODAY.minusDays(22))));
        var broken=new DividendEvent(UID,new BigDecimal("1E50"),e.currency(),e.declaredDate(),e.lastBuyDate(),e.recordDate(),e.paymentDate(),
                e.dividendType(),e.regularity(),null,null,null,e.providerCreatedAt(),NOW,e.source(),"a".repeat(64),e.identityAnchor(),e.issues(),e.rawPayload());
        var attempt=NOW.plusSeconds(120); assertThat(store.tryStart(UID,attempt,Duration.ZERO)).isTrue();
        assertThatThrownBy(() -> store.save(UID,List.of(e,broken),TODAY.minusYears(5),TODAY.plusDays(365),attempt))
                .isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThat(store.find(UID)).hasSize(1); assertThat(store.state(UID).lastSuccess()).isEqualTo(NOW);
    }
    @Test void emptyAndFailedRequestsNeverDeletePreviouslyObservedHistory() {
        persist(List.of(event()),NOW); persist(List.of(),NOW.plusSeconds(120)); assertThat(store.find(UID)).hasSize(1);
        jdbc.update("UPDATE dividend_sync_state SET last_attempt=last_attempt-INTERVAL '10 minutes'");
        when(provider.fetchDividends(eq(UID),any(),any())).thenThrow(new MarketException(MarketException.Reason.UNAVAILABLE));
        var result=service.refresh("SBER"); assertThat(result.outcome()).isEqualTo("FAILED"); assertThat(result.view().events()).hasSize(1);
        assertThat(store.state(UID).failureCode()).isEqualTo("UNAVAILABLE");
    }
    @Test void admissionIsExclusiveAndLateExpiredWriterCannotOverwriteNewerSync() {
        assertThat(store.tryStart(UID,NOW,Duration.ofSeconds(60))).isTrue();
        assertThat(store.tryStart(UID,NOW.plusSeconds(61),Duration.ofSeconds(60))).isFalse();
        assertThat(store.tryStart(UID,NOW.plusSeconds(121),Duration.ofSeconds(60))).isTrue();
        assertThatThrownBy(() -> store.save(UID,List.of(event()),TODAY.minusYears(3),TODAY.plusDays(365),NOW)).isInstanceOf(IllegalStateException.class);
        assertThat(store.find(UID)).isEmpty();
    }
    @Test void preferredSharesNeverReceiveCommonShareEventsAndCalendarHasUpcomingAnnouncedEvidence() {
        var future=event(raw().setRecordDate(day(TODAY.plusDays(10))).setLastBuyDate(day(TODAY.plusDays(8))).setPaymentDate(day(TODAY.plusDays(20))));
        when(provider.fetchDividends(eq(UID),any(),any())).thenReturn(List.of(future));
        var common=service.get("SBER",1); var preferred=service.get("SBERP",1);
        assertThat(common.events()).hasSize(1); assertThat(preferred.events()).isEmpty();
        var calendar=service.calendar(); assertThat(calendar.rows()).extracting(DividendService.CalendarRow::date).isSorted();
        assertThat(calendar.rows()).anyMatch(r -> r.kind().equals("реестр") && r.observation().event().status(TODAY)==DividendEvent.Status.ANNOUNCED);
        assertThat(calendar.rows()).allMatch(r -> r.ticker().equals("SBER"));
    }
    @Test void trailingYieldUsesOnlyDatedSameCurrencyStoredQuoteAndDoesNotFetchMarketPrices() {
        jdbc.update("INSERT INTO market_quotes(instrument_uid,price,currency,quote_time,fetched_at,source) VALUES(?,?,?,?,?,?)",
                UID,new BigDecimal("200"),"RUB",NOW.atOffset(ZoneOffset.UTC),NOW.atOffset(ZoneOffset.UTC),"T_INVEST_PRODUCTION");
        var view=service.get("SBER",1); assertThat(view.trailingSum().value()).isEqualByComparingTo("10.5");
        assertThat(view.trailingYield().value()).isEqualByComparingTo("5.25"); assertThat(view.price().date()).isEqualTo(TODAY);
        jdbc.update("UPDATE market_quotes SET currency='USD' WHERE instrument_uid=?",UID);
        assertThat(service.get("SBER",1).trailingYield().reason()).isEqualTo("CURRENCY_MISMATCH");
        verify(provider,times(1)).fetchDividends(eq(UID),any(),any());
    }
    private void assertReply(String text) throws Exception {
        verify(client,atLeastOnce()).execute(argThat((org.telegram.telegrambots.meta.api.methods.botapimethods.BotApiMethod<?> m) ->
                m instanceof SendMessage s && s.getChatId().equals(Long.toString(OWNER)) && s.getText().contains(text)));
    }
    @TestConfiguration(proxyBeanMethods=false)
    static class Fakes {
        @Bean @Primary StockInstrumentProvider fakeDividendStocks() { return mock(StockInstrumentProvider.class); }
        @Bean @Primary DividendProvider fakeDividends() { return mock(DividendProvider.class); }
        @Bean @Primary DividendService fixedDividends(InstrumentService instruments,MarketPriceService prices,DividendProvider provider,DividendStore store) {
            return new DividendService(instruments,prices,provider,store,CLOCK,Duration.ofDays(1),Duration.ofSeconds(60),3,365,List.of("SBER","SBERP"),true);
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
