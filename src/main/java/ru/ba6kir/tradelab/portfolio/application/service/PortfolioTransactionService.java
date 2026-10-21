package ru.ba6kir.tradelab.portfolio.application.service;

import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import ru.ba6kir.tradelab.portfolio.application.port.PortfolioStore;
import ru.ba6kir.tradelab.portfolio.domain.accounting.*;
import ru.ba6kir.tradelab.portfolio.domain.model.*;
import ru.ba6kir.tradelab.market.application.InstrumentService;

public class PortfolioTransactionService {
    public record Command(LedgerTransaction.Type type,String instrument,BigDecimal quantity,BigDecimal price,BigDecimal amount,
            BigDecimal commission,Instant executedAt,String note,UUID reversalTarget) {}
    private final PortfolioStore store;private final InstrumentService instruments;private final Clock clock;
    public PortfolioTransactionService(PortfolioStore store,InstrumentService instruments,Clock clock){this.store=store;this.instruments=instruments;this.clock=clock;}
    public PortfolioStore.Pending prepare(Command command,String requestKey,String payloadHash){
        var prior=store.pendingByRequest(requestKey);if(prior.isPresent()){if(!payloadHash.equals(prior.get().payloadHash()))throw new IllegalArgumentException("IDEMPOTENCY_CONFLICT");return prior.get();}
        var selected=store.selected().orElseThrow(()->new IllegalArgumentException("NO_ACTIVE_PORTFOLIO"));var account=selected.account();var now=clock.instant();
        boolean opening=command.type()==LedgerTransaction.Type.OPENING_POSITION;
        if(account.source()!=PortfolioAccount.Source.MANUAL && !opening && command.type()!=LedgerTransaction.Type.REVERSAL)throw new IllegalArgumentException("BROKER_ACCOUNT_MANUAL_OVERLAP_FORBIDDEN");
        String uid=command.instrument()==null?null:resolve(command.instrument());var executed=command.executedAt()==null?now:command.executedAt();
        if(executed.isAfter(now))throw new IllegalArgumentException("FUTURE_EXECUTION_DATE");
        if(opening && (command.note()==null || command.note().length()<3))throw new IllegalArgumentException("OPENING_PROVENANCE_REQUIRED");
        if(opening && account.source()==PortfolioAccount.Source.T_INVEST && !executed.isBefore(account.historyFrom()))throw new IllegalArgumentException("OPENING_MUST_PRECEDE_BROKER_HISTORY");
        BigDecimal gross=command.type()==LedgerTransaction.Type.BUY || command.type()==LedgerTransaction.Type.SELL?command.quantity().multiply(command.price()):command.amount();
        var transaction=new LedgerTransaction(UUID.randomUUID(),account.id(),command.type(),uid,command.quantity(),command.price(),gross,
                command.commission()==null?BigDecimal.ZERO:command.commission(),"RUB",executed,now,PortfolioAccount.Source.MANUAL,null,
                "OWNER_CONFIRMED; SHARES_NOT_LOTS; "+(command.note()==null?"manual transaction":command.note()),requestKey,command.reversalTarget(),"CONFIRMED");
        return store.locked(account.id(),a->{var rows=new ArrayList<>(a.transactions());validateBrokerCorrection(a.account(),transaction,rows);rows.add(transaction);new FifoAccountingEngine().rebuild(account.id(),rows,account.source()==PortfolioAccount.Source.MANUAL,now);
            return a.pending(new PortfolioStore.Pending(transaction.id(),requestKey,payloadHash,transaction,now.plus(Duration.ofMinutes(15)),null));});
    }
    private String resolve(String identifier){
        String uid=identifier.length()==36?InstrumentService.validateUid(identifier):instruments.resolve(identifier).uid();
        var metadata=instruments.getCached(uid);if(metadata.isPresent() && (metadata.get().currency()==null || !metadata.get().currency().equalsIgnoreCase("RUB")))throw new IllegalArgumentException("MANUAL_RUB_EQUITY_ONLY");
        // An exact UID without cached metadata is an explicit owner assertion; it cannot be valued until metadata is available.
        return uid;
    }
    public LedgerTransaction confirm(UUID token){
        var pending=store.pendingByToken(token).orElseThrow(()->new IllegalArgumentException("CONFIRMATION_NOT_FOUND"));
        return store.locked(pending.transaction().accountId(),a->{var p=a.pending(token);if(p.confirmedTransactionId()!=null)return a.transactions().stream().filter(t->t.id().equals(p.confirmedTransactionId())).findFirst().orElseThrow();
            if(!p.expiresAt().isAfter(clock.instant()))throw new IllegalArgumentException("CONFIRMATION_EXPIRED");
            var rows=new ArrayList<>(a.transactions());validateBrokerCorrection(a.account(),p.transaction(),rows);rows.add(p.transaction());var result=new FifoAccountingEngine().rebuild(a.account().id(),rows,a.account().source()==PortfolioAccount.Source.MANUAL,clock.instant());
            var saved=a.append(p.transaction());a.projection(result);a.confirm(token,saved.id());return saved;});
    }
    private void validateBrokerCorrection(PortfolioAccount account,LedgerTransaction t,List<LedgerTransaction> rows){
        if(account.source()==PortfolioAccount.Source.MANUAL || t.type()==LedgerTransaction.Type.OPENING_POSITION)return;
        if(t.type()!=LedgerTransaction.Type.REVERSAL || rows.stream().noneMatch(original->original.id().equals(t.correctionReference()) && original.type()==LedgerTransaction.Type.OPENING_POSITION && original.source()==PortfolioAccount.Source.MANUAL))
            throw new IllegalArgumentException("BROKER_ACCOUNT_MANUAL_OVERLAP_FORBIDDEN");
    }
    public AccountingResult rebuild(UUID account){return store.locked(account,a->{var result=new FifoAccountingEngine().rebuild(account,a.transactions(),a.account().source()==PortfolioAccount.Source.MANUAL,clock.instant());a.projection(result);return result;});}
}
