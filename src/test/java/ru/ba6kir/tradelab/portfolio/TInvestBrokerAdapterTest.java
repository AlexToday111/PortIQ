package ru.ba6kir.tradelab.portfolio;

import java.time.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import io.grpc.*;
import org.junit.jupiter.api.Test;
import ru.tinkoff.piapi.contract.v1.*;
import ru.ba6kir.tradelab.market.infrastructure.tinvest.*;
import ru.ba6kir.tradelab.market.application.MarketException;
import ru.ba6kir.tradelab.portfolio.infrastructure.tinvest.TInvestBrokerPortfolioProvider;
import static ru.ba6kir.tradelab.portfolio.PortfolioTestData.*;
import static org.assertj.core.api.Assertions.*;

class TInvestBrokerAdapterTest {
    final ScriptedChannel channel=new ScriptedChannel();final InvestProperties properties=new InvestProperties(true,"synthetic-not-a-real-token",Duration.ofDays(1),Duration.ofMillis(100),3,Duration.ofMillis(1));
    final TInvestStockInstrumentProvider transport=new TInvestStockInstrumentProvider(InstrumentsServiceGrpc.newBlockingStub(channel),channel,properties,CLOCK);
    final TInvestBrokerPortfolioProvider adapter=new TInvestBrokerPortfolioProvider(transport,CLOCK);
    static com.google.protobuf.Timestamp time(Instant value){return com.google.protobuf.Timestamp.newBuilder().setSeconds(value.getEpochSecond()).setNanos(value.getNano()).build();}
    static MoneyValue money(long units){return MoneyValue.newBuilder().setCurrency("rub").setUnits(units).build();}
    @Test void discoversEveryAccountAndPreservesReadOnlyAccessWithoutSelectingTheFirst(){channel.responses.add(GetAccountsResponse.newBuilder().addAccounts(Account.newBuilder().setId("synthetic-A").setName("SYNTHETIC A").setType(AccountType.ACCOUNT_TYPE_TINKOFF).setStatus(AccountStatus.ACCOUNT_STATUS_OPEN).setAccessLevel(AccessLevel.ACCOUNT_ACCESS_LEVEL_FULL_ACCESS))
                .addAccounts(Account.newBuilder().setId("synthetic-B").setName("SYNTHETIC B").setType(AccountType.ACCOUNT_TYPE_TINKOFF_IIS).setStatus(AccountStatus.ACCOUNT_STATUS_OPEN).setAccessLevel(AccessLevel.ACCOUNT_ACCESS_LEVEL_READ_ONLY).setOpenedDate(time(NOW.minusSeconds(86400)))).build());
        var accounts=adapter.accounts();assertThat(accounts).hasSize(2);assertThat(accounts.getFirst().readOnly()).isFalse();assertThat(accounts.get(1).readOnly()).isTrue();assertThat(accounts.get(1).externalId()).isEqualTo("synthetic-B");assertThat(accounts.get(1).toString()).doesNotContain("synthetic-B");assertThat(channel.methods).containsExactly("tinkoff.public.invest.api.contract.v1.UsersService/GetAccounts");}
    @Test void cursorRequestIncludesExecutionsCommissionsAndActualFilledSharesNotLots(){channel.responses.add(GetOperationsByCursorResponse.newBuilder().setHasNext(true).setNextCursor("next").addItems(OperationItem.newBuilder().setId("synthetic-operation").setBrokerAccountId("synthetic-B").setType(OperationType.OPERATION_TYPE_BUY).setState(OperationState.OPERATION_STATE_EXECUTED).setInstrumentUid(UID).setInstrumentType("share")
                .setDate(time(NOW.minusSeconds(100))).setPayment(money(-1000)).setPrice(money(100)).setCommission(money(-10)).setQuantity(20).setQuantityRest(10).setQuantityDone(10)
                .setTradesInfo(OperationItemTrades.newBuilder().addTrades(OperationItemTrade.newBuilder().setNum("execution-1").setDate(time(NOW.minusSeconds(100))).setQuantity(10).setPrice(money(100))))).build());
        var page=adapter.operations("synthetic-B",NOW.minusSeconds(3600),NOW,"previous",100);assertThat(page.hasNext()).isTrue();assertThat(page.nextCursor()).isEqualTo("next");assertThat(page.operations().getFirst().quantityDone()).isEqualByComparingTo("10");assertThat(page.operations().getFirst().executions()).hasSize(1);assertThat(page.operations().getFirst().commission()).isEqualByComparingTo("-10");
        var request=(GetOperationsByCursorRequest)channel.requests.getFirst();assertThat(request.getCursor()).isEqualTo("previous");assertThat(request.getWithoutCommissions()).isFalse();assertThat(request.getWithoutTrades()).isFalse();assertThat(request.getAccountId()).isEqualTo("synthetic-B");assertThat(channel.methods).containsExactly("tinkoff.public.invest.api.contract.v1.OperationsService/GetOperationsByCursor");}
    @Test void holdingsPreserveBlockedSettledAndUnsupportedAssetsWithoutUsingBrokerAverageCostAsHistory(){channel.responses.add(PositionsResponse.newBuilder().setAccountId("synthetic-B").addMoney(money(990)).addBlocked(money(10)).addSecurities(PositionsSecurities.newBuilder().setInstrumentUid(UID).setInstrumentType("share").setBalance(8).setBlocked(2)).build());
        channel.responses.add(PortfolioResponse.newBuilder().setAccountId("synthetic-B").addPositions(PortfolioPosition.newBuilder().setInstrumentUid(UID).setInstrumentType("share").setQuantity(Quotation.newBuilder().setUnits(10)).setCurrentPrice(money(120)).setAveragePositionPrice(money(999)))
                .addPositions(PortfolioPosition.newBuilder().setInstrumentUid(UID2).setInstrumentType("bond").setQuantity(Quotation.newBuilder().setUnits(3)).setCurrentPrice(money(1000))).build());
        var snapshot=adapter.snapshot("synthetic-B");assertThat(snapshot.holdings()).hasSize(2);assertThat(snapshot.holdings().getFirst().quantity()).isEqualByComparingTo("10");assertThat(snapshot.holdings().getFirst().blockedQuantity()).isEqualByComparingTo("2");assertThat(snapshot.holdings().getFirst().settledFreeQuantity()).isEqualByComparingTo("8");assertThat(snapshot.holdings().get(1).instrumentType()).isEqualTo("bond");assertThat(snapshot.cash().get("RUB")).isEqualByComparingTo("990");assertThat(channel.methods).containsExactly("tinkoff.public.invest.api.contract.v1.OperationsService/GetPositions","tinkoff.public.invest.api.contract.v1.OperationsService/GetPortfolio");}
    @Test void freshDeadlinesBoundRetriesAndRateLimitOrAuthenticationDoNotLeakSensitiveDetails(){channel.responses.add(Status.DEADLINE_EXCEEDED);channel.responses.add(Status.DEADLINE_EXCEEDED);channel.responses.add(Status.DEADLINE_EXCEEDED);
        assertThatThrownBy(adapter::accounts).isInstanceOfSatisfying(MarketException.class,e->assertThat(e.reason()).isEqualTo(MarketException.Reason.TIMEOUT));assertThat(channel.deadlines).hasSize(3).allSatisfy(d->assertThat(d.timeRemaining(TimeUnit.MILLISECONDS)).isLessThanOrEqualTo(100));
        channel.responses.add(Status.UNAUTHENTICATED.withDescription("private-token-sensitive"));assertThatThrownBy(adapter::accounts).isInstanceOfSatisfying(MarketException.class,e->{assertThat(e.reason()).isEqualTo(MarketException.Reason.AUTHENTICATION);assertThat(e.getMessage()).doesNotContain("private-token-sensitive");});
        channel.responses.add(Status.RESOURCE_EXHAUSTED);assertThatThrownBy(adapter::accounts).isInstanceOf(MarketException.class);var requests=channel.requests.size();assertThatThrownBy(adapter::accounts).isInstanceOfSatisfying(MarketException.class,e->assertThat(e.reason()).isEqualTo(MarketException.Reason.RATE_LIMIT));assertThat(channel.requests).hasSize(requests);}
    @Test void mismatchedAccountResponseIsRejected(){channel.responses.add(GetOperationsByCursorResponse.newBuilder().addItems(OperationItem.newBuilder().setId("x").setBrokerAccountId("wrong-account")).build());assertThatThrownBy(()->adapter.operations("synthetic-B",NOW.minusSeconds(1),NOW,"",100)).hasMessage("BROKER_ACCOUNT_RESPONSE_MISMATCH");}
    static final class ScriptedChannel extends ManagedChannel {
        final Queue<Object> responses=new ArrayDeque<>();final List<Object> requests=new ArrayList<>();final List<String> methods=new ArrayList<>();final List<Deadline> deadlines=new ArrayList<>();
        public String authority(){return "synthetic-in-memory";}public ManagedChannel shutdown(){return this;}public ManagedChannel shutdownNow(){return this;}public boolean isShutdown(){return false;}public boolean isTerminated(){return false;}public boolean awaitTermination(long timeout,TimeUnit unit){return true;}
        public <ReqT,RespT>ClientCall<ReqT,RespT> newCall(MethodDescriptor<ReqT,RespT> method,CallOptions options){methods.add(method.getFullMethodName());deadlines.add(options.getDeadline());return new ClientCall<>(){
            Listener<RespT> listener;public void start(Listener<RespT> l,Metadata headers){listener=l;}public void request(int count){}public void cancel(String m,Throwable c){}public void sendMessage(ReqT r){requests.add(r);}
            @SuppressWarnings("unchecked") public void halfClose(){var response=responses.remove();if(response instanceof Status status)listener.onClose(status,new Metadata());else{listener.onMessage((RespT)response);listener.onClose(Status.OK,new Metadata());}}
        };}
    }
}
