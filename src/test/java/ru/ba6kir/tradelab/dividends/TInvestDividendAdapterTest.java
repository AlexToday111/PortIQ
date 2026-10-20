package ru.ba6kir.tradelab.dividends;

import java.time.*;
import java.util.*;
import io.grpc.*;
import org.junit.jupiter.api.Test;
import ru.tinkoff.piapi.contract.v1.*;
import ru.ba6kir.tradelab.market.application.MarketException;
import ru.ba6kir.tradelab.market.infrastructure.tinvest.*;
import ru.ba6kir.tradelab.dividends.infrastructure.tinvest.TInvestDividendAdapter;
import ru.ba6kir.tradelab.dividends.domain.model.DividendEvent;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static ru.ba6kir.tradelab.dividends.DividendTestData.*;

class TInvestDividendAdapterTest {
    final ScriptedChannel channel=new ScriptedChannel();
    final TInvestStockInstrumentProvider client=new TInvestStockInstrumentProvider(InstrumentsServiceGrpc.newBlockingStub(channel),mock(ManagedChannel.class),
            new InvestProperties(true,"synthetic",Duration.ofDays(1),Duration.ofMillis(100),3,Duration.ofMillis(1)),CLOCK);
    final TInvestDividendAdapter adapter=new TInvestDividendAdapter(client,CLOCK);
    @Test void usesInstrumentUidAndExactUtcRequestBoundsWithPresenceAwareFields() {
        channel.responses.add(GetDividendsResponse.newBuilder().addDividends(raw()).build());
        var result=adapter.fetchDividends(UID,NOW.minusSeconds(86400),NOW.plusSeconds(86400)).getFirst();
        assertThat(channel.requests.getFirst()).isInstanceOfSatisfying(GetDividendsRequest.class,r -> {
            assertThat(r.getInstrumentId()).isEqualTo(UID); assertThat(r.getFigi()).isEmpty();
            assertThat(r.getFrom().getSeconds()).isEqualTo(NOW.minusSeconds(86400).getEpochSecond());
            assertThat(r.getTo().getSeconds()).isEqualTo(NOW.plusSeconds(86400).getEpochSecond());
        });
        assertThat(result.amountPerShare()).isEqualByComparingTo("10.5"); assertThat(result.currency()).isEqualTo("RUB");
        assertThat(result.providerCreatedAt().getNano()).isEqualTo(123456789); assertThat(result.retrievedAt()).isEqualTo(NOW);
        assertThat(result.rawPayload()).isNotBlank();
    }
    @Test void preservesProviderYieldAndUntimedReferencePriceWithoutInventingAnExDate() {
        var e=event(raw().setYieldValue(Quotation.newBuilder().setUnits(2).setNano(500000000))
                .setClosePrice(MoneyValue.newBuilder().setCurrency("usd").setUnits(100)));
        assertThat(e.providerYield()).isEqualByComparingTo("2.5"); assertThat(e.providerReferencePrice()).isEqualByComparingTo("100");
        assertThat(e.referenceCurrency()).isEqualTo("USD"); assertThat(e.issues()).contains("REFERENCE_CURRENCY_MISMATCH");
    }
    @Test void malformedDecimalsAndMissingDatesRemainExplicitQualityDiagnostics() {
        var e=event(raw().setDividendNet(MoneyValue.newBuilder().setCurrency("?").setUnits(1).setNano(-1)).clearPaymentDate().clearRecordDate());
        assertThat(e.amountPerShare()).isNull(); assertThat(e.currency()).isNull();
        assertThat(e.issues()).contains("SUSPECT_AMOUNT","MISSING_CURRENCY","MISSING_RECORD_DATE","MISSING_PAYMENT_DATE");
        assertThat(e.status(TODAY)).isEqualTo(DividendEvent.Status.UNKNOWN);
    }
    @Test void futureDeclaredRecordIsAnnouncementWhileMissingDeclarationRemainsUnknown() {
        var d=raw().setRecordDate(day(TODAY.plusDays(5))).setPaymentDate(day(TODAY.plusDays(10)));
        assertThat(event(d).status(TODAY)).isEqualTo(DividendEvent.Status.ANNOUNCED);
        assertThat(event(d.clearDeclaredDate()).status(TODAY)).isEqualTo(DividendEvent.Status.UNKNOWN);
    }
    @Test void fullNanosecondCreationIdentityAndContentAreStableAcrossRetrievals() {
        var first=event(); var later=TInvestDividendAdapter.normalize(UID,raw().build(),NOW.plusSeconds(100));
        assertThat(first.contentHash()).isEqualTo(later.contentHash()); assertThat(first.identityAnchor()).isEqualTo(later.identityAnchor());
        var revised=event(raw().setDividendNet(MoneyValue.newBuilder().setCurrency("rub").setUnits(20)));
        assertThat(first.contentHash()).isNotEqualTo(revised.contentHash()); assertThat(first.identityAnchor()).isEqualTo(revised.identityAnchor());
        assertThat(event(raw().setCreatedAt(stamp("2026-09-30T12:00:00.123456788Z"))).identityAnchor()).isNotEqualTo(first.identityAnchor());
    }
    @Test void emptyResponseIsEmptyObservationSet() {
        channel.responses.add(GetDividendsResponse.getDefaultInstance());
        assertThat(adapter.fetchDividends(UID,NOW.minusSeconds(1),NOW.plusSeconds(1))).isEmpty();
    }
    @Test void transientFailureRetriesButAuthenticationAndRateLimitDoNotLeakOrRetry() {
        channel.responses.add(Status.UNAVAILABLE); channel.responses.add(GetDividendsResponse.getDefaultInstance());
        adapter.fetchDividends(UID,NOW.minusSeconds(1),NOW.plusSeconds(1)); assertThat(channel.requests).hasSize(2);
        channel.responses.add(Status.UNAUTHENTICATED.withDescription("private-token"));
        assertThatThrownBy(() -> adapter.fetchDividends(UID,NOW.minusSeconds(1),NOW.plusSeconds(1)))
                .isInstanceOf(MarketException.class).hasNoCause().hasMessageNotContaining("private-token");
        channel.responses.add(Status.RESOURCE_EXHAUSTED);
        assertThatThrownBy(() -> adapter.fetchDividends(UID,NOW.minusSeconds(1),NOW.plusSeconds(1))).isInstanceOf(MarketException.class);
        assertThatThrownBy(() -> client.findShares("SBER")).isInstanceOf(MarketException.class);
        assertThat(channel.requests).hasSize(4);
    }
    @Test void rejectsInvalidRequestBeforeRpc() {
        assertThatThrownBy(() -> adapter.fetchDividends("SBER",NOW,NOW.plusSeconds(1))).isInstanceOf(MarketException.class);
        assertThatThrownBy(() -> adapter.fetchDividends(UID,NOW,NOW)).isInstanceOf(IllegalArgumentException.class);
        assertThat(channel.requests).isEmpty();
    }
    static class ScriptedChannel extends Channel {
        final Queue<Object> responses=new ArrayDeque<>(); final List<Object> requests=new ArrayList<>();
        @Override public String authority() { return "in-memory"; }
        @Override public <Req,Resp> ClientCall<Req,Resp> newCall(MethodDescriptor<Req,Resp> method,CallOptions options) {
            assertThat(options.getDeadline()).isNotNull();
            return new ClientCall<>() {
                Listener<Resp> listener;
                public void start(Listener<Resp> value,Metadata headers) { listener=value; }
                public void request(int n) { }
                public void cancel(String message,Throwable cause) { }
                public void sendMessage(Req request) { requests.add(request); }
                @SuppressWarnings("unchecked") public void halfClose() {
                    Object result=responses.remove();
                    if (result instanceof Status status) listener.onClose(status,new Metadata());
                    else { listener.onMessage((Resp)result); listener.onClose(Status.OK,new Metadata()); }
                }
            };
        }
    }
}
