package ru.ba6kir.tradelab.portfolio.domain.reconciliation;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;

public record ReconciliationResult(UUID id,UUID accountId,Instant checkedAt,Instant brokerCapturedAt,List<Row> rows,List<String> warnings) {
    public record Row(String instrumentUid,String currency,BigDecimal internalQuantity,BigDecimal brokerQuantity,BigDecimal difference,String status,String source) {}
    public ReconciliationResult{rows=List.copyOf(rows);warnings=List.copyOf(warnings);}
}
