package ru.ba6kir.tradelab.portfolio.infrastructure.tinvest;

import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import com.google.protobuf.Timestamp;
import ru.tinkoff.piapi.contract.v1.*;
import ru.ba6kir.tradelab.market.infrastructure.tinvest.*;
import ru.ba6kir.tradelab.portfolio.application.port.BrokerPortfolioProvider;

/** Exactly four read RPCs; no order, transfer, account mutation or deprecated operations RPC. */
public class TInvestBrokerPortfolioProvider implements BrokerPortfolioProvider {
    private final TInvestStockInstrumentProvider transport;private final Clock clock;
    public TInvestBrokerPortfolioProvider(TInvestStockInstrumentProvider transport,Clock clock){this.transport=transport;this.clock=clock;}
    private static String optional(String s){return s==null || s.isBlank()?null:s;}
    private static Instant time(Timestamp t){if(t.getSeconds()<0 || t.getNanos()<0 || t.getNanos()>=1_000_000_000)throw new IllegalArgumentException("INVALID_BROKER_TIMESTAMP");return Instant.ofEpochSecond(t.getSeconds(),t.getNanos());}
    private static Timestamp timestamp(Instant t){return Timestamp.newBuilder().setSeconds(t.getEpochSecond()).setNanos(t.getNano()).build();}
    private static String currency(MoneyValue m){return m.getCurrency().toUpperCase(Locale.ROOT);}
    private static BigDecimal money(MoneyValue m){return QuotationDecimals.from(m);}
    private static String account(String id){if(id==null || !id.matches("[A-Za-z0-9_-]{1,120}"))throw new IllegalArgumentException("INVALID_BROKER_ACCOUNT_REFERENCE");return id;}
    @Override public List<BrokerAccount> accounts(){
        return transport.usersCall(s->s.getAccounts(GetAccountsRequest.newBuilder().setStatus(AccountStatus.ACCOUNT_STATUS_OPEN).build())).getAccountsList().stream().map(a->
                new BrokerAccount(account(a.getId()),a.getName(),a.getType().name(),a.getStatus().name(),a.getAccessLevel()==AccessLevel.ACCOUNT_ACCESS_LEVEL_READ_ONLY,a.hasOpenedDate()?time(a.getOpenedDate()):null)).toList();
    }
    @Override public Page operations(String external,Instant from,Instant to,String cursor,int limit){
        if(from==null || to==null || from.isAfter(to) || limit<1 || limit>100)throw new IllegalArgumentException("INVALID_BROKER_OPERATIONS_WINDOW");
        var request=GetOperationsByCursorRequest.newBuilder().setAccountId(account(external)).setFrom(timestamp(from)).setTo(timestamp(to)).setLimit(limit)
                .setWithoutCommissions(false).setWithoutTrades(false).setWithoutOvernights(false);if(cursor!=null && !cursor.isBlank())request.setCursor(cursor);
        var response=transport.operationsCall(s->s.getOperationsByCursor(request.build()));
        return new Page(response.getItemsList().stream().map(o->{
            if(!o.getBrokerAccountId().isBlank() && !external.equals(o.getBrokerAccountId()))throw new IllegalArgumentException("BROKER_ACCOUNT_RESPONSE_MISMATCH");
            var trades=o.hasTradesInfo()?o.getTradesInfo().getTradesList().stream().map(t->new Execution(optional(t.getNum()),t.hasDate()?time(t.getDate()):null,
                    BigDecimal.valueOf(t.getQuantity()),t.hasPrice()?money(t.getPrice()):null,t.hasPrice()?currency(t.getPrice()):null)).toList():List.<Execution>of();
            String cur=o.hasPayment()?currency(o.getPayment()):null;
            if(o.hasCommission() && !Objects.equals(cur,currency(o.getCommission())) || o.hasPrice() && !Objects.equals(cur,currency(o.getPrice())))cur="UNVERIFIED";
            return new Operation(o.getId(),optional(o.getParentOperationId()),o.getType().name(),o.getState().name(),optional(o.getInstrumentUid()),optional(o.getInstrumentType()),o.hasDate()?time(o.getDate()):null,
                    o.hasPayment()?money(o.getPayment()):null,cur,o.hasPrice()?money(o.getPrice()):null,o.hasCommission()?money(o.getCommission()):null,BigDecimal.valueOf(o.getQuantityDone()),trades,o.toString());
        }).toList(),response.getHasNext(),response.getNextCursor());
    }
    @Override public Snapshot snapshot(String external){
        account(external);var positions=transport.operationsCall(s->s.getPositions(PositionsRequest.newBuilder().setAccountId(external).build()));
        var portfolio=transport.operationsCall(s->s.getPortfolio(PortfolioRequest.newBuilder().setAccountId(external).setCurrency(PortfolioRequest.CurrencyRequest.RUB).build()));
        if(!positions.getAccountId().isBlank() && !external.equals(positions.getAccountId()) || !portfolio.getAccountId().isBlank() && !external.equals(portfolio.getAccountId()))throw new IllegalArgumentException("BROKER_ACCOUNT_RESPONSE_MISMATCH");
        var holdings=new ArrayList<Holding>();var seen=new HashSet<String>();
        for(var p:portfolio.getPositionsList()){
            var settled=positions.getSecuritiesList().stream().filter(s->s.getInstrumentUid().equals(p.getInstrumentUid())).findFirst().orElse(null);
            var cur=p.hasCurrentPrice()?currency(p.getCurrentPrice()):p.hasAveragePositionPrice()?currency(p.getAveragePositionPrice()):null;
            holdings.add(new Holding(optional(p.getInstrumentUid()),p.getInstrumentType(),cur,p.hasQuantity()?QuotationDecimals.from(p.getQuantity()):null,
                    settled==null?null:BigDecimal.valueOf(settled.getBlocked()),settled==null?null:BigDecimal.valueOf(settled.getBalance()),"GET_PORTFOLIO.quantity shares; GET_POSITIONS.balance free/blocked"));seen.add(p.getInstrumentUid());
        }
        for(var p:positions.getSecuritiesList())if(!seen.contains(p.getInstrumentUid()))holdings.add(new Holding(optional(p.getInstrumentUid()),p.getInstrumentType(),null,null,BigDecimal.valueOf(p.getBlocked()),BigDecimal.valueOf(p.getBalance()),"GET_POSITIONS_ONLY; total quantity unverified"));
        for(var p:positions.getFuturesList())if(!seen.contains(p.getInstrumentUid()))holdings.add(new Holding(optional(p.getInstrumentUid()),"futures",null,null,BigDecimal.valueOf(p.getBlocked()),BigDecimal.valueOf(p.getBalance()),"GET_POSITIONS_FUTURES"));
        for(var p:positions.getOptionsList())if(!seen.contains(p.getInstrumentUid()))holdings.add(new Holding(optional(p.getInstrumentUid()),"options",null,null,BigDecimal.valueOf(p.getBlocked()),BigDecimal.valueOf(p.getBalance()),"GET_POSITIONS_OPTIONS"));
        // Virtual positions have distinct economics; never silently omit them from completeness diagnostics.
        for(var p:portfolio.getVirtualPositionsList())holdings.add(new Holding(optional(p.getInstrumentUid()),"virtual:"+p.getInstrumentType(),p.hasCurrentPrice()?currency(p.getCurrentPrice()):null,p.hasQuantity()?QuotationDecimals.from(p.getQuantity()):null,null,null,"GET_PORTFOLIO_VIRTUAL"));
        var cash=new TreeMap<String,BigDecimal>();var blocked=new TreeMap<String,BigDecimal>();positions.getMoneyList().forEach(m->cash.merge(currency(m),money(m),BigDecimal::add));positions.getBlockedList().forEach(m->blocked.merge(currency(m),money(m),BigDecimal::add));
        return new Snapshot(clock.instant(),holdings,cash,blocked,positions.getLimitsLoadingInProgress(),"GetPositions:\n"+positions+"\nGetPortfolio:\n"+portfolio);
    }
}
