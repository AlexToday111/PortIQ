package ru.ba6kir.tradelab.market.application.price;

public class PriceException extends RuntimeException {
    public enum Reason { INVALID_DATA, NO_PRICE, EMPTY_HISTORY, COOLDOWN }
    private final Reason reason;
    public PriceException(Reason reason) { super("Market price failure: " + reason); this.reason = reason; }
    public Reason reason() { return reason; }
}
