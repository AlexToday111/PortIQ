package ru.ba6kir.tradelab.portfolio.domain.accounting;

import java.math.*;
import java.time.Instant;
import java.util.*;
import ru.ba6kir.tradelab.portfolio.domain.model.LedgerTransaction;
import static ru.ba6kir.tradelab.portfolio.domain.model.LedgerTransaction.Type;

/** Rebuildable long-only FIFO. No market prices, tax rate, provider or framework dependency. */
public class FifoAccountingEngine {
    private static final BigDecimal ZERO=BigDecimal.ZERO;
    public static BigDecimal divide(BigDecimal a,BigDecimal b) {return a.divide(b,18,RoundingMode.HALF_EVEN);}
    private static final class MutableLot {
        LedgerTransaction t;BigDecimal quantity,cost,original;
        MutableLot(LedgerTransaction t) {this.t=t;quantity=t.quantity();cost=t.type()==Type.OPENING_POSITION?t.grossAmount():t.grossAmount().add(t.commission());original=cost;}
    }
    public AccountingResult rebuild(UUID account,List<LedgerTransaction> history,boolean strict,Instant at) {
        var seen=new HashSet<UUID>();var keys=new HashSet<String>();var byId=new HashMap<UUID,LedgerTransaction>();var reversed=new HashSet<UUID>();
        for (var t:history) {
            if (!account.equals(t.accountId()) || !seen.add(t.id()) || !keys.add(t.idempotencyKey())) throw new IllegalArgumentException("DUPLICATE_OR_CROSS_ACCOUNT_TRANSACTION");
            byId.put(t.id(),t);
        }
        for (var t:history) if(t.type()==Type.REVERSAL) {
            var original=byId.get(t.correctionReference());
            if(original==null || original.type()==Type.REVERSAL || !reversed.add(original.id())) throw new IllegalArgumentException("INVALID_OR_DUPLICATE_REVERSAL");
        }
        var effective=history.stream().filter(t -> t.type()!=Type.REVERSAL && !reversed.contains(t.id()))
                .sorted(Comparator.comparing(LedgerTransaction::executedAt).thenComparingInt(t -> priority(t.type())).thenComparing(LedgerTransaction::idempotencyKey)).toList();
        var lots=new ArrayList<MutableLot>();var disposals=new ArrayList<AccountingResult.Disposal>();var cash=new TreeMap<String,BigDecimal>();
        var pnl=new TreeMap<String,BigDecimal>();var dividends=new TreeMap<String,BigDecimal>();var fees=new TreeMap<String,BigDecimal>();var taxes=new TreeMap<String,BigDecimal>();
        var groups=new TreeMap<String,String>();var invalid=new HashSet<String>();var issues=new TreeSet<String>();
        for (var t:effective) {
            var currency=t.currency();BigDecimal delta=ZERO;String group=t.instrumentUid()==null?null:t.instrumentUid()+"/"+currency;
            if(group!=null) groups.put(group,t.instrumentUid());
            if(t.type()==Type.BUY || t.type()==Type.OPENING_POSITION) {
                lots.add(new MutableLot(t));if(t.type()==Type.BUY) {delta=t.grossAmount().add(t.commission()).negate();add(fees,currency,t.commission());}
            } else if(t.type()==Type.SELL) {
                delta=t.grossAmount().subtract(t.commission());add(fees,currency,t.commission());BigDecimal remaining=t.quantity(),cost=ZERO,remainingFee=t.commission();boolean known=true;
                for(var lot:lots) if(remaining.signum()>0 && lot.t.instrumentUid().equals(t.instrumentUid()) && lot.t.currency().equals(currency) && lot.quantity.signum()>0) {
                    var consumed=lot.quantity.min(remaining);BigDecimal allocated=lot.cost==null?null:consumed.compareTo(lot.quantity)==0?lot.cost:divide(lot.cost.multiply(consumed),lot.quantity).min(lot.cost);
                    if(allocated==null) known=false;else {cost=cost.add(allocated);lot.cost=lot.cost.subtract(allocated);}
                    var fee=consumed.compareTo(remaining)==0?remainingFee:divide(t.commission().multiply(consumed),t.quantity()).min(remainingFee);remainingFee=remainingFee.subtract(fee);
                    lot.quantity=lot.quantity.subtract(consumed);remaining=remaining.subtract(consumed);disposals.add(new AccountingResult.Disposal(t.id(),lot.t.id(),consumed,allocated,fee));
                }
                if(remaining.signum()>0) {
                    if(strict) throw new IllegalArgumentException("SALE_EXCEEDS_AVAILABLE_SHARES");
                    invalid.add(group);issues.add("INCOMPLETE_INVENTORY:"+t.instrumentUid());known=false;
                }
                if(!known || invalid.contains(group)) pnl.put(currency,null);else if(!pnl.containsKey(currency) || pnl.get(currency)!=null) add(pnl,currency,delta.subtract(cost));
            } else switch(t.type()) {
                case DEPOSIT,CASH_ADJUSTMENT -> delta=t.grossAmount();
                case WITHDRAWAL -> delta=t.grossAmount().negate();
                case DIVIDEND_RECEIVED -> {delta=t.grossAmount();add(dividends,currency,t.grossAmount());}
                case BROKER_FEE -> {delta=t.grossAmount().negate();add(fees,currency,t.grossAmount());}
                case TAX_WITHHELD -> {delta=t.grossAmount().negate();add(taxes,currency,t.grossAmount());}
                default -> throw new IllegalArgumentException("UNSUPPORTED_LEDGER_TYPE");
            }
            add(cash,currency,delta);
            if(cash.get(currency).signum()<0) {if(strict) throw new IllegalArgumentException("INSUFFICIENT_CASH_AT_EXECUTION");issues.add("INCOMPLETE_OR_NEGATIVE_CASH:"+currency);}
        }
        var positions=new ArrayList<AccountingResult.Position>();
        for(var e:groups.entrySet()) {
            String currency=e.getKey().substring(e.getKey().lastIndexOf('/')+1);var owned=lots.stream().filter(l -> l.t.instrumentUid().equals(e.getValue()) && l.t.currency().equals(currency)).toList();
            // Dividend-only identity does not manufacture a position.
            if(owned.isEmpty() && !invalid.contains(e.getKey())) continue;
            var quantity=owned.stream().map(l -> l.quantity).reduce(ZERO,BigDecimal::add);boolean known=owned.stream().filter(l -> l.quantity.signum()>0).allMatch(l -> l.cost!=null);
            BigDecimal cost=known?owned.stream().filter(l -> l.quantity.signum()>0).map(l -> l.cost).reduce(ZERO,BigDecimal::add):null;
            boolean reliable=!invalid.contains(e.getKey());positions.add(new AccountingResult.Position(e.getValue(),currency,reliable?quantity:null,reliable?cost:null,
                    reliable && cost!=null && quantity.signum()>0?divide(cost,quantity):null,!reliable?"INCOMPLETE_INVENTORY":known?"KNOWN":"UNKNOWN_ACQUISITION_COST"));
        }
        return new AccountingResult(account,positions,lots.stream().map(l -> new AccountingResult.Lot(l.t.id(),l.t.instrumentUid(),l.t.currency(),l.t.quantity(),l.quantity,l.original,l.cost,l.t.executedAt())).toList(),
                disposals,cash,pnl,dividends,fees,taxes,List.copyOf(issues),at);
    }
    private static void add(Map<String,BigDecimal> map,String key,BigDecimal amount) {map.merge(key,amount,BigDecimal::add);}
    private static int priority(Type type) {return switch(type) {case OPENING_POSITION,DEPOSIT,CASH_ADJUSTMENT -> 0;case BUY -> 1;case DIVIDEND_RECEIVED -> 2;case SELL -> 3;default -> 4;};}
}
