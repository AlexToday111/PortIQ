package ru.ba6kir.tradelab.portfolio.domain.model;

import java.time.Instant;
import java.util.UUID;

public record PortfolioAccount(UUID id,UUID portfolioId,String name,AccountType accountType,Source source,String broker,
        String externalAccountId,boolean synchronizationEnabled,Instant historyFrom,Instant brokerOpenedAt,Instant checkpoint,
        Instant lastSuccessfulSyncAt,Instant createdAt,Instant updatedAt) {
    public enum AccountType { MANUAL,BROKERAGE,IIS }
    public enum Source { MANUAL,T_INVEST }
    @Override public String toString() { return "PortfolioAccount[id="+id+",source="+source+",externalAccountId=[REDACTED]]"; }
}
