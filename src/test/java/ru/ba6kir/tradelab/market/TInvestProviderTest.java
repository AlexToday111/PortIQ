package ru.ba6kir.tradelab.market;

import java.time.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import io.grpc.*;
import org.junit.jupiter.api.Test;
import ru.ba6kir.tradelab.market.application.MarketException;
import ru.ba6kir.tradelab.market.infrastructure.tinvest.*;
import ru.tinkoff.piapi.contract.v1.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static ru.ba6kir.tradelab.market.InstrumentServiceTest.*;

/** Real generated stubs over an in-memory scripted Channel; never uses credentials or network. */
class TInvestProviderTest {
    private final ScriptedChannel transport = new ScriptedChannel();
    private final ManagedChannel managed = mock(ManagedChannel.class);
    private final TInvestStockInstrumentProvider provider = new TInvestStockInstrumentProvider(
            InstrumentsServiceGrpc.newBlockingStub(transport), managed,
            new InvestProperties(true, "synthetic-test-token", Duration.ofHours(24), Duration.ofMillis(100),
                    3, Duration.ofMillis(1)), Clock.fixed(NOW, ZoneOffset.UTC));

    @Test void mapsFullShareAndMissingOptionalMetadata() {
        transport.responses.add(ShareResponse.newBuilder().setInstrument(Share.newBuilder().setUid(UID)
                .setTicker("SBER").setClassCode("TQBR").setName("Сбербанк").setCurrency("rub")
                .setExchange("MOEX").setLot(10).setApiTradeAvailableFlag(true)
                .setTradingStatus(SecurityTradingStatus.SECURITY_TRADING_STATUS_NORMAL_TRADING)).build());
        var s = provider.findByUid(UID).orElseThrow();
        assertThat(s.uid()).isEqualTo(UID);
        assertThat(s.figi()).isNull();
        assertThat(s.isin()).isNull();
        assertThat(s.lotSize()).isEqualTo(10);
        assertThat(s.exchange()).isEqualTo("MOEX");
        assertThat(s.apiTradeAvailable()).isTrue();
        assertThat(s.updatedAt()).isEqualTo(NOW);
        assertThat(transport.requests.getFirst()).isInstanceOfSatisfying(InstrumentRequest.class,
                r -> assertThat(r.getIdType()).isEqualTo(InstrumentIdType.INSTRUMENT_ID_TYPE_UID));
        assertThat(transport.methods).containsExactly("tinkoff.public.invest.api.contract.v1.InstrumentsService/ShareBy");
        transport.responses.add(ShareResponse.newBuilder().setInstrument(Share.newBuilder().setUid(UID)).build());
        var missing = provider.findByUid(UID).orElseThrow();
        assertThat(missing.lotSize()).isNull();
        assertThat(missing.tradingStatus()).isNull();
    }

    @Test void searchFiltersNonSharesPrefersMoexAndDoesNotDownloadCatalogOrFanOut() {
        transport.responses.add(FindInstrumentResponse.newBuilder()
                .addInstruments(shortInstrument("foreign", "SPBXM", InstrumentType.INSTRUMENT_TYPE_SHARE))
                .addInstruments(shortInstrument(UID, "TQBR", InstrumentType.INSTRUMENT_TYPE_SHARE))
                .addInstruments(shortInstrument("bond", "TQOB", InstrumentType.INSTRUMENT_TYPE_BOND)).build());
        var result = provider.findShares("SBER");
        assertThat(result).extracting(s -> s.uid()).containsExactly(UID, "foreign");
        assertThat(result.getFirst().lotSize()).isNull();
        assertThat(result.getFirst().currency()).isNull();
        assertThat(transport.requests.getFirst()).isInstanceOfSatisfying(FindInstrumentRequest.class,
                r -> assertThat(r.getInstrumentKind()).isEqualTo(InstrumentType.INSTRUMENT_TYPE_SHARE));
        assertThat(transport.methods).containsExactly("tinkoff.public.invest.api.contract.v1.InstrumentsService/FindInstrument");
    }

    @Test void retriesTimeoutWithFiniteFreshDeadlinesAndSanitizedFailure() {
        transport.responses.addAll(List.of(Status.DEADLINE_EXCEEDED, Status.DEADLINE_EXCEEDED, Status.DEADLINE_EXCEEDED));
        assertFailure(MarketException.Reason.TIMEOUT);
        assertThat(transport.requests).hasSize(3);
        assertThat(transport.deadlines).hasSize(3).allSatisfy(d -> {
            assertThat(d).isNotNull();
            assertThat(d.timeRemaining(TimeUnit.MILLISECONDS)).isLessThanOrEqualTo(100);
        });
        assertThat(transport.deadlines.getFirst()).isNotSameAs(transport.deadlines.getLast());
    }

    @Test void transientOutageCanRecover() {
        transport.responses.add(Status.UNAVAILABLE);
        transport.responses.add(ShareResponse.newBuilder().setInstrument(Share.newBuilder().setUid(UID)).build());
        assertThat(provider.findByUid(UID)).isPresent();
        assertThat(transport.requests).hasSize(2);
    }

    @Test void authenticationNeverRetriesAndDiscardsSecretBearingTransportDetails() {
        transport.responses.add(Status.UNAUTHENTICATED.withDescription("synthetic-secret-in-transport"));
        assertFailure(MarketException.Reason.AUTHENTICATION);
        assertThat(transport.requests).hasSize(1);
    }

    @Test void rateLimitAppliesCooldownAndDoesNotImmediatelyRetry() {
        transport.trailers.put(Metadata.Key.of("x-ratelimit-reset", Metadata.ASCII_STRING_MARSHALLER), "30");
        transport.responses.add(Status.RESOURCE_EXHAUSTED);
        assertFailure(MarketException.Reason.RATE_LIMIT);
        assertFailure(MarketException.Reason.RATE_LIMIT);
        assertThat(transport.requests).hasSize(1);
    }

    @Test void unknownUidIsEmptyAndOtherErrorsStayDistinct() {
        transport.responses.add(Status.NOT_FOUND);
        assertThat(provider.findByUid(UID)).isEmpty();
        transport.responses.add(Status.INVALID_ARGUMENT);
        assertFailure(MarketException.Reason.INVALID_INPUT);
        transport.responses.add(Status.INTERNAL);
        assertFailure(MarketException.Reason.INTERNAL);
        assertThat(transport.requests).hasSize(3);
    }

    @Test void shutdownClosesChannel() throws Exception {
        provider.close();
        verify(managed).shutdownNow();
        verify(managed).awaitTermination(5, TimeUnit.SECONDS);
    }

    private void assertFailure(MarketException.Reason reason) {
        assertThatThrownBy(() -> provider.findByUid(UID)).isInstanceOfSatisfying(MarketException.class, e -> {
            assertThat(e.reason()).isEqualTo(reason);
            assertThat(e).hasNoCause().hasMessageNotContaining("synthetic-secret-in-transport");
        });
    }

    private InstrumentShort shortInstrument(String uid, String board, InstrumentType type) {
        return InstrumentShort.newBuilder().setUid(uid).setTicker("SBER").setClassCode(board).setInstrumentKind(type).build();
    }

    static class ScriptedChannel extends Channel {
        final Queue<Object> responses = new ArrayDeque<>();
        final List<Object> requests = new ArrayList<>();
        final List<String> methods = new ArrayList<>();
        final List<Deadline> deadlines = new ArrayList<>();
        final Metadata trailers = new Metadata();

        @Override public String authority() { return "in-memory-test"; }
        @Override public <ReqT, RespT> ClientCall<ReqT, RespT> newCall(MethodDescriptor<ReqT, RespT> method, CallOptions options) {
            methods.add(method.getFullMethodName());
            deadlines.add(options.getDeadline());
            return new ClientCall<>() {
                Listener<RespT> listener;
                @Override public void start(Listener<RespT> listener, Metadata headers) { this.listener = listener; }
                @Override public void request(int count) { }
                @Override public void cancel(String message, Throwable cause) { }
                @Override public void sendMessage(ReqT request) { requests.add(request); }
                @Override @SuppressWarnings("unchecked") public void halfClose() {
                    Object next = responses.remove();
                    if (next instanceof Status status) listener.onClose(status, trailers);
                    else {
                        listener.onMessage((RespT) next);
                        listener.onClose(Status.OK, new Metadata());
                    }
                }
            };
        }
    }
}
