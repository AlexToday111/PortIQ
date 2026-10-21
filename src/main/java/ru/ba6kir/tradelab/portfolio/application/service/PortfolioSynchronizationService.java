package ru.ba6kir.tradelab.portfolio.application.service;

import java.math.BigDecimal;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import ru.ba6kir.tradelab.analysis.scoring.application.service.ScoringFingerprint;
import ru.ba6kir.tradelab.market.application.MarketException;
import ru.ba6kir.tradelab.portfolio.application.port.*;
import ru.ba6kir.tradelab.portfolio.domain.accounting.FifoAccountingEngine;
import ru.ba6kir.tradelab.portfolio.domain.model.*;
import ru.ba6kir.tradelab.portfolio.domain.reconciliation.*;

public class PortfolioSynchronizationService {
    public record Outcome(String status,PortfolioStore.SyncReport report,String reason) {}
    private final PortfolioStore store;private final BrokerPortfolioProvider broker;private final ScoringFingerprint hashes;private final Clock clock;
    private final boolean enabled;private final Duration cooldown;private final int maxPages;private final BrokerOperationMapper mapper;
    public PortfolioSynchronizationService(PortfolioStore store,BrokerPortfolioProvider broker,ScoringFingerprint hashes,Clock clock,boolean enabled,Duration cooldown,int maxPages){
        this.store=store;this.broker=broker;this.hashes=hashes;this.clock=clock;this.enabled=enabled;this.cooldown=cooldown;this.maxPages=maxPages;mapper=new BrokerOperationMapper(hashes);
    }
    private void enabled(){if(!enabled)throw new IllegalArgumentException("BROKER_SYNC_DISABLED");}
    public List<Map<String,Object>> discover(){enabled();store.discover(broker.accounts(),clock.instant());return store.discovered();}
    public PortfolioAccount link(UUID candidate,Instant from,String name){return link(candidate,from,name,false);}
    public PortfolioAccount link(UUID candidate,Instant from,String name,boolean reviewedDistinctManualAccounts){enabled();var selection=store.selected().orElseThrow(()->new IllegalArgumentException("NO_ACTIVE_PORTFOLIO"));
        if(from==null || from.isAfter(clock.instant()) || from.isBefore(Instant.parse("1970-01-01T00:00:00Z")))throw new IllegalArgumentException("INVALID_HISTORY_START");
        boolean manualHistory=store.accounts(selection.portfolio().id()).stream().filter(a->a.source()==PortfolioAccount.Source.MANUAL).anyMatch(a->{var rows=store.transactions(a.id());var reversed=rows.stream().filter(t->t.type()==LedgerTransaction.Type.REVERSAL).map(LedgerTransaction::correctionReference).collect(java.util.stream.Collectors.toSet());return rows.stream().anyMatch(t->t.type()!=LedgerTransaction.Type.REVERSAL && !reversed.contains(t.id()));});
        if(manualHistory && !reviewedDistinctManualAccounts)throw new IllegalArgumentException("MANUAL_HISTORY_REVIEW_REQUIRED");
        return store.link(selection.portfolio().id(),candidate,from,name,reviewedDistinctManualAccounts,clock.instant());
    }
    public Outcome synchronize(){enabled();var account=store.selected().orElseThrow(()->new IllegalArgumentException("NO_ACTIVE_PORTFOLIO")).account();
        if(account.source()!=PortfolioAccount.Source.T_INVEST || !account.synchronizationEnabled())return new Outcome("NOT_LINKED",null,"MANUAL_ACCOUNT");
        var token=clock.instant().truncatedTo(ChronoUnit.MICROS);var admission=store.startSync(account.id(),token,cooldown);if(admission.isEmpty())return new Outcome("COOLDOWN_OR_RUNNING",null,null);
        Instant from=account.checkpoint()==null?account.historyFrom():account.checkpoint().minus(Duration.ofDays(7)).isBefore(account.historyFrom())?account.historyFrom():account.checkpoint().minus(Duration.ofDays(7));
        Instant to=from.plus(Duration.ofDays(31));if(to.isAfter(token))to=token;final Instant cutoff=to;
        try{
            // All external work precedes the atomic commit. Failure restarts the same overlapping window safely.
            var access=broker.accounts().stream().filter(a->a.externalId().equals(account.externalAccountId())).findFirst().orElseThrow(()->new IllegalArgumentException("SELECTED_BROKER_ACCOUNT_UNAVAILABLE"));
            if(!access.readOnly() || !access.status().equals("ACCOUNT_STATUS_OPEN"))throw new IllegalArgumentException("BROKER_ACCOUNT_NOT_READ_ONLY");
            var incoming=new TreeMap<String,BrokerPortfolioProvider.Operation>();String cursor="";var cursors=new HashSet<String>();boolean finished=false;
            for(int page=0;page<maxPages;page++){
                var response=broker.operations(account.externalAccountId(),from,cutoff,cursor,100);
                if(response.operations().size()>100)throw new IllegalArgumentException("OVERSIZED_BROKER_PAGE");
                for(var operation:response.operations()){
                    if(operation.externalId()==null || operation.externalId().isBlank() || operation.executedAt()!=null && (operation.executedAt().isBefore(from) || operation.executedAt().isAfter(cutoff)))throw new IllegalArgumentException("BROKER_PAGE_WINDOW_OR_ID_MISMATCH");
                    var prior=incoming.putIfAbsent(operation.externalId(),operation);if(prior!=null && !hashes.hash(prior).equals(hashes.hash(operation)))throw new IllegalArgumentException("CONFLICTING_PAGINATED_OPERATION");
                }
                if(!response.hasNext()){finished=true;break;}if(response.nextCursor()==null || response.nextCursor().isBlank() || !cursors.add(response.nextCursor()))throw new IllegalArgumentException("BROKER_CURSOR_LOOP");cursor=response.nextCursor();
            }
            if(!finished)throw new IllegalArgumentException("BROKER_PAGE_LIMIT_REACHED");
            var snapshot=broker.snapshot(account.externalAccountId());
            var report=store.locked(account.id(),scope->commit(scope,incoming.values().stream().toList(),snapshot,admission.get().runId(),token,from,cutoff));
            return new Outcome(report.status(),report,null);
        }catch(RuntimeException e){String reason=e instanceof MarketException m?m.reason().name():e instanceof IllegalArgumentException && e.getMessage()!=null && e.getMessage().matches("[A-Z_]{1,80}")?e.getMessage():"BROKER_SYNC_FAILED";
            try{store.failSync(account.id(),token,reason);}catch(RuntimeException ignored){}return new Outcome("FAILED_PREVIOUS_PRESERVED",null,reason);}
    }
    private PortfolioStore.SyncReport commit(PortfolioStore.LockedAccount scope,List<BrokerPortfolioProvider.Operation> incoming,BrokerPortfolioProvider.Snapshot snapshot,UUID run,Instant token,Instant from,Instant to){
        var now=clock.instant();var old=scope.sources();var combined=new TreeMap<String,BrokerPortfolioProvider.Operation>();old.forEach(o->combined.put(o.externalId(),o.operation()));incoming.forEach(o->combined.put(o.externalId(),o));
        var history=new ArrayList<>(scope.transactions());var reversed=new HashSet<UUID>();history.stream().filter(t->t.type()==LedgerTransaction.Type.REVERSAL).forEach(t->reversed.add(t.correctionReference()));
        var active=history.stream().filter(t->t.source()==PortfolioAccount.Source.T_INVEST && t.type()!=LedgerTransaction.Type.REVERSAL && !reversed.contains(t.id())).toList();
        var importedIds=active.stream().map(LedgerTransaction::externalOperationId).collect(java.util.stream.Collectors.toSet());
        var operations=combined.values().stream().sorted(Comparator.comparing((BrokerPortfolioProvider.Operation o)->!importedIds.contains(o.externalId())).thenComparing(BrokerPortfolioProvider.Operation::externalId)).toList();
        var plan=mapper.map(operations);int created=0,corrected=0,unchanged=0;
        for(var desired:plan.desired().values()){
            String reference="BROKER_COMPONENT:"+desired.key();var previous=active.stream().filter(t->t.sourceReference().equals(reference)).findFirst().orElse(null);
            if(previous!=null && equivalent(previous,desired)){unchanged++;continue;}
            if(previous!=null){var reversal=reversal(previous,now);scope.append(reversal);history.add(reversal);corrected++;}else created++;
            var transaction=new LedgerTransaction(UUID.randomUUID(),scope.account().id(),desired.type(),desired.uid(),desired.quantity(),desired.price(),desired.gross(),desired.commission(),desired.currency(),desired.executedAt(),now,
                    PortfolioAccount.Source.T_INVEST,desired.externalId(),reference,desired.key()+":"+hashes.hash(desired)+":"+(previous==null?"initial":previous.id()),previous==null?null:previous.id(),"CONFIRMED");
            scope.append(transaction);history.add(transaction);
        }
        for(var previous:active)if(!plan.desired().containsKey(previous.sourceReference().replace("BROKER_COMPONENT:","")) && "SUPPORTED".equals(plan.statuses().get(previous.externalOperationId()))){var reversal=reversal(previous,now);scope.append(reversal);history.add(reversal);corrected++;}
        int unsupported=0;
        for(var operation:operations){
            String status=plan.statuses().get(operation.externalId()),reason=plan.reasons().get(operation.externalId());
            if("DIAGNOSTIC".equals(status) && importedIds.contains(operation.externalId())){status="UNSUPPORTED";reason="PREVIOUSLY_IMPORTED_OPERATION_NO_LONGER_FINAL";}
            if("UNSUPPORTED".equals(status)||reason!=null)unsupported++;
            var existing=old.stream().filter(o->o.externalId().equals(operation.externalId())).findFirst().orElse(null);
            scope.source(new PortfolioStore.SourceOperation(existing==null?UUID.randomUUID():existing.id(),operation.externalId(),mapper.identity(operation),hashes.hash(operation),operation,status,reason),now);
        }
        var accounting=new FifoAccountingEngine().rebuild(scope.account().id(),history,false,now);scope.projection(accounting);
        boolean full=scope.account().brokerOpenedAt()!=null && !scope.account().historyFrom().isAfter(scope.account().brokerOpenedAt()) && !to.isBefore(token.minusSeconds(60));
        var reconciliation=new PortfolioReconciliationEngine().reconcile(accounting,snapshot,full,now);boolean partial=unsupported>0 || !full || !accounting.issues().isEmpty()
                || reconciliation.rows().stream().anyMatch(r->!r.status().equals("MATCHED")) || !reconciliation.warnings().isEmpty();
        var report=new PortfolioStore.SyncReport(run,partial?"PARTIAL":"SUCCESS",created,unchanged,corrected,unsupported,from,to,now,reconciliation);scope.completeSync(token,report,snapshot);return report;
    }
    private boolean equivalent(LedgerTransaction t,BrokerOperationMapper.Spec s){return t.type()==s.type() && Objects.equals(t.instrumentUid(),s.uid()) && decimal(t.quantity(),s.quantity()) && decimal(t.price(),s.price()) && decimal(t.grossAmount(),s.gross()) && decimal(t.commission(),s.commission()) && t.currency().equals(s.currency()) && t.executedAt().equals(s.executedAt());}
    private boolean decimal(BigDecimal a,BigDecimal b){return a==null?b==null:b!=null && a.compareTo(b)==0;}
    private LedgerTransaction reversal(LedgerTransaction t,Instant at){return new LedgerTransaction(UUID.randomUUID(),t.accountId(),LedgerTransaction.Type.REVERSAL,null,null,null,null,BigDecimal.ZERO,t.currency(),t.executedAt(),at,PortfolioAccount.Source.T_INVEST,t.externalOperationId(),"BROKER_CORRECTION; original="+t.id(),"reverse:"+t.id(),t.id(),"CONFIRMED");}
    public ReconciliationResult reconcile(){var selected=store.selected().orElseThrow(()->new IllegalArgumentException("NO_ACTIVE_PORTFOLIO"));if(selected.account().source()==PortfolioAccount.Source.MANUAL)throw new IllegalArgumentException("MANUAL_ACCOUNT_RECONCILIATION_UNAVAILABLE");
        return store.locked(selected.account().id(),scope->{var snapshot=store.brokerSnapshot(scope.account().id()).orElseThrow(()->new IllegalArgumentException("NO_BROKER_SNAPSHOT"));var at=clock.instant();var accounting=new FifoAccountingEngine().rebuild(scope.account().id(),scope.transactions(),false,at);
            var result=new PortfolioReconciliationEngine().reconcile(accounting,snapshot,PortfolioValuationService.completeHistory(scope.account(),at),at);scope.reconciliation(result);return result;});
    }
}
