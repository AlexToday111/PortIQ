package ru.ba6kir.tradelab.portfolio.domain.accounting;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;

public record AccountingResult(UUID accountId,List<Position> positions,List<Lot> lots,List<Disposal> disposals,Map<String,BigDecimal> cash,
        Map<String,BigDecimal> realizedPnL,Map<String,BigDecimal> dividends,Map<String,BigDecimal> fees,Map<String,BigDecimal> taxes,
        List<String> issues,Instant calculatedAt) {
    public record Position(String instrumentUid,String currency,BigDecimal quantity,BigDecimal remainingCost,BigDecimal averageCost,String costQuality) {}
    public record Lot(UUID transactionId,String instrumentUid,String currency,BigDecimal initialQuantity,BigDecimal remainingQuantity,
            BigDecimal originalCost,BigDecimal remainingCost,Instant acquiredAt) {}
    public record Disposal(UUID saleTransactionId,UUID acquisitionTransactionId,BigDecimal quantity,BigDecimal allocatedCost,BigDecimal allocatedSaleCommission) {}
    public AccountingResult {
        positions=List.copyOf(positions);lots=List.copyOf(lots);disposals=List.copyOf(disposals);cash=Map.copyOf(cash);
        realizedPnL=Collections.unmodifiableMap(new TreeMap<>(realizedPnL));dividends=Map.copyOf(dividends);fees=Map.copyOf(fees);taxes=Map.copyOf(taxes);issues=List.copyOf(issues);
    }
}
