package ru.ba6kir.tradelab.fundamentals.application.port;

import java.time.*;
import java.util.*;
import ru.ba6kir.tradelab.fundamentals.domain.model.*;

public interface FundamentalsStore {
    record RequestState(Instant lastSuccess,Instant lastFailure,String failureCode) { }
    record SaveResult(FundamentalSnapshot snapshot,boolean changed,int updatedMetrics) { }
    record ReportBatch(List<FinancialReportEvent> events,Instant fetchedAt,Instant from,Instant to) {
        public ReportBatch { events=List.copyOf(events); }
    }
    Optional<AssetIdentity> findIdentity(String instrumentUid);
    void saveIdentity(AssetIdentity identity);
    Optional<FundamentalSnapshot> latest(String assetUid);
    RequestState requestState(String assetUid);
    boolean tryStart(String assetUid,Instant attempt,Duration cooldown);
    SaveResult saveSnapshot(FundamentalSnapshot snapshot,Instant attempt);
    void failed(String assetUid,Instant attempt,Instant failedAt,String code);
    Optional<ReportBatch> reports(String instrumentUid);
    void saveReports(String instrumentUid,List<FinancialReportEvent> events,Instant fetchedAt,Instant from,Instant to);
}
