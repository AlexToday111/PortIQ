package ru.ba6kir.tradelab.portfolio.application.service;

import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import ru.ba6kir.tradelab.market.application.InstrumentService;
import ru.ba6kir.tradelab.portfolio.application.port.PortfolioStore;
import ru.ba6kir.tradelab.portfolio.domain.model.*;
import ru.ba6kir.tradelab.portfolio.domain.accounting.AccountingResult;
import ru.ba6kir.tradelab.portfolio.domain.valuation.PortfolioValuation;

public class PortfolioService {
    public record AccountLot(UUID accountId,String accountName,AccountingResult.Lot lot) {}
    public record HoldingDetail(String uid,List<PortfolioValuation.PositionValue> positions,List<AccountLot> lots,
            BigDecimal shares,BigDecimal remainingCost,BigDecimal marketValue,BigDecimal unrealizedPnL) {}
    public record Status(PortfolioStore.Selection selected,PortfolioStore.SyncState synchronization,ru.ba6kir.tradelab.portfolio.domain.reconciliation.ReconciliationResult reconciliation,PortfolioValuation valuation) {}
    private final PortfolioStore store;private final PortfolioValuationService valuation;private final InstrumentService instruments;private final Clock clock;
    public PortfolioService(PortfolioStore store,PortfolioValuationService valuation,InstrumentService instruments,Clock clock){this.store=store;this.valuation=valuation;this.instruments=instruments;this.clock=clock;}
    private static String name(String value){if(value==null || value.isBlank() || value.length()>120 || value.matches(".*[\\p{Cntrl}\\p{Cf}].*"))throw new IllegalArgumentException("INVALID_NAME");return value;}
    public PortfolioStore.Selection create(String name,String requestKey){return store.createPortfolio(name(name),requestKey,clock.instant());}
    public Optional<PortfolioStore.Selection> selected(){return store.selected();}
    public List<Portfolio> portfolios(){return store.portfolios();}
    public void selectPortfolio(UUID id){store.selectPortfolio(id);}
    public List<PortfolioAccount> accounts(){return store.accounts(required().portfolio().id());}
    public PortfolioAccount createAccount(String name,String key){return store.createAccount(required().portfolio().id(),name(name),PortfolioAccount.AccountType.MANUAL,key,clock.instant());}
    public void selectAccount(UUID id){store.selectAccount(id);}
    private PortfolioStore.Selection required(){return store.selected().orElseThrow(()->new IllegalArgumentException("NO_ACTIVE_PORTFOLIO"));}
    public PortfolioValuation value(){return valuation.value();}
    public HoldingDetail position(String identifier){String uid=identifier.length()==36?InstrumentService.validateUid(identifier):instruments.resolve(identifier).uid();var v=value();
        var positions=v.accounts().stream().flatMap(a->a.positions().stream()).filter(p->p.position().instrumentUid().equals(uid)).toList();
        var lots=v.accounts().stream().flatMap(a->a.accounting().lots().stream().filter(l->l.instrumentUid().equals(uid)).map(l->new AccountLot(a.accountId(),a.name(),l))).toList();
        boolean compatible=positions.stream().allMatch(p->p.position().currency().equals("RUB"));
        return new HoldingDetail(uid,positions,lots,compatible?sum(positions.stream().map(p->p.position().quantity()).toList()):null,compatible?sum(positions.stream().map(p->p.position().remainingCost()).toList()):null,
                compatible?sum(positions.stream().map(PortfolioValuation.PositionValue::marketValue).toList()):null,compatible?sum(positions.stream().map(PortfolioValuation.PositionValue::unrealizedPnL).toList()):null);
    }
    private static BigDecimal sum(List<BigDecimal> values){return values.stream().anyMatch(Objects::isNull)?null:values.stream().reduce(BigDecimal.ZERO,BigDecimal::add);}
    public List<LedgerTransaction> transactions(int count){if(count<1 ||count>50)throw new IllegalArgumentException("TRANSACTION_COUNT_1_TO_50");return store.readConsistently(()->store.accounts(required().portfolio().id()).stream().flatMap(a->store.transactions(a.id()).stream()).sorted(Comparator.comparing(LedgerTransaction::executedAt).reversed().thenComparing(LedgerTransaction::id)).limit(count).toList());}
    public Status status(){var selected=required();return new Status(selected,store.syncState(selected.account().id()),store.reconciliation(selected.account().id()).orElse(null),value());}
}
