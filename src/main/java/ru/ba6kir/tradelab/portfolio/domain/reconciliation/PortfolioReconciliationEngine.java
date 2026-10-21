package ru.ba6kir.tradelab.portfolio.domain.reconciliation;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import ru.ba6kir.tradelab.portfolio.domain.accounting.AccountingResult;
import ru.ba6kir.tradelab.portfolio.application.port.BrokerPortfolioProvider.Snapshot;

public class PortfolioReconciliationEngine {
    public ReconciliationResult reconcile(AccountingResult internal,Snapshot broker,boolean historyComplete,Instant at) {
        var rows=new ArrayList<ReconciliationResult.Row>();var warnings=new ArrayList<String>();
        if(!historyComplete) warnings.add("INCOMPLETE_HISTORY");if(broker.limitsLoading()) warnings.add("BROKER_LIMITS_LOADING");
        var known=new HashSet<String>();
        for(var b:broker.holdings()) {
            if(!known.add(String.valueOf(b.instrumentUid()))) {warnings.add("DUPLICATE_BROKER_INSTRUMENT");continue;}
            var p=internal.positions().stream().filter(i -> Objects.equals(i.instrumentUid(),b.instrumentUid())).findFirst().orElse(null);
            var iq=p==null?BigDecimal.ZERO:p.quantity();var bq=b.quantity();
            boolean supported="share".equals(b.instrumentType()) && "RUB".equals(b.currency()) && bq!=null && bq.signum()>=0;
            String status=!supported?"UNSUPPORTED_INSTRUMENT":bq==null || iq==null?"INCOMPLETE_HISTORY":p==null && bq.signum()!=0?"MISSING_INTERNAL_POSITION":iq.compareTo(bq)==0?"MATCHED":"QUANTITY_MISMATCH";
            rows.add(new ReconciliationResult.Row(b.instrumentUid(),b.currency(),iq,bq,iq==null || bq==null?null:iq.subtract(bq),status,b.source()));
            if(b.blockedQuantity()!=null && b.blockedQuantity().signum()!=0) warnings.add("BLOCKED_OR_SETTLEMENT_QUANTITY:"+b.instrumentUid());
            if(b.settledFreeQuantity()!=null && b.blockedQuantity()!=null && bq!=null && b.settledFreeQuantity().add(b.blockedQuantity()).compareTo(bq)!=0)
                warnings.add("POSITION_ENDPOINT_TIMING_OR_DEFINITION_MISMATCH:"+b.instrumentUid());
        }
        for(var p:internal.positions()) if(!known.contains(p.instrumentUid()) && (p.quantity()==null || p.quantity().signum()!=0))
            rows.add(new ReconciliationResult.Row(p.instrumentUid(),p.currency(),p.quantity(),BigDecimal.ZERO,p.quantity(),"MISSING_BROKER_POSITION","GET_PORTFOLIO"));
        for(var cash:broker.cash().entrySet()) {
            String cur=cash.getKey();var amount=internal.cash().getOrDefault(cur,BigDecimal.ZERO);boolean compatible="RUB".equals(cur)
                    && broker.blockedCash().getOrDefault(cur,BigDecimal.ZERO).signum()==0 && !broker.limitsLoading();
            rows.add(new ReconciliationResult.Row(null,cur,amount,cash.getValue(),compatible?amount.subtract(cash.getValue()):null,
                    !compatible?"CASH_DEFINITION_OR_CURRENCY_UNSUPPORTED":amount.compareTo(cash.getValue())==0?"MATCHED":"CASH_MISMATCH","GET_POSITIONS_MONEY; zero blocked only; settlement may differ"));
        }
        if(broker.capturedAt()==null || broker.capturedAt().isAfter(at) || !broker.capturedAt().plus(java.time.Duration.ofDays(1)).isAfter(at))warnings.add("STALE_OR_FUTURE_BROKER_SNAPSHOT");
        return new ReconciliationResult(UUID.randomUUID(),internal.accountId(),at,broker.capturedAt(),rows,warnings.stream().distinct().sorted().toList());
    }
}
