package ru.ba6kir.tradelab.portfolio.application.service;

import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import ru.ba6kir.tradelab.market.application.InstrumentService;
import ru.ba6kir.tradelab.market.application.price.MarketPriceService;
import ru.ba6kir.tradelab.portfolio.application.port.PortfolioStore;
import ru.ba6kir.tradelab.portfolio.domain.accounting.*;
import ru.ba6kir.tradelab.portfolio.domain.model.PortfolioAccount;
import ru.ba6kir.tradelab.portfolio.domain.valuation.PortfolioValuation;

/** Consistent local valuation only. Never resolves a ticker or fetches a provider quote. */
public class PortfolioValuationService {
    private final PortfolioStore store;private final InstrumentService instruments;private final MarketPriceService prices;private final Clock clock;private final Duration quoteAge;
    public PortfolioValuationService(PortfolioStore store,InstrumentService instruments,MarketPriceService prices,Clock clock,Duration quoteAge){this.store=store;this.instruments=instruments;this.prices=prices;this.clock=clock;this.quoteAge=quoteAge;}
    public PortfolioValuation value(){var selected=store.selected().orElseThrow(()->new IllegalArgumentException("NO_ACTIVE_PORTFOLIO"));return store.readConsistently(()->{
        var at=clock.instant();var values=new ArrayList<PortfolioValuation.AccountValue>();var allWarnings=new TreeSet<String>();BigDecimal equity=BigDecimal.ZERO,cash=BigDecimal.ZERO,pnl=BigDecimal.ZERO,unrealized=BigDecimal.ZERO,dividends=BigDecimal.ZERO,fees=BigDecimal.ZERO,taxes=BigDecimal.ZERO;
        boolean complete=true,pnlKnown=true,unrealizedKnown=true;
        for(var account:store.accounts(selected.portfolio().id())){
            var result=new FifoAccountingEngine().rebuild(account.id(),store.transactions(account.id()),account.source()==PortfolioAccount.Source.MANUAL,at);var warnings=new TreeSet<>(result.issues());
            boolean history=account.source()==PortfolioAccount.Source.MANUAL || completeHistory(account,at);
            if(!history)warnings.add("INCOMPLETE_BROKER_HISTORY");
            var last=store.lastSync(account.id());if(last.isPresent() && last.get().unsupported()>0)warnings.add("UNSUPPORTED_BROKER_OPERATIONS");
            var reconciliation=store.reconciliation(account.id());if(account.source()==PortfolioAccount.Source.T_INVEST && (reconciliation.isEmpty() || reconciliation.get().rows().stream().anyMatch(r->!r.status().equals("MATCHED"))))warnings.add("BROKER_RECONCILIATION_UNRESOLVED");
            boolean reliable=history && warnings.isEmpty();var positions=new ArrayList<PortfolioValuation.PositionValue>();
            if(account.source()==PortfolioAccount.Source.T_INVEST && !reliable)unrealizedKnown=false;
            for(var position:result.positions()){
                var metadata=instruments.getCached(position.instrumentUid()).orElse(null);var quote=prices.cachedQuote(position.instrumentUid()).orElse(null);var issues=new ArrayList<String>();BigDecimal value=null,profit=null,percent=null;
                String status=quote==null?"MISSING_PRICE":"ACCEPTABLE";
                if(quote!=null && (quote.quoteTime().isAfter(at)||quote.fetchedAt().isAfter(at)||!quote.quoteTime().plus(quoteAge).isAfter(at)||!quote.fetchedAt().plus(quoteAge).isAfter(at)))status="STALE_PRICE";
                if(metadata==null || !"RUB".equalsIgnoreCase(metadata.currency()) || metadata.classCode()==null || !metadata.classCode().startsWith("TQ"))issues.add("UNVERIFIED_MOEX_RUB_EQUITY_METADATA");
                if(!"RUB".equals(position.currency()) || quote!=null && (!position.currency().equalsIgnoreCase(quote.currency()) || !position.instrumentUid().equals(quote.instrumentUid())))issues.add("UNSUPPORTED_OR_MISMATCHED_CURRENCY");
                if(position.quantity()==null)issues.add("INCOMPLETE_INVENTORY");if(position.remainingCost()==null)issues.add("UNKNOWN_ACQUISITION_COST");
                if(!status.equals("ACCEPTABLE") && (position.quantity()==null || position.quantity().signum()!=0))issues.add(status);
                if(position.quantity()!=null && position.quantity().signum()==0){value=BigDecimal.ZERO;profit=BigDecimal.ZERO;}
                else if(issues.stream().noneMatch(x->!x.equals("UNKNOWN_ACQUISITION_COST")) && quote!=null){
                    value=position.quantity().multiply(quote.price());if(position.remainingCost()!=null && reliable){profit=value.subtract(position.remainingCost());if(position.remainingCost().signum()>0)percent=FifoAccountingEngine.divide(profit.multiply(BigDecimal.valueOf(100)),position.remainingCost());}
                }
                if(!reliable && account.source()==PortfolioAccount.Source.T_INVEST){issues.add("BROKER_ACCOUNT_VALUE_OR_COST_UNRECONCILED");value=null;profit=null;percent=null;}
                if(value!=null && "RUB".equals(position.currency()))equity=equity.add(value);if(profit==null && (position.quantity()==null || position.quantity().signum()!=0))unrealizedKnown=false;else if(profit!=null)unrealized=unrealized.add(profit);
                positions.add(new PortfolioValuation.PositionValue(account.id(),account.name(),metadata==null?position.instrumentUid():metadata.ticker(),position,quote,value,profit,percent,status,issues));warnings.addAll(issues);
            }
            if(result.cash().keySet().stream().anyMatch(c->!c.equals("RUB")))warnings.add("UNSUPPORTED_FOREIGN_CURRENCY_CASH");
            cash=cash.add(result.cash().getOrDefault("RUB",BigDecimal.ZERO));var realized=result.realizedPnL().getOrDefault("RUB",BigDecimal.ZERO);if(realized==null || !reliable)pnlKnown=false;else pnl=pnl.add(realized);
            dividends=dividends.add(result.dividends().getOrDefault("RUB",BigDecimal.ZERO));fees=fees.add(result.fees().getOrDefault("RUB",BigDecimal.ZERO));taxes=taxes.add(result.taxes().getOrDefault("RUB",BigDecimal.ZERO));
            boolean accountComplete=warnings.isEmpty();complete&=accountComplete;allWarnings.addAll(warnings);values.add(new PortfolioValuation.AccountValue(account.id(),account.name(),result,positions,reliable,accountComplete,List.copyOf(warnings)));
        }
        return new PortfolioValuation(selected.portfolio().id(),selected.portfolio().name(),at,values,equity,cash,complete?equity.add(cash):null,pnlKnown?pnl:null,unrealizedKnown?unrealized:null,dividends,fees,taxes,complete,List.copyOf(allWarnings));
    });}
    public static boolean completeHistory(PortfolioAccount a,Instant at){return a.historyFrom()!=null && a.brokerOpenedAt()!=null && !a.historyFrom().isAfter(a.brokerOpenedAt()) && a.checkpoint()!=null && !a.checkpoint().isAfter(at) && a.checkpoint().plus(Duration.ofDays(1)).isAfter(at);}
}
