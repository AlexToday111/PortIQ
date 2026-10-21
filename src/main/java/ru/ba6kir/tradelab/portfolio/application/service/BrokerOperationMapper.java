package ru.ba6kir.tradelab.portfolio.application.service;

import java.math.*;
import java.time.Instant;
import java.util.*;
import ru.ba6kir.tradelab.analysis.scoring.application.service.ScoringFingerprint;
import ru.ba6kir.tradelab.portfolio.application.port.BrokerPortfolioProvider.*;
import ru.ba6kir.tradelab.portfolio.domain.model.LedgerTransaction.Type;

/** Conservative operation/execution mapping. No unknown operation is guessed into a trade. */
public class BrokerOperationMapper {
    public record Spec(String key,Type type,String uid,BigDecimal quantity,BigDecimal price,BigDecimal gross,BigDecimal commission,String currency,Instant executedAt,String externalId) {}
    public record Mapping(Map<String,Spec> desired,Map<String,String> statuses,Map<String,String> reasons) {public Mapping{desired=Map.copyOf(desired);statuses=Map.copyOf(statuses);reasons=Map.copyOf(reasons);}}
    private final ScoringFingerprint hashes;
    public BrokerOperationMapper(ScoringFingerprint hashes){this.hashes=hashes;}
    public String identity(Operation o){return !o.executions().isEmpty() && o.executions().stream().allMatch(e->e.tradeId()!=null && !e.tradeId().isBlank())
            ?"trades:"+hashes.hash(Arrays.asList(o.instrumentUid(),o.type(),o.executions().stream().map(Execution::tradeId).sorted().toList())):"operation:"+o.externalId();}
    public String financialHash(Operation o){return hashes.hash(Arrays.asList(o.type(),o.state(),o.instrumentUid(),o.instrumentType(),o.executedAt(),o.payment(),o.currency(),o.price(),o.commission(),o.quantityDone(),o.executions()));}
    public Mapping map(List<Operation> operations){
        var desired=new TreeMap<String,Spec>();var statuses=new TreeMap<String,String>();var reasons=new TreeMap<String,String>();var excluded=new HashSet<String>();
        var groups=new HashMap<String,List<Operation>>();for(var o:operations)groups.computeIfAbsent(identity(o),k->new ArrayList<>()).add(o);
        // Different provider IDs with identical stable execution evidence are aliases, never two trades.
        for(var group:groups.values()) if(group.size()>1){var canonical=group.getFirst();for(var o:group.subList(1,group.size())){
            excluded.add(o.externalId());if(financialHash(o).equals(financialHash(canonical))){statuses.put(o.externalId(),"ALIAS");}
            else {statuses.put(o.externalId(),"UNSUPPORTED");reasons.put(o.externalId(),"CHANGED_OPERATION_ID_REQUIRES_RECONCILIATION");excluded.add(canonical.externalId());statuses.put(canonical.externalId(),"UNSUPPORTED");reasons.put(canonical.externalId(),"CHANGED_OPERATION_ID_REQUIRES_RECONCILIATION");}
        }}
        var executionOwners=new HashMap<String,String>();
        for(var o:operations) if(!excluded.contains(o.externalId()))for(var execution:o.executions()){
            String key=o.type()+":"+o.instrumentUid()+":"+execution.tradeId();var owner=executionOwners.putIfAbsent(key,o.externalId());if(owner!=null && !owner.equals(o.externalId())){
                excluded.add(owner);excluded.add(o.externalId());for(var id:List.of(owner,o.externalId())){statuses.put(id,"UNSUPPORTED");reasons.put(id,"OVERLAPPING_EXECUTIONS_REQUIRES_RECONCILIATION");}
            }
        }
        // Cash operations have no stable execution identifier. An identical record under another ID is held for review.
        var cashHashes=new HashMap<String,String>();for(var o:operations)if(o.executions().isEmpty()){
            String prior=cashHashes.putIfAbsent(financialHash(o),o.externalId());if(prior!=null && !prior.equals(o.externalId())){
                excluded.add(o.externalId());statuses.put(o.externalId(),"UNSUPPORTED");reasons.put(o.externalId(),"POSSIBLE_CHANGED_CASH_OPERATION_ID");}
        }
        var related=new HashMap<String,BigDecimal>();var relatedIds=new HashMap<String,List<String>>();
        for(var o:operations)if(o.type().equals("OPERATION_TYPE_BROKER_FEE") && o.parentId()!=null && !o.parentId().isBlank() && !excluded.contains(o.externalId())
                && o.state().equals("OPERATION_STATE_EXECUTED") && "RUB".equals(o.currency()) && o.payment()!=null && o.payment().signum()<0){
            related.merge(o.parentId(),o.payment().abs(),BigDecimal::add);relatedIds.computeIfAbsent(o.parentId(),k->new ArrayList<>()).add(o.externalId());
        }
        for(var o:operations){
            if(excluded.contains(o.externalId()))continue;
            if(!o.state().equals("OPERATION_STATE_EXECUTED")){statuses.put(o.externalId(),"DIAGNOSTIC");if(o.quantityDone()!=null && o.quantityDone().signum()>0)reasons.put(o.externalId(),"UNFINALIZED_EXECUTION_REQUIRES_RECONCILIATION");continue;}
            try{
                if(o.executedAt()==null || !"RUB".equals(o.currency()) || o.payment()==null)throw new IllegalArgumentException("UNSUPPORTED_CURRENCY_OR_MISSING_DATE_AMOUNT");
                if(isTrade(o)){
                    if(!"share".equals(o.instrumentType()) || o.instrumentUid()==null)throw new IllegalArgumentException("UNSUPPORTED_INSTRUMENT");
                    BigDecimal embedded=o.commission()==null?BigDecimal.ZERO:o.commission().abs(),separate=related.getOrDefault(o.externalId(),BigDecimal.ZERO);
                    if(o.commission()==null && separate.signum()==0)throw new IllegalArgumentException("MISSING_COMMISSION_EVIDENCE");
                    if(embedded.signum()>0 && separate.signum()>0 && embedded.compareTo(separate)!=0)throw new IllegalArgumentException("RELATED_COMMISSION_MISMATCH");
                    var commission=embedded.signum()>0?embedded:separate;
                    List<Execution> executions=o.executions().isEmpty()?List.of(new Execution("aggregate",o.executedAt(),o.quantityDone(),o.price(),o.currency())):o.executions();
                    BigDecimal quantity=BigDecimal.ZERO,gross=BigDecimal.ZERO;
                    var nums=new HashSet<String>();for(var e:executions){if(e.executedAt()==null || e.quantity()==null || e.quantity().signum()<=0 || e.price()==null || e.price().signum()<=0 || !"RUB".equals(e.currency()) || e.tradeId()==null || e.tradeId().isBlank() || !nums.add(e.tradeId()))throw new IllegalArgumentException("INVALID_EXECUTION_EVIDENCE");quantity=quantity.add(e.quantity());gross=gross.add(e.quantity().multiply(e.price()));}
                    if(o.quantityDone()==null || quantity.compareTo(o.quantityDone())!=0)throw new IllegalArgumentException("EXECUTED_QUANTITY_MISMATCH");
                    boolean buy=o.type().equals("OPERATION_TYPE_BUY");if(buy!= (o.payment().signum()<0) || o.payment().signum()==0)throw new IllegalArgumentException("TRADE_PAYMENT_SIGN_MISMATCH");
                    var net=buy?gross.add(commission):gross.subtract(commission);if(o.payment().abs().compareTo(gross)!=0 && o.payment().abs().compareTo(net)!=0)throw new IllegalArgumentException("TRADE_PAYMENT_COMPONENTS_MISMATCH");
                    var remainingFee=commission;var sorted=executions.stream().sorted(Comparator.comparing(Execution::executedAt).thenComparing(Execution::tradeId)).toList();
                    for(int index=0;index<sorted.size();index++){
                        var e=sorted.get(index);var amount=e.quantity().multiply(e.price());var fee=index==sorted.size()-1?remainingFee:commission.multiply(amount).divide(gross,18,RoundingMode.HALF_EVEN).min(remainingFee);remainingFee=remainingFee.subtract(fee);
                        String key="broker:"+hashes.hash(List.of(o.externalId(),e.tradeId()));desired.put(key,new Spec(key,buy?Type.BUY:Type.SELL,o.instrumentUid(),e.quantity(),e.price(),amount,fee,"RUB",e.executedAt(),o.externalId()));
                    }
                    for(var id:relatedIds.getOrDefault(o.externalId(),List.of()))statuses.put(id,"INCLUDED_IN_TRADE");
                }else{
                    Type type=switch(o.type()){case "OPERATION_TYPE_INPUT" -> Type.DEPOSIT;case "OPERATION_TYPE_OUTPUT" -> Type.WITHDRAWAL;case "OPERATION_TYPE_DIVIDEND" -> Type.DIVIDEND_RECEIVED;
                        case "OPERATION_TYPE_BROKER_FEE","OPERATION_TYPE_SERVICE_FEE" -> Type.BROKER_FEE;case "OPERATION_TYPE_TAX","OPERATION_TYPE_DIVIDEND_TAX","OPERATION_TYPE_TAX_PROGRESSIVE","OPERATION_TYPE_DIVIDEND_TAX_PROGRESSIVE" -> Type.TAX_WITHHELD;
                        default -> throw new IllegalArgumentException("UNSUPPORTED_OPERATION_TYPE:"+o.type());};
                    if(type==Type.BROKER_FEE && o.parentId()!=null && !o.parentId().isBlank())continue;
                    boolean credit=type==Type.DEPOSIT || type==Type.DIVIDEND_RECEIVED;if(credit!= (o.payment().signum()>0) || o.payment().signum()==0)throw new IllegalArgumentException("CASH_PAYMENT_SIGN_MISMATCH");
                    if(type==Type.DIVIDEND_RECEIVED && o.instrumentUid()==null)throw new IllegalArgumentException("DIVIDEND_UID_MISSING");
                    String key="broker:"+hashes.hash(o.externalId());desired.put(key,new Spec(key,type,type==Type.DIVIDEND_RECEIVED?o.instrumentUid():null,null,null,o.payment().abs(),BigDecimal.ZERO,o.currency(),o.executedAt(),o.externalId()));
                }
                statuses.putIfAbsent(o.externalId(),"SUPPORTED");
            }catch(IllegalArgumentException e){statuses.put(o.externalId(),"UNSUPPORTED");reasons.put(o.externalId(),e.getMessage());}
        }
        for(var o:operations)if(!statuses.containsKey(o.externalId())){statuses.put(o.externalId(),"UNSUPPORTED");reasons.put(o.externalId(),"ORPHAN_RELATED_COMMISSION");}
        return new Mapping(desired,statuses,reasons);
    }
    private static boolean isTrade(Operation o){return Set.of("OPERATION_TYPE_BUY","OPERATION_TYPE_SELL").contains(o.type());}
}
