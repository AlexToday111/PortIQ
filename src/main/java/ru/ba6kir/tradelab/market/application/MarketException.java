package ru.ba6kir.tradelab.market.application;

/** Deliberately discards transport messages, trailers and causes that may contain secrets. */
public class MarketException extends RuntimeException {
    public enum Reason { DISABLED, INVALID_INPUT, NOT_FOUND, AUTHENTICATION, RATE_LIMIT, TIMEOUT, UNAVAILABLE, INTERNAL }
    private final Reason reason;

    public MarketException(Reason reason) {
        super("Market metadata failure: " + reason);
        this.reason = reason;
    }

    public Reason reason() { return reason; }
}
