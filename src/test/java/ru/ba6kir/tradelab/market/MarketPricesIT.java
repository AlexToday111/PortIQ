package ru.ba6kir.tradelab.market;

import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.*;
import org.springframework.boot.test.context.*;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;
import org.springframework.transaction.annotation.Transactional;
import org.telegram.telegrambots.longpolling.*;
import org.telegram.telegrambots.meta.api.methods.GetMe;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.objects.User;
import org.telegram.telegrambots.meta.generics.TelegramClient;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;
import ru.ba6kir.tradelab.market.application.*;
import ru.ba6kir.tradelab.market.application.price.*;
import ru.ba6kir.tradelab.market.domain.price.*;
import ru.ba6kir.tradelab.telegram.adapter.TelegramUpdateConsumer;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static ru.ba6kir.tradelab.market.InstrumentServiceTest.*;
import static ru.ba6kir.tradelab.market.PriceDomainTest.*;
import static ru.ba6kir.tradelab.telegram.TelegramTestData.*;

@SpringBootTest(properties={"tradelab.telegram.enabled=true", "tradelab.telegram.bot-token="+TOKEN,
        "tradelab.telegram.owner-id="+OWNER, "tradelab.invest.enabled=true", "tradelab.invest.token=synthetic-test-token"})
@Import(MarketPricesIT.Fakes.class)
@Testcontainers(disabledWithoutDocker=true)
@Transactional
class MarketPricesIT {
    @Container static final PostgreSQLContainer<?> POSTGRES=new PostgreSQLContainer<>("postgres:17-bookworm");
    @DynamicPropertySource static void database(DynamicPropertyRegistry r) {
        r.add("tradelab.database.host",POSTGRES::getHost); r.add("tradelab.database.port",POSTGRES::getFirstMappedPort);
        r.add("tradelab.database.name",POSTGRES::getDatabaseName); r.add("tradelab.database.username",POSTGRES::getUsername);
        r.add("tradelab.database.password",POSTGRES::getPassword);
    }
    @Autowired @Qualifier("fakeInstruments") StockInstrumentProvider instrumentProvider;
    @Autowired @Qualifier("fakePrices") MarketPriceProvider priceProvider;
    @Autowired InstrumentStore instruments;
    @Autowired MarketPriceStore prices;
    @Autowired MarketPriceService service;
    @Autowired JdbcTemplate jdbc;
    @Autowired TelegramClient client;
    @Autowired TelegramUpdateConsumer telegram;
    private List<DailyCandle> candles;
    @BeforeEach void setup() {
        reset(instrumentProvider,priceProvider);
        clearInvocations(client);
        var instrument=stock(UID,"Сбербанк",NOW);
        instruments.upsert(instrument);
        when(instrumentProvider.findShares("SBER")).thenReturn(List.of(instrument));
        when(instrumentProvider.findByUid(UID)).thenReturn(Optional.of(instrument));
        when(priceProvider.getLastPrice(any())).thenReturn(Optional.of(quote(NOW.minusSeconds(3600))));
        candles=List.of(candle(Instant.parse("2026-10-16T00:00:00Z"),"100",true),
                candle(Instant.parse("2026-10-19T00:00:00Z"),"110",true),candle(Instant.parse("2026-10-20T00:00:00Z"),"999",false));
        when(priceProvider.getDailyCandles(any(),any(),any())).thenAnswer(invocation -> {
            Instant from=invocation.getArgument(1), to=invocation.getArgument(2);
            return candles.stream().filter(c -> !c.candleTime().isBefore(from) && c.candleTime().isBefore(to)).toList();
        });
    }
    @Test void ownerCommandFlowPersistsPricesAndCalculatesHistoryThroughRealPostgres() throws Exception {
        telegram.consume(List.of(update("/refresh SBER"),update("/price SBER"),update("/history SBER 30d"),
                update("/stock SBER"),update("/status"),update("/help")));
        assertThat(prices.findQuote(UID).orElseThrow().price()).isEqualByComparingTo("123.123456789");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM daily_candles",Long.class)).isEqualTo(3L);
        assertReply("Синхронизация выполнена"); assertReply("Изменение цены: 10 (10%)");
        assertReply("незавершённых исключено: 1"); assertReply("T-Invest: включён"); assertReply("/refresh");
        assertThat(prices.status().lastSuccess()).isNotNull();
    }
    @Test void repeatedIncrementalRefreshFinalizesExistingCandleWithoutDuplicates() {
        assertThat(service.refresh("SBER").outcome()).isEqualTo(MarketPriceService.Outcome.SUCCESS);
        assertThat(service.refresh("SBER").outcome()).isEqualTo(MarketPriceService.Outcome.COOLDOWN);
        candles=List.of(candles.get(0),candles.get(1),candle(Instant.parse("2026-10-20T00:00:00Z"),"111",true));
        jdbc.update("UPDATE market_sync_state SET last_attempt=last_attempt-INTERVAL '2 minutes'");
        clearInvocations(priceProvider);
        assertThat(service.refresh("SBER").outcome()).isEqualTo(MarketPriceService.Outcome.SUCCESS);
        verify(priceProvider,times(1)).getDailyCandles(any(),any(),any());
        var stored=prices.findCandles(UID,LocalDate.parse("2026-10-16"),LocalDate.parse("2026-10-20"));
        assertThat(stored).hasSize(3);
        assertThat(stored.getLast().complete()).isTrue();
        assertThat(stored.getLast().close()).isEqualByComparingTo("111");
        prices.saveCandles(List.of(candle(Instant.parse("2026-10-20T00:00:00Z"),"999",false)));
        assertThat(prices.findCandles(UID,LocalDate.parse("2026-10-20"),LocalDate.parse("2026-10-20")).getFirst().close())
                .isEqualByComparingTo("111");
    }
    @Test void providerFailurePreservesStoredQuoteCandlesAndCoverageAndRecordsFailure() {
        service.refresh("SBER");
        var before=prices.syncState(UID).orElseThrow();
        jdbc.update("UPDATE market_sync_state SET last_attempt=last_attempt-INTERVAL '2 minutes'");
        when(priceProvider.getLastPrice(any())).thenThrow(new MarketException(MarketException.Reason.UNAVAILABLE));
        doThrow(new MarketException(MarketException.Reason.RATE_LIMIT)).when(priceProvider).getDailyCandles(any(),any(),any());
        assertThat(service.refresh("SBER").outcome()).isEqualTo(MarketPriceService.Outcome.FAILED);
        assertThat(prices.findQuote(UID)).isPresent();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM daily_candles",Long.class)).isEqualTo(3L);
        var after=prices.syncState(UID).orElseThrow();
        assertThat(after.coveredTo()).isEqualTo(before.coveredTo());
        assertThat(after.lastSuccess()).isEqualTo(before.lastSuccess());
        assertThat(after.lastFailure()).isNotNull();
        assertThat(after.failureCode()).contains("UNAVAILABLE","RATE_LIMIT");
    }
    @Test void decimalPrecisionUtcAndQuoteRegressionProtection() {
        var q=quote(NOW.minusSeconds(60)); prices.saveQuote(q); prices.saveQuote(q);
        prices.saveQuote(quote(NOW.minusSeconds(3600)));
        assertThat(prices.findQuote(UID).orElseThrow().quoteTime()).isEqualTo(q.quoteTime());
        assertThat(prices.findQuote(UID).orElseThrow().price()).isEqualByComparingTo("123.123456789");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM market_quotes",Long.class)).isEqualTo(1L);
        assertThat(jdbc.queryForObject("SHOW TIME ZONE",String.class)).isEqualTo("UTC");
        assertThat(jdbc.queryForObject("SELECT data_type FROM information_schema.columns WHERE table_name='daily_candles' AND column_name='candle_time'",
                String.class)).isEqualTo("timestamp with time zone");
        prices.saveQuote(new MarketQuote(UID,new BigDecimal("9223372036854775807.999999999"),"rub",NOW,NOW,q.source()));
        assertThat(prices.findQuote(UID).orElseThrow().price()).isEqualByComparingTo("9223372036854775807.999999999");
    }
    @Test void databaseEnforcesCandleUniqueness() {
        prices.saveCandles(candles); prices.saveCandles(candles);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM daily_candles",Long.class)).isEqualTo(3L);
        assertThatThrownBy(() -> jdbc.update("INSERT INTO daily_candles SELECT * FROM daily_candles LIMIT 1"))
                .isInstanceOf(org.springframework.dao.DuplicateKeyException.class);
    }
    @Test void admissionAndFailureStateAreDurableAndAnActiveLeasePreventsOverlap() {
        assertThat(prices.tryStart(UID,NOW,Duration.ofSeconds(60))).isTrue();
        assertThat(prices.tryStart(UID,NOW.plusSeconds(61),Duration.ofSeconds(60))).isFalse();
        prices.finish(UID,NOW,false,"TIMEOUT");
        assertThat(prices.syncState(UID).orElseThrow().lastFailure()).isEqualTo(NOW);
        assertThat(prices.tryStart(UID,NOW.plusSeconds(30),Duration.ofSeconds(60))).isFalse();
        assertThat(prices.tryStart(UID,NOW.plusSeconds(61),Duration.ofSeconds(60))).isTrue();
    }
    @Test void databaseEnforcesInstrumentForeignKeys() {
        assertThatThrownBy(() -> prices.saveQuote(new MarketQuote("22222222-2222-2222-2222-222222222222",BigDecimal.ONE,"rub",NOW,NOW,"test")))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }
    @Test
    @Transactional(propagation=org.springframework.transaction.annotation.Propagation.NOT_SUPPORTED)
    void failedCandleBatchRollsBackAllRowsInThatBatch() {
        var c=candles.getFirst();
        var missing=new DailyCandle("22222222-2222-2222-2222-222222222222",c.candleTime(),c.tradingDate(),
                c.open(),c.high(),c.low(),c.close(),c.volume(),c.complete(),c.source(),c.fetchedAt());
        assertThatThrownBy(() -> prices.saveCandles(List.of(c,missing))).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThat(prices.findCandles(UID,LocalDate.parse("2026-10-16"),LocalDate.parse("2026-10-20"))).isEmpty();
    }
    private void assertReply(String fragment) throws Exception {
        verify(client,atLeastOnce()).execute(argThat((org.telegram.telegrambots.meta.api.methods.botapimethods.BotApiMethod<?> method) ->
                method instanceof SendMessage message && message.getChatId().equals(Long.toString(OWNER)) && message.getText().contains(fragment)));
    }
    @TestConfiguration(proxyBeanMethods=false)
    static class Fakes {
        @Bean @Primary MarketPriceService testPriceService(InstrumentService instruments, MarketPriceProvider provider, MarketPriceStore store) {
            return new MarketPriceService(instruments,provider,store,Clock.fixed(NOW,ZoneOffset.UTC),Duration.ofSeconds(60),365,List.of("SBER"),true);
        }
        @Bean @Primary StockInstrumentProvider fakeInstruments() { return mock(StockInstrumentProvider.class); }
        @Bean @Primary MarketPriceProvider fakePrices() { return mock(MarketPriceProvider.class); }
        @Bean @Primary TelegramClient fakeTelegramClient() throws Exception {
            var client=mock(TelegramClient.class);
            when(client.execute(any(GetMe.class))).thenReturn(User.builder().id(123456789L).isBot(true).firstName("test").userName("TradeLabTestBot").build());
            return client;
        }
        @Bean(destroyMethod="") @Primary TelegramBotsLongPollingApplication fakePollingApplication() throws Exception {
            var polling=mock(TelegramBotsLongPollingApplication.class); var session=mock(BotSession.class);
            when(session.isRunning()).thenReturn(true); when(polling.registerBot(anyString(),any(),any(),any())).thenReturn(session);
            return polling;
        }
    }
}
