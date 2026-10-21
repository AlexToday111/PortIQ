package ru.ba6kir.tradelab.portfolio.application.port;

import java.time.*;
import java.util.*;
import java.util.function.Function;
import ru.ba6kir.tradelab.portfolio.domain.model.*;
import ru.ba6kir.tradelab.portfolio.domain.accounting.AccountingResult;
import ru.ba6kir.tradelab.portfolio.domain.reconciliation.ReconciliationResult;

public interface PortfolioStore {
    record Selection(Portfolio portfolio,PortfolioAccount account) {}
    record Pending(UUID token,String requestKey,String payloadHash,LedgerTransaction transaction,Instant expiresAt,UUID confirmedTransactionId) {}
    record SourceOperation(UUID id,String externalId,String identityKey,String fingerprint,BrokerPortfolioProvider.Operation operation,String mappingStatus,String reason) {}
    record SyncState(UUID runId,Instant lastAttempt,Instant leaseUntil,Instant checkpoint,Instant lastSuccess,String lastError) {}
    record SyncReport(UUID id,String status,int created,int unchanged,int corrected,int unsupported,Instant from,Instant to,Instant completedAt,ReconciliationResult reconciliation) {}
    interface LockedAccount {
        PortfolioAccount account();
        List<LedgerTransaction> transactions();
        LedgerTransaction append(LedgerTransaction transaction);
        void projection(AccountingResult result);
        Pending pending(String requestKey);
        Pending pending(UUID token);
        Pending pending(Pending request);
        void confirm(UUID token,UUID transactionId);
        List<SourceOperation> sources();
        void source(SourceOperation operation,Instant observedAt);
        void completeSync(Instant token,SyncReport report,BrokerPortfolioProvider.Snapshot snapshot);
        void reconciliation(ReconciliationResult result);
    }
    Optional<Selection> selected();
    Selection createPortfolio(String name,String creationKey,Instant at);
    List<Portfolio> portfolios();
    void selectPortfolio(UUID id);
    List<PortfolioAccount> accounts(UUID portfolioId);
    PortfolioAccount account(UUID id);
    PortfolioAccount createAccount(UUID portfolioId,String name,PortfolioAccount.AccountType type,String creationKey,Instant at);
    void selectAccount(UUID id);
    List<UUID> discover(List<BrokerPortfolioProvider.BrokerAccount> accounts,Instant at);
    List<Map<String,Object>> discovered();
    PortfolioAccount link(UUID portfolioId,UUID candidate,Instant historyFrom,String name,boolean distinctManualAccountsReviewed,Instant at);
    <T> T locked(UUID accountId,Function<LockedAccount,T> work);
    <T> T readConsistently(java.util.function.Supplier<T> work);
    List<LedgerTransaction> transactions(UUID accountId);
    Optional<Pending> pendingByRequest(String requestKey);
    Optional<Pending> pendingByToken(UUID token);
    Optional<SyncState> startSync(UUID accountId,Instant token,Duration cooldown);
    void failSync(UUID accountId,Instant token,String code);
    SyncState syncState(UUID accountId);
    Optional<SyncReport> lastSync(UUID accountId);
    Optional<BrokerPortfolioProvider.Snapshot> brokerSnapshot(UUID accountId);
    Optional<ReconciliationResult> reconciliation(UUID accountId);
}
