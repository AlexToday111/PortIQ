package ru.ba6kir.tradelab.fundamentals;

import java.time.*;
import java.util.*;
import com.google.protobuf.Timestamp;
import io.grpc.*;
import org.junit.jupiter.api.Test;
import ru.tinkoff.piapi.contract.v1.*;
import ru.ba6kir.tradelab.market.application.MarketException;
import ru.ba6kir.tradelab.market.infrastructure.tinvest.*;
import ru.ba6kir.tradelab.fundamentals.infrastructure.tinvest.TInvestFundamentalsAdapter;
import ru.ba6kir.tradelab.fundamentals.domain.model.*;
import ru.ba6kir.tradelab.fundamentals.domain.validation.FundamentalNormalizer;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static ru.ba6kir.tradelab.fundamentals.FundamentalsTestData.*;

class TInvestFundamentalsAdapterTest {
    private final ScriptedChannel channel=new ScriptedChannel();
    private final TInvestStockInstrumentProvider client=new TInvestStockInstrumentProvider(InstrumentsServiceGrpc.newBlockingStub(channel),
            mock(ManagedChannel.class),new InvestProperties(true,"synthetic",Duration.ofDays(1),Duration.ofMillis(100),3,Duration.ofMillis(1)),CLOCK);
    private final TInvestFundamentalsAdapter adapter=new TInvestFundamentalsAdapter(client,CLOCK);
    private AssetResponse asset(String instrument) {
        return AssetResponse.newBuilder().setAsset(AssetFull.newBuilder().setUid(ASSET).setName("Asset")
                .setType(AssetType.ASSET_TYPE_SECURITY).setSecurity(AssetSecurity.newBuilder().setInstrumentKind(InstrumentType.INSTRUMENT_TYPE_SHARE)
                        .setShare(AssetShare.getDefaultInstance())).addInstruments(AssetInstrument.newBuilder().setUid(instrument))).build();
    }
    @Test void resolvesShareAssetAndChecksReverseMembership() {
        channel.responses.add(ShareResponse.newBuilder().setInstrument(Share.newBuilder().setUid(UID).setAssetUid(ASSET)).build());
        channel.responses.add(asset(UID));
        assertThat(adapter.resolve(UID).orElseThrow().assetUid()).isEqualTo(ASSET);
        assertThat(channel.requests.get(0)).isInstanceOfSatisfying(InstrumentRequest.class,r -> assertThat(r.getId()).isEqualTo(UID));
        assertThat(channel.requests.get(1)).isInstanceOfSatisfying(AssetRequest.class,r -> assertThat(r.getId()).isEqualTo(ASSET));
    }
    @Test void missingOrUnverifiedMappingsAreNotGuessedFromTickerOrBrand() {
        channel.responses.add(ShareResponse.newBuilder().setInstrument(Share.newBuilder().setUid(UID)).build());
        assertThat(adapter.resolve(UID)).isEmpty();
        channel.responses.add(ShareResponse.newBuilder().setInstrument(Share.newBuilder().setUid(UID).setAssetUid(ASSET)).build());
        channel.responses.add(asset(UID2));
        assertThat(adapter.resolve(UID)).isEmpty();
    }
    @Test void requestsFundamentalsByAssetAndPreservesImplicitZeroAndFinancialCurrency() {
        channel.responses.add(GetAssetFundamentalsResponse.newBuilder().addFundamentals(GetAssetFundamentalsResponse.StatisticResponse.newBuilder()
                .setAssetUid(ASSET).setCurrency("USD").setPeRatioTtm(5.25).setNetIncomeTtm(-10.5).setFreeCashFlowTtm(-2.5).setRoe(0.25)).build());
        var raw=adapter.fetch(ASSET).orElseThrow();
        assertThat(channel.requests.getFirst()).isInstanceOfSatisfying(GetAssetFundamentalsRequest.class,r -> assertThat(r.getAssetsList()).containsExactly(ASSET));
        assertThat(raw.currency()).isEqualTo("USD"); assertThat(raw.providerAsOf()).isNull();
        assertThat(raw.values().get(MetricCode.TOTAL_DEBT).presenceKnown()).isFalse();
        var normalized=new FundamentalNormalizer().normalize(raw);
        assertThat(normalized.metrics()).anySatisfy(m -> {
            assertThat(m.code()).isEqualTo(MetricCode.TOTAL_DEBT); assertThat(m.quality()).isEqualTo(QualityStatus.SUSPECT);
        });
    }
    @Test void emptyResponseAndNonFiniteRawNumbersRemainExplicit() {
        channel.responses.add(GetAssetFundamentalsResponse.getDefaultInstance());
        assertThat(adapter.fetch(ASSET)).isEmpty();
        channel.responses.add(GetAssetFundamentalsResponse.newBuilder().addFundamentals(GetAssetFundamentalsResponse.StatisticResponse.newBuilder()
                .setAssetUid(ASSET).setPeRatioTtm(Double.NaN)).build());
        assertThat(adapter.fetch(ASSET).orElseThrow().values().get(MetricCode.PE).text()).isEqualTo("NaN");
    }
    @Test void reportRequestUsesInstrumentUidAndRetainsCalendarFieldsWithoutCreatingStatementData() {
        var date=Timestamp.newBuilder().setSeconds(NOW.plusSeconds(86400).getEpochSecond());
        channel.responses.add(GetAssetReportsResponse.newBuilder().addEvents(GetAssetReportsResponse.GetAssetReportsEvent.newBuilder()
                .setInstrumentId(UID).setReportDate(date).setPeriodYear(2026).setPeriodNum(3)
                .setPeriodType(GetAssetReportsResponse.AssetReportPeriodType.PERIOD_TYPE_QUARTER)).build());
        var events=adapter.fetchReports(UID,NOW.minusSeconds(1),NOW.plusSeconds(172800));
        assertThat(channel.requests.getFirst()).isInstanceOfSatisfying(GetAssetReportsRequest.class,r -> assertThat(r.getInstrumentId()).isEqualTo(UID));
        assertThat(events.getFirst().reportDate()).isEqualTo(NOW.plusSeconds(86400));
        assertThat(events.getFirst().reportingYear()).isEqualTo(2026); assertThat(events.getFirst().periodNumber()).isEqualTo(3);
        assertThat(events.getFirst().fetchedAt()).isEqualTo(NOW);
    }
    @Test void authenticationDoesNotRetryAndRateLimitIsSharedAcrossAdapters() {
        channel.responses.add(Status.UNAUTHENTICATED.withDescription("secret"));
        assertThatThrownBy(() -> adapter.fetch(ASSET)).isInstanceOfSatisfying(MarketException.class,e -> {
            assertThat(e.reason()).isEqualTo(MarketException.Reason.AUTHENTICATION); assertThat(e).hasNoCause();
        });
        assertThat(channel.requests).hasSize(1);
        channel.responses.add(Status.RESOURCE_EXHAUSTED);
        assertThatThrownBy(() -> adapter.fetch(ASSET)).isInstanceOf(MarketException.class);
        assertThatThrownBy(() -> client.findShares("SBER")).isInstanceOf(MarketException.class);
        assertThat(channel.requests).hasSize(2);
    }
    private static class ScriptedChannel extends Channel {
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
