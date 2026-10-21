package ru.ba6kir.tradelab.analysis.ranking.application;

import java.time.*;
import java.util.Optional;
import ru.ba6kir.tradelab.analysis.ranking.domain.RankingRun;

public interface RankingStore {
    record State(Instant lastAttempt,Instant leaseUntil,Instant lastSuccess,String lastFailure) {}
    boolean tryStart(Instant token,Duration cooldown);
    RankingRun save(RankingRun run,Instant token);
    void fail(Instant token,String reason);
    Optional<RankingRun> latest();
    State state();
}
