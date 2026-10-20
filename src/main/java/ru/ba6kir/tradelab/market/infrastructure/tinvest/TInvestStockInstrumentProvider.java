package ru.ba6kir.tradelab.market.infrastructure.tinvest;

import java.time.Clock;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import io.grpc.ManagedChannel;
import ru.ba6kir.tradelab.market.application.MarketException;
import ru.ba6kir.tradelab.market.application.StockInstrumentProvider;
import ru.ba6kir.tradelab.market.domain.StockInstrument;
import ru.tinkoff.piapi.contract.v1.*;

public class TInvestStockInstrumentProvider implements StockInstrumentProvider, AutoCloseable {
    public static final String SOURCE = "T_INVEST_PRODUCTION";
    private final InstrumentsServiceGrpc.InstrumentsServiceBlockingStub stub;
    private final ManagedChannel channel;
    private final InvestProperties properties;
    private final Clock clock;
    private final TInvestRpc rpc;

    public TInvestStockInstrumentProvider(InstrumentsServiceGrpc.InstrumentsServiceBlockingStub stub,
            ManagedChannel channel, InvestProperties properties, Clock clock) {
        this.stub = stub;
        this.channel = channel;
        this.properties = properties;
        this.clock = clock;
        this.rpc = new TInvestRpc(properties, clock);
    }

    @Override
    public List<StockInstrument> findShares(String query) {
        var request = FindInstrumentRequest.newBuilder().setQuery(query)
                .setInstrumentKind(InstrumentType.INSTRUMENT_TYPE_SHARE).build();
        // One indexed API search; no Shares catalog download and no per-result fan-out.
        // FindInstrument does not include all ShareBy fields. Unknown attributes stay null.
        return call(s -> s.findInstrument(request)).getInstrumentsList().stream()
                .filter(s -> s.getInstrumentKind() == InstrumentType.INSTRUMENT_TYPE_SHARE)
                .filter(s -> !s.getUid().isBlank())
                .map(s -> new StockInstrument(s.getUid(), optional(s.getTicker()), optional(s.getClassCode()),
                        optional(s.getName()), optional(s.getFigi()), optional(s.getIsin()), null, null, null,
                        null, s.getApiTradeAvailableFlag(), clock.instant(), SOURCE))
                .distinct()
                // Prefer MOEX equity boards in presentation; keep all matches to preserve ambiguity.
                .sorted(Comparator.comparing((StockInstrument s) -> !isMoexBoard(s.classCode()))
                        .thenComparing(s -> s.ticker() == null ? "" : s.ticker()).thenComparing(StockInstrument::uid))
                .toList();
    }

    @Override
    public Optional<StockInstrument> findByUid(String uid) {
        var request = InstrumentRequest.newBuilder().setIdType(InstrumentIdType.INSTRUMENT_ID_TYPE_UID).setId(uid).build();
        try {
            Share s = call(client -> client.shareBy(request)).getInstrument();
            if (s.getUid().isBlank()) throw new MarketException(MarketException.Reason.UNAVAILABLE);
            if (!uid.equalsIgnoreCase(s.getUid())) throw new MarketException(MarketException.Reason.INTERNAL);
            return Optional.of(new StockInstrument(s.getUid(), optional(s.getTicker()), optional(s.getClassCode()),
                    optional(s.getName()), optional(s.getFigi()), optional(s.getIsin()), optional(s.getCurrency()),
                    optional(s.getExchange()), s.getLot() > 0 ? s.getLot() : null,
                    s.getTradingStatus() == SecurityTradingStatus.SECURITY_TRADING_STATUS_UNSPECIFIED
                            ? null : s.getTradingStatus().name(),
                    s.getApiTradeAvailableFlag(), clock.instant(), SOURCE));
        } catch (MarketException e) {
            if (e.reason() == MarketException.Reason.NOT_FOUND) return Optional.empty();
            throw e;
        }
    }

    // Infrastructure-only gateway reused by other T-Invest adapters; SDK types stay out of application ports.
    public <T> T call(Function<InstrumentsServiceGrpc.InstrumentsServiceBlockingStub, T> operation) {
        return rpc.call(() -> operation.apply(stub.withDeadlineAfter(properties.timeout().toMillis(), TimeUnit.MILLISECONDS)));
    }

    MarketDataServiceGrpc.MarketDataServiceBlockingStub marketDataStub() {
        return MarketDataServiceGrpc.newBlockingStub(channel);
    }

    TInvestRpc rpc() { return rpc; }
    private static String optional(String value) { return value.isBlank() ? null : value; }
    private static boolean isMoexBoard(String code) { return code != null && code.startsWith("TQ"); }

    @Override
    public void close() {
        channel.shutdownNow();
        try { channel.awaitTermination(5, TimeUnit.SECONDS); }
        catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
    }
}
