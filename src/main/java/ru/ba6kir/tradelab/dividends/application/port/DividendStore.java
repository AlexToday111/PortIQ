package ru.ba6kir.tradelab.dividends.application.port;

import java.time.*;
import java.util.List;
import ru.ba6kir.tradelab.dividends.domain.model.DividendEvent;
import ru.ba6kir.tradelab.dividends.domain.model.DividendEvent.Observation;

public interface DividendStore {
    record State(LocalDate from, LocalDate to, Instant lastSuccess, Instant lastFailure, String failureCode) {}
    record Counts(int added, int updated, int cancellations, int unchanged, int ambiguous) {}
    List<Observation> find(String uid);
    State state(String uid);
    boolean tryStart(String uid, Instant attempt, Duration cooldown);
    Counts save(String uid, List<DividendEvent> events, LocalDate from, LocalDate to, Instant attempt);
    void fail(String uid, Instant attempt, String code);
}
