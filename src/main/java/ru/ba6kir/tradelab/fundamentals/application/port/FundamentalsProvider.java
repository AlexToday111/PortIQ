package ru.ba6kir.tradelab.fundamentals.application.port;

import java.time.Instant;
import java.util.*;
import ru.ba6kir.tradelab.fundamentals.domain.model.*;

public interface FundamentalsProvider {
    Optional<FundamentalData> fetch(String assetUid);
    List<FinancialReportEvent> fetchReports(String instrumentUid,Instant from,Instant to);
}
