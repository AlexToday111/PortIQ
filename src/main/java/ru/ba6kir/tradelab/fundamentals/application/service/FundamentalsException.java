package ru.ba6kir.tradelab.fundamentals.application.service;

public class FundamentalsException extends RuntimeException {
    public enum Reason { MAPPING_UNAVAILABLE, EMPTY_RESPONSE, INVALID_DATA, INTERNAL }
    private final Reason reason;
    public FundamentalsException(Reason reason) { super("Fundamentals failure: "+reason); this.reason=reason; }
    public Reason reason() { return reason; }
}
