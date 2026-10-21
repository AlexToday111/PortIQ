package ru.ba6kir.tradelab.portfolio.application.port;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;

/** Read-only broker contract. External identifiers and raw payloads must never enter routine logs/replies. */
public interface BrokerPortfolioProvider {
    record BrokerAccount(String externalId,String name,String accountType,String status,boolean readOnly,Instant openedAt) {
        @Override public String toString(){return "BrokerAccount[externalId=[REDACTED],readOnly="+readOnly+"]";}
    }
    record Execution(String tradeId,Instant executedAt,BigDecimal quantity,BigDecimal price,String currency) {}
    record Operation(String externalId,String parentId,String type,String state,String instrumentUid,String instrumentType,Instant executedAt,
            BigDecimal payment,String currency,BigDecimal price,BigDecimal commission,BigDecimal quantityDone,List<Execution> executions,String rawSource) {
        public Operation {executions=List.copyOf(executions);}
        @Override public String toString(){return "BrokerOperation[identifiers=[REDACTED],type="+type+",state="+state+"]";}
    }
    record Page(List<Operation> operations,boolean hasNext,String nextCursor) {public Page{operations=List.copyOf(operations);}}
    record Holding(String instrumentUid,String instrumentType,String currency,BigDecimal quantity,BigDecimal blockedQuantity,BigDecimal settledFreeQuantity,String source) {}
    record Snapshot(Instant capturedAt,List<Holding> holdings,Map<String,BigDecimal> cash,Map<String,BigDecimal> blockedCash,
            boolean limitsLoading,String rawSource) {
        public Snapshot{holdings=List.copyOf(holdings);cash=Map.copyOf(cash);blockedCash=Map.copyOf(blockedCash);}
    }
    List<BrokerAccount> accounts();
    Snapshot snapshot(String externalAccountId);
    Page operations(String externalAccountId,Instant from,Instant to,String cursor,int limit);
}
