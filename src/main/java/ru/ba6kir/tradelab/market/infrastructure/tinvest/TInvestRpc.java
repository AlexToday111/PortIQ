package ru.ba6kir.tradelab.market.infrastructure.tinvest;

import java.time.Clock;
import java.util.function.Supplier;
import io.grpc.*;
import org.slf4j.*;
import ru.ba6kir.tradelab.market.application.MarketException;

/** Shared retry/cooldown policy for instrument and price unary requests on one SDK channel. */
public class TInvestRpc {
    private static final Logger log = LoggerFactory.getLogger(TInvestRpc.class);
    private final InvestProperties properties;
    private final Clock clock;
    private volatile long rateLimitedUntil;
    public TInvestRpc(InvestProperties properties, Clock clock) { this.properties = properties; this.clock = clock; }
    public <T> T call(Supplier<T> operation) {
        if (clock.millis() < rateLimitedUntil) throw new MarketException(MarketException.Reason.RATE_LIMIT);
        for (int attempt = 1; ; attempt++) {
            try { return operation.get(); }
            catch (StatusRuntimeException e) {
                var reason = reason(e.getStatus().getCode());
                log.warn("T-Invest request failed; reason={}, attempt={}", reason, attempt);
                if (reason == MarketException.Reason.RATE_LIMIT) {
                    long seconds = 60;
                    if (e.getTrailers() != null) {
                        String reset = e.getTrailers().get(Metadata.Key.of("x-ratelimit-reset", Metadata.ASCII_STRING_MARSHALLER));
                        try { if (reset != null) seconds = Math.clamp(Long.parseLong(reset), 1, 3600); }
                        catch (NumberFormatException ignored) { }
                    }
                    rateLimitedUntil = clock.millis() + seconds * 1000;
                }
                boolean retry = reason == MarketException.Reason.TIMEOUT || reason == MarketException.Reason.UNAVAILABLE;
                if (!retry || attempt >= properties.maxAttempts()) throw new MarketException(reason);
                try { Thread.sleep(properties.retryBackoff().toMillis() * (1L << (attempt - 1))); }
                catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                    throw new MarketException(MarketException.Reason.UNAVAILABLE);
                }
            }
        }
    }
    static MarketException.Reason reason(Status.Code code) {
        return switch (code) {
            case NOT_FOUND -> MarketException.Reason.NOT_FOUND;
            case UNAUTHENTICATED, PERMISSION_DENIED -> MarketException.Reason.AUTHENTICATION;
            case RESOURCE_EXHAUSTED -> MarketException.Reason.RATE_LIMIT;
            case DEADLINE_EXCEEDED -> MarketException.Reason.TIMEOUT;
            case UNAVAILABLE, ABORTED -> MarketException.Reason.UNAVAILABLE;
            case INVALID_ARGUMENT -> MarketException.Reason.INVALID_INPUT;
            default -> MarketException.Reason.INTERNAL;
        };
    }
}
