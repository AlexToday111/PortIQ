package ru.ba6kir.tradelab.portfolio;

import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import ru.ba6kir.tradelab.analysis.scoring.infrastructure.configuration.ScoringFingerprints;
import ru.ba6kir.tradelab.market.domain.StockInstrument;
import ru.ba6kir.tradelab.market.domain.price.MarketQuote;
import ru.ba6kir.tradelab.portfolio.application.port.BrokerPortfolioProvider.*;
import ru.ba6kir.tradelab.portfolio.domain.model.*;
import static ru.ba6kir.tradelab.portfolio.domain.model.LedgerTransaction.Type;

/** All fixture identities, operations and prices are synthetic; never installed in production. */
final class PortfolioTestData {
    static final String UID="11111111-1111-1111-1111-111111111111",UID2="22222222-2222-2222-2222-222222222222";
    static final UUID ACCOUNT=UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"),PORTFOLIO=UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");
    static final Instant NOW=Instant.parse("2026-10-21T12:00:00Z");static final Clock CLOCK=Clock.fixed(NOW,ZoneOffset.UTC);
    static final ScoringFingerprints HASHES=new ScoringFingerprints(new ObjectMapper().findAndRegisterModules());
    static BigDecimal bd(String value){return value==null?null:new BigDecimal(value);}
    static UUID id(int n){return UUID.nameUUIDFromBytes(("SYNTHETIC-PORTFOLIO-"+n).getBytes(java.nio.charset.StandardCharsets.UTF_8));}
    static LedgerTransaction tx(int n,Type type,String quantity,String price,String amount,String commission){return new LedgerTransaction(id(n),ACCOUNT,type,
            Set.of(Type.BUY,Type.SELL,Type.OPENING_POSITION,Type.DIVIDEND_RECEIVED).contains(type)?UID:null,bd(quantity),bd(price),amount!=null?bd(amount):quantity!=null && price!=null?bd(quantity).multiply(bd(price)):null,
            bd(commission==null?"0":commission),"RUB",NOW.minusSeconds(3600).plusSeconds(n),NOW,PortfolioAccount.Source.MANUAL,null,"SYNTHETIC_TEST_ONLY","synthetic:"+n,null,"CONFIRMED");}
    static List<LedgerTransaction> example(){return List.of(tx(1,Type.DEPOSIT,null,null,"2000",null),tx(2,Type.BUY,"10","100",null,"10"),tx(3,Type.SELL,"5","120",null,"5"));}
    static PortfolioAccount account(){return new PortfolioAccount(ACCOUNT,PORTFOLIO,"SYNTHETIC MANUAL",PortfolioAccount.AccountType.MANUAL,PortfolioAccount.Source.MANUAL,null,null,false,null,null,null,null,NOW,NOW);}
    static Portfolio portfolio(){return new Portfolio(PORTFOLIO,"SYNTHETIC PORTFOLIO","","RUB","ACTIVE",NOW,NOW);}
    static StockInstrument instrument(){return new StockInstrument(UID,"SYNTH","TQBR","SYNTHETIC EQUITY",null,null,"RUB","MOEX",10,"NORMAL_TRADING",true,NOW,"SYNTHETIC");}
    static MarketQuote quote(){return new MarketQuote(UID,bd("120"),"RUB",NOW.minusSeconds(60),NOW,"SYNTHETIC");}
    static Operation cashOp(String id,String type,String amount,int seconds){return new Operation(id,null,type,"OPERATION_STATE_EXECUTED",type.equals("OPERATION_TYPE_DIVIDEND")?UID:null,null,NOW.minusSeconds(3600).plusSeconds(seconds),bd(amount),"RUB",null,null,BigDecimal.ZERO,List.of(),"SYNTHETIC CASH "+id);}
    static Operation buy(String id,String price,String fee){var p=bd(price);return new Operation(id,null,"OPERATION_TYPE_BUY","OPERATION_STATE_EXECUTED",UID,"share",NOW.minusSeconds(3000),p.multiply(bd("10")).negate(),"RUB",p,bd(fee),bd("10"),List.of(new Execution("synthetic-trade-1",NOW.minusSeconds(3000),bd("10"),p,"RUB")),"SYNTHETIC BUY "+id+" "+price+" "+fee);}
    static Snapshot brokerSnapshot(String quantity,String cash){return new Snapshot(NOW,List.of(new Holding(UID,"share","RUB",bd(quantity),BigDecimal.ZERO,bd(quantity),"SYNTHETIC BROKER HOLDING")),Map.of("RUB",bd(cash)),Map.of(),false,"SYNTHETIC BROKER SNAPSHOT");}
}
