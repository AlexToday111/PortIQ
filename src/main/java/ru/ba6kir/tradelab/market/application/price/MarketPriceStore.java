package ru.ba6kir.tradelab.market.application.price;

import java.time.*;
import java.util.*;
import ru.ba6kir.tradelab.market.domain.price.*;

public interface MarketPriceStore {
    record SyncState(Instant coveredFrom, Instant coveredTo, Instant lastAttempt, Instant lastSuccess,
                     Instant lastFailure, String failureCode) { }
    record SyncStatus(Instant lastSuccess, Instant lastFailure, String failureCode) { }
    Optional<MarketQuote> findQuote(String uid);
    void saveQuote(MarketQuote quote);
    List<DailyCandle> findCandles(String uid, LocalDate from, LocalDate to);
    void saveCandles(List<DailyCandle> candles);
    Optional<Instant> earliestIncomplete(String uid, Instant from);
    Optional<SyncState> syncState(String uid);
    boolean tryStart(String uid, Instant attempt, Duration cooldown);
    void coverHistory(String uid, Instant from, Instant to);
    void finish(String uid, Instant attempt, boolean success, String failureCode);
    SyncStatus status();
}
