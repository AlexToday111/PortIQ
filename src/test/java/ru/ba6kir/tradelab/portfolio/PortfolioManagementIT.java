package ru.ba6kir.tradelab.portfolio;

import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;
import ru.ba6kir.tradelab.market.application.*;
import ru.ba6kir.tradelab.market.application.price.*;
import ru.ba6kir.tradelab.market.infrastructure.persistence.*;
import ru.ba6kir.tradelab.portfolio.application.port.*;
import ru.ba6kir.tradelab.portfolio.application.service.*;
import ru.ba6kir.tradelab.portfolio.domain.accounting.FifoAccountingEngine;
import ru.ba6kir.tradelab.portfolio.domain.model.*;
import ru.ba6kir.tradelab.portfolio.infrastructure.persistence.JdbcPortfolioStore;
import ru.ba6kir.tradelab.portfolio.telegram.*;
import ru.ba6kir.tradelab.telegram.adapter.TelegramMessageSender;
import ru.ba6kir.tradelab.telegram.command.TelegramCommand;
import ru.ba6kir.tradelab.telegram.dispatcher.TelegramCommandDispatcher;
import ru.ba6kir.tradelab.telegram.security.TelegramAccessGuard;
import ru.ba6kir.tradelab.telegram.service.TelegramBotState;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static ru.ba6kir.tradelab.portfolio.PortfolioTestData.*;
import static ru.ba6kir.tradelab.portfolio.domain.model.LedgerTransaction.Type;
import static ru.ba6kir.tradelab.telegram.TelegramTestData.*;

@Testcontainers(disabledWithoutDocker=true)
class PortfolioManagementIT {
    @Container static final PostgreSQLContainer<?> POSTGRES=new PostgreSQLContainer<>("postgres:17-bookworm");
    static final class MutableClock extends Clock {Instant at=NOW;public ZoneId getZone(){return ZoneOffset.UTC;}public Clock withZone(ZoneId z){return this;}public Instant instant(){return at;}}
    MutableClock clock;JdbcTemplate jdbc;DataSourceTransactionManager manager;PortfolioStore store;InstrumentService instruments;StockInstrumentProvider metadata;
    MarketPriceProvider quotes;MarketPriceService prices;BrokerPortfolioProvider broker;PortfolioTransactionService transactions;PortfolioValuationService valuations;PortfolioService portfolios;PortfolioSynchronizationService sync;
    @BeforeEach void setup(){
        clock=new MutableClock();var source=new DriverManagerDataSource(POSTGRES.getJdbcUrl(),POSTGRES.getUsername(),POSTGRES.getPassword());manager=new DataSourceTransactionManager(source);jdbc=new JdbcTemplate(source);
        Flyway.configure().dataSource(source).load().migrate();
        // All destructive reset SQL is confined to this disposable Testcontainers database.
        jdbc.execute("TRUNCATE portfolios,broker_account_candidates RESTART IDENTITY CASCADE");jdbc.update("INSERT INTO portfolio_owner_selection(id) VALUES(1)");
        store=new JdbcPortfolioStore(jdbc,new ObjectMapper().findAndRegisterModules(),manager,clock);metadata=mock(StockInstrumentProvider.class);var catalog=new JdbcInstrumentStore(jdbc);catalog.upsert(instrument());
        instruments=new InstrumentService(metadata,catalog,clock,Duration.ofDays(1),false);quotes=mock(MarketPriceProvider.class);var priceStore=new JdbcMarketPriceStore(jdbc,manager,"SYNTHETIC");priceStore.saveQuote(quote());prices=new MarketPriceService(instruments,quotes,priceStore,clock,Duration.ofSeconds(60),365,List.of(),false);
        broker=mock(BrokerPortfolioProvider.class);transactions=new PortfolioTransactionService(store,instruments,clock);valuations=new PortfolioValuationService(store,instruments,prices,clock,Duration.ofDays(3));portfolios=new PortfolioService(store,valuations,instruments,clock);sync=new PortfolioSynchronizationService(store,broker,HASHES,clock,true,Duration.ofSeconds(60),20);
        portfolios.create("SYNTHETIC TEST PORTFOLIO","synthetic:create");
    }
    LedgerTransaction record(int n,Type type,String quantity,String price,String amount,String fee){var p=transactions.prepare(new PortfolioTransactionService.Command(type,Set.of(Type.BUY,Type.SELL,Type.DIVIDEND_RECEIVED,Type.OPENING_POSITION).contains(type)?UID:null,bd(quantity),bd(price),bd(amount),bd(fee==null?"0":fee),NOW.minusSeconds(3600).plusSeconds(n),type==Type.OPENING_POSITION?"SYNTHETIC verified opening evidence":null,null),"record:"+n,HASHES.hash("record:"+n));return transactions.confirm(p.token());}
    PortfolioCommand command(String name){return new PortfolioCommand(name,portfolios,transactions,sync,HASHES);}
    @Test void flywayLedgerFifoAndLocalValuationWorkWithoutAnyBrokerCredentials(){
        assertThat(Flyway.configure().dataSource(POSTGRES.getJdbcUrl(),POSTGRES.getUsername(),POSTGRES.getPassword()).load().info().current().getVersion().getVersion()).isEqualTo("9");
        record(1,Type.DEPOSIT,null,null,"2000",null);record(2,Type.BUY,"10","100",null,"10");record(3,Type.SELL,"5","120",null,"5");var v=portfolios.value();
        assertThat(v.rubCashSubtotal()).isEqualByComparingTo("1585");assertThat(v.supportedEquityValue()).isEqualByComparingTo("600");assertThat(v.unrealizedPnL()).isEqualByComparingTo("95");assertThat(v.supportedAssetsPlusCash()).isEqualByComparingTo("2185");
        assertThat(jdbc.queryForObject("SELECT remaining_cost FROM acquisition_lots",BigDecimal.class)).isEqualByComparingTo("505");assertThat(jdbc.queryForObject("SELECT allocated_cost FROM lot_disposals",BigDecimal.class)).isEqualByComparingTo("505");verifyNoInteractions(metadata,quotes,broker);
    }
    @Test void duplicatePreparationAndConfirmationAreIdempotentAndTransactionHistoryIsImmutable(){
        var draft=new PortfolioTransactionService.Command(Type.DEPOSIT,null,null,null,bd("2000"),bd("0"),null,null,null);var first=transactions.prepare(draft,"same-update",HASHES.hash("same"));clock.at=NOW.plusSeconds(10);
        assertThat(transactions.prepare(draft,"same-update",HASHES.hash("same"))).isEqualTo(first);var saved=transactions.confirm(first.token());assertThat(transactions.confirm(first.token())).isEqualTo(saved);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM portfolio_transactions",Long.class)).isEqualTo(1);assertThatThrownBy(()->jdbc.update("UPDATE portfolio_transactions SET gross_amount=1 WHERE id=?",saved.id())).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(()->jdbc.update("DELETE FROM portfolio_transactions WHERE id=?",saved.id())).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(()->transactions.prepare(draft,"same-update",HASHES.hash("different"))).hasMessage("IDEMPOTENCY_CONFLICT");
    }
    @Test void competingConfirmationsProduceExactlyOneLedgerRecord()throws Exception{
        var p=transactions.prepare(new PortfolioTransactionService.Command(Type.DEPOSIT,null,null,null,bd("100"),bd("0"),null,null,null),"duplicate",HASHES.hash("duplicate"));
        try(var pool=Executors.newFixedThreadPool(2)){var gate=new CyclicBarrier(2);var outcomes=pool.invokeAll(List.<Callable<UUID>>of(()->{gate.await();return transactions.confirm(p.token()).id();},()->{gate.await();return transactions.confirm(p.token()).id();}));assertThat(outcomes.get(0).get()).isEqualTo(outcomes.get(1).get());}
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM portfolio_transactions",Long.class)).isEqualTo(1);
    }
    @Test void invalidBackdatedSaleAndInsufficientCashAreRejectedBeforeAnyLedgerWrite(){
        assertThatThrownBy(()->record(1,Type.WITHDRAWAL,null,null,"10",null)).hasMessage("INSUFFICIENT_CASH_AT_EXECUTION");record(1,Type.DEPOSIT,null,null,"2000",null);record(2,Type.BUY,"10","100",null,"10");
        assertThatThrownBy(()->record(0,Type.SELL,"1","100",null,"0")).hasMessage("SALE_EXCEEDS_AVAILABLE_SHARES");assertThatThrownBy(()->record(3,Type.SELL,"11","100",null,"0")).hasMessage("SALE_EXCEEDS_AVAILABLE_SHARES");assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM portfolio_transactions",Long.class)).isEqualTo(2);
    }
    @Test void projectionCanBeRebuiltIdenticallyAndAtomicFailureCannotLeaveAPartialLedger(){
        record(1,Type.DEPOSIT,null,null,"2000",null);record(2,Type.BUY,"10","100",null,"10");var account=portfolios.selected().orElseThrow().account().id();var first=transactions.rebuild(account);assertThat(transactions.rebuild(account)).isEqualTo(first);
        assertThatThrownBy(()->store.locked(account,a->{var t=new LedgerTransaction(id(90),account,Type.DEPOSIT,null,null,null,bd("500"),bd("0"),"RUB",NOW,NOW,PortfolioAccount.Source.MANUAL,null,"SYNTHETIC rollback","rollback",null,"CONFIRMED");a.append(t);throw new IllegalStateException("SYNTHETIC failure");})).hasMessage("SYNTHETIC failure");
        assertThat(store.transactions(account)).hasSize(2);assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM acquisition_lots",Long.class)).isEqualTo(1);
    }
    @Test void accountsKeepSeparateAcquisitionCostsAndPendingConfirmationRemainsBoundToOriginalAccount(){
        var first=portfolios.selected().orElseThrow().account();record(1,Type.DEPOSIT,null,null,"2000",null);var pending=transactions.prepare(new PortfolioTransactionService.Command(Type.BUY,UID,bd("5"),bd("100"),null,bd("0"),NOW,null,null),"pending-first",HASHES.hash("first"));
        var second=portfolios.createAccount("SYNTHETIC SECOND","second-account");portfolios.selectAccount(second.id());record(3,Type.DEPOSIT,null,null,"3000",null);record(4,Type.BUY,"5","200",null,"0");transactions.confirm(pending.token());
        var d=portfolios.position(UID);assertThat(d.positions()).hasSize(2);assertThat(d.remainingCost()).isEqualByComparingTo("1500");assertThat(store.transactions(first.id())).hasSize(2);assertThat(store.transactions(second.id())).hasSize(2);
    }
    @Test void explicitReversalRetainsOriginalAndExpiredConfirmationDoesNotWrite(){
        var original=record(1,Type.DEPOSIT,null,null,"100",null);var pending=transactions.prepare(new PortfolioTransactionService.Command(Type.REVERSAL,null,null,null,null,bd("0"),null,"SYNTHETIC correction",original.id()),"reverse",HASHES.hash("reverse"));transactions.confirm(pending.token());
        assertThat(store.transactions(original.accountId())).hasSize(2);assertThat(portfolios.value().rubCashSubtotal()).isZero();var expired=transactions.prepare(new PortfolioTransactionService.Command(Type.DEPOSIT,null,null,null,bd("1"),bd("0"),null,null,null),"expire",HASHES.hash("expire"));clock.at=NOW.plusSeconds(901);assertThatThrownBy(()->transactions.confirm(expired.token())).hasMessage("CONFIRMATION_EXPIRED");assertThat(store.transactions(original.accountId())).hasSize(2);
    }
    @Test void realTelegramDispatcherDeduplicatesUpdatesAndRequiresConfirmationBeforeCashChanges(){
        var sender=mock(TelegramMessageSender.class);var dispatcher=new TelegramCommandDispatcher(new TelegramAccessGuard(properties()),sender,new TelegramBotState(),List.<TelegramCommand>of(command("cash"),command("confirm"),command("portfolio")));
        var update=update("/cash deposit 2000");update.setUpdateId(100);dispatcher.dispatch(update);dispatcher.dispatch(update);assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM portfolio_transactions",Long.class)).isZero();assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM portfolio_requests",Long.class)).isEqualTo(1);
        var token=store.pendingByRequest("telegram:"+OWNER+":100").orElseThrow().token();var confirm=update("/confirm "+token);confirm.setUpdateId(101);dispatcher.dispatch(confirm);dispatcher.dispatch(confirm);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM portfolio_transactions",Long.class)).isEqualTo(1);assertThat(portfolios.value().rubCashSubtotal()).isEqualByComparingTo("2000");
    }
    PortfolioAccount linked(){
        when(broker.accounts()).thenAnswer(i->{assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();return List.of(new BrokerPortfolioProvider.BrokerAccount("synthetic-A","SYNTHETIC A","ACCOUNT_TYPE_TINKOFF","ACCOUNT_STATUS_OPEN",true,NOW.minus(Duration.ofDays(1))),new BrokerPortfolioProvider.BrokerAccount("synthetic-B","SYNTHETIC B","ACCOUNT_TYPE_TINKOFF","ACCOUNT_STATUS_OPEN",true,NOW.minus(Duration.ofDays(1))));});
        var discovered=sync.discover();var chosen=discovered.stream().filter(a->a.get("name").equals("SYNTHETIC B")).findFirst().orElseThrow();var account=sync.link((UUID)chosen.get("id"),NOW.minus(Duration.ofDays(1)),"SYNTHETIC LINKED");portfolios.selectAccount(account.id());
        when(broker.operations(eq("synthetic-B"),any(),any(),eq(""),eq(100))).thenAnswer(i->{assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();return new BrokerPortfolioProvider.Page(List.of(cashOp("fund","OPERATION_TYPE_INPUT","2000",1)),true,"page-2");});
        when(broker.operations(eq("synthetic-B"),any(),any(),eq("page-2"),eq(100))).thenAnswer(i->{assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();return new BrokerPortfolioProvider.Page(List.of(buy("buy","100","10")),false,"");});
        when(broker.snapshot("synthetic-B")).thenAnswer(i->{assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();return brokerSnapshot("10","990");});return account;
    }
    @Test void explicitAccountSelectionPaginatedImportAndOverlapAreIdempotent(){var account=linked();var first=sync.synchronize();assertThat(first.status()).isEqualTo("SUCCESS");assertThat(first.report().created()).isEqualTo(2);assertThat(store.transactions(account.id())).hasSize(2);
        clock.at=NOW.plusSeconds(61);var second=sync.synchronize();assertThat(second.report().created()).isZero();assertThat(second.report().unchanged()).isEqualTo(2);assertThat(store.transactions(account.id())).hasSize(2);assertThat(store.syncState(account.id()).checkpoint()).isEqualTo(clock.at);verify(broker,never()).snapshot("synthetic-A");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM broker_operation_versions",Long.class)).isEqualTo(2);assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM broker_reconciliation_results",Long.class)).isEqualTo(2);
    }
    @Test void updatedProviderOperationUsesAuditedReversalReplacementIncludingReversion(){var account=linked();sync.synchronize();clock.at=NOW.plusSeconds(61);
        when(broker.operations(eq("synthetic-B"),any(),any(),eq("page-2"),eq(100))).thenReturn(new BrokerPortfolioProvider.Page(List.of(buy("buy","110","10")),false,""));when(broker.snapshot("synthetic-B")).thenReturn(brokerSnapshot("10","890"));
        var corrected=sync.synchronize();assertThat(corrected.report().corrected()).isEqualTo(1);assertThat(store.transactions(account.id())).hasSize(4);assertThat(jdbc.queryForObject("SELECT original_cost FROM acquisition_lots",BigDecimal.class)).isEqualByComparingTo("1110");
        clock.at=NOW.plusSeconds(122);when(broker.operations(eq("synthetic-B"),any(),any(),eq("page-2"),eq(100))).thenReturn(new BrokerPortfolioProvider.Page(List.of(buy("buy","100","10")),false,""));when(broker.snapshot("synthetic-B")).thenReturn(brokerSnapshot("10","990"));
        assertThat(sync.synchronize().report().corrected()).isEqualTo(1);assertThat(store.transactions(account.id())).hasSize(6);assertThat(jdbc.queryForObject("SELECT original_cost FROM acquisition_lots",BigDecimal.class)).isEqualByComparingTo("1010");
    }
    @Test void interruptedPaginationDoesNotAdvanceCheckpointOrCommitUnfinishedHistory(){var account=linked();when(broker.operations(eq("synthetic-B"),any(),any(),eq("page-2"),eq(100))).thenThrow(new MarketException(MarketException.Reason.TIMEOUT));
        assertThat(sync.synchronize().status()).isEqualTo("FAILED_PREVIOUS_PRESERVED");assertThat(store.transactions(account.id())).isEmpty();assertThat(store.syncState(account.id()).checkpoint()).isNull();assertThat(store.syncState(account.id()).lastError()).isEqualTo("TIMEOUT");
        clock.at=NOW.plusSeconds(61);when(broker.operations(eq("synthetic-B"),any(),any(),eq("page-2"),eq(100))).thenReturn(new BrokerPortfolioProvider.Page(List.of(buy("buy","100","10")),false,""));assertThat(sync.synchronize().report().created()).isEqualTo(2);
    }
    @Test void unsupportedCorporateActionsPreserveSourceAndSuppressMisleadingPnLWithoutAdjustments(){var account=linked();when(broker.operations(eq("synthetic-B"),any(),any(),eq("page-2"),eq(100))).thenReturn(new BrokerPortfolioProvider.Page(List.of(buy("buy","100","10"),cashOp("split","OPERATION_TYPE_INPUT_SECURITIES","1",100)),false,""));when(broker.snapshot("synthetic-B")).thenReturn(brokerSnapshot("100","990"));
        var result=sync.synchronize();assertThat(result.status()).isEqualTo("PARTIAL");assertThat(result.report().unsupported()).isEqualTo(1);assertThat(store.transactions(account.id())).hasSize(2);assertThat(portfolios.value().unrealizedPnL()).isNull();assertThat(store.reconciliation(account.id()).orElseThrow().rows().getFirst().status()).isEqualTo("QUANTITY_MISMATCH");
        assertThat(jdbc.queryForObject("SELECT evidence::text FROM broker_source_operations WHERE external_operation_id='split'",String.class)).contains("OPERATION_TYPE_INPUT_SECURITIES");
    }
    @Test void stableProviderExecutionUnderAnotherIdNeverCreatesASecondPurchase(){var account=linked();sync.synchronize();clock.at=NOW.plusSeconds(61);when(broker.operations(eq("synthetic-B"),any(),any(),eq("page-2"),eq(100))).thenReturn(new BrokerPortfolioProvider.Page(List.of(buy("new-id","100","10")),false,""));
        var result=sync.synchronize();assertThat(result.report().created()).isZero();assertThat(store.transactions(account.id())).hasSize(2);assertThat(jdbc.queryForObject("SELECT mapping_status FROM broker_source_operations WHERE external_operation_id='new-id'",String.class)).isEqualTo("ALIAS");
    }
    @Test void brokerAccountRejectsManualOverlapAndConcurrentSynchronization(){var account=linked();assertThatThrownBy(()->record(1,Type.DEPOSIT,null,null,"100",null)).hasMessage("BROKER_ACCOUNT_MANUAL_OVERLAP_FORBIDDEN");
        assertThat(store.startSync(account.id(),NOW,Duration.ofSeconds(60))).isPresent();assertThat(sync.synchronize().status()).isEqualTo("COOLDOWN_OR_RUNNING");verify(broker,never()).operations(any(),any(),any(),any(),anyInt());
    }
    @Test void reconciliationDoesNotFetchBrokerOrChangeTransactions(){var account=linked();sync.synchronize();var before=store.transactions(account.id());clearInvocations(broker);var result=sync.reconcile();assertThat(result.rows()).allSatisfy(r->assertThat(r.status()).isEqualTo("MATCHED"));assertThat(store.transactions(account.id())).isEqualTo(before);verifyNoInteractions(broker);}
    @Test void fullAccessAccountAndExpiredDiscoveryCannotBeLinked(){when(broker.accounts()).thenReturn(List.of(new BrokerPortfolioProvider.BrokerAccount("synthetic-full","SYNTHETIC FULL","ACCOUNT_TYPE_TINKOFF","ACCOUNT_STATUS_OPEN",false,NOW.minusSeconds(86400))));
        var candidate=(UUID)sync.discover().getFirst().get("id");assertThatThrownBy(()->sync.link(candidate,NOW.minusSeconds(3600),"SYNTHETIC")).hasMessage("BROKER_ACCOUNT_NOT_READ_ONLY_OR_SUPPORTED");
        when(broker.accounts()).thenReturn(List.of(new BrokerPortfolioProvider.BrokerAccount("synthetic-readonly","SYNTHETIC READONLY","ACCOUNT_TYPE_TINKOFF","ACCOUNT_STATUS_OPEN",true,NOW.minusSeconds(86400))));var expired=(UUID)sync.discover().stream().filter(c->c.get("name").equals("SYNTHETIC READONLY")).findFirst().orElseThrow().get("id");
        clock.at=NOW.plusSeconds(901);assertThatThrownBy(()->sync.link(expired,NOW.minusSeconds(3600),"SYNTHETIC")).hasMessage("ACCOUNT_DISCOVERY_EXPIRED");assertThat(portfolios.accounts()).hasSize(1);
    }
    @Test void missingBrokerAcquisitionHistoryRemainsUnknownUntilAnExplicitOwnerOpeningRecord(){var account=linked();jdbc.update("UPDATE portfolio_accounts SET history_from=? WHERE id=?",NOW.minusSeconds(7200).atOffset(ZoneOffset.UTC),account.id());
        var sell=new BrokerPortfolioProvider.Operation("sell",null,"OPERATION_TYPE_SELL","OPERATION_STATE_EXECUTED",UID,"share",NOW.minusSeconds(3000),bd("600"),"RUB",bd("120"),bd("5"),bd("5"),List.of(new BrokerPortfolioProvider.Execution("synthetic-sale",NOW.minusSeconds(3000),bd("5"),bd("120"),"RUB")),"SYNTHETIC SELL WITHOUT AVAILABLE ACQUISITION HISTORY");
        when(broker.operations(eq("synthetic-B"),any(),any(),eq(""),eq(100))).thenReturn(new BrokerPortfolioProvider.Page(List.of(sell),false,""));when(broker.snapshot("synthetic-B")).thenReturn(brokerSnapshot("5","595"));
        assertThat(sync.synchronize().status()).isEqualTo("PARTIAL");assertThat(store.transactions(account.id())).hasSize(1);assertThat(portfolios.value().unrealizedPnL()).isNull();assertThat(jdbc.queryForObject("SELECT quantity FROM portfolio_position_projections",BigDecimal.class)).isNull();
        var p=transactions.prepare(new PortfolioTransactionService.Command(Type.OPENING_POSITION,UID,bd("10"),null,null,bd("0"),NOW.minusSeconds(10800),"SYNTHETIC OWNER CONFIRMED starting shares; historical cost unknown",null),"opening-history",HASHES.hash("opening-history"));transactions.confirm(p.token());
        assertThat(store.transactions(account.id())).hasSize(2);assertThat(jdbc.queryForObject("SELECT quantity FROM portfolio_position_projections",BigDecimal.class)).isEqualByComparingTo("5");assertThat(jdbc.queryForObject("SELECT remaining_cost FROM portfolio_position_projections",BigDecimal.class)).isNull();assertThat(sync.reconcile().warnings()).contains("INCOMPLETE_HISTORY");
    }
    @Test void unsupportedForeignAssetsAndCashArePreservedButNeverConvertedIntoRubTotals(){var account=linked();var foreign=new BrokerPortfolioProvider.Operation("foreign",null,"OPERATION_TYPE_BUY","OPERATION_STATE_EXECUTED",UID2,"bond",NOW.minusSeconds(3000),bd("-100"),"USD",bd("100"),bd("0"),bd("1"),List.of(),"SYNTHETIC FOREIGN BOND USD");
        when(broker.operations(eq("synthetic-B"),any(),any(),eq(""),eq(100))).thenReturn(new BrokerPortfolioProvider.Page(List.of(foreign),false,""));when(broker.snapshot("synthetic-B")).thenReturn(new BrokerPortfolioProvider.Snapshot(NOW,List.of(new BrokerPortfolioProvider.Holding(UID2,"bond","USD",bd("1"),bd("0"),bd("1"),"SYNTHETIC")),Map.of("USD",bd("100")),Map.of(),false,"SYNTHETIC FOREIGN ACCOUNT"));
        var result=sync.synchronize();assertThat(result.status()).isEqualTo("PARTIAL");assertThat(store.transactions(account.id())).isEmpty();assertThat(store.brokerSnapshot(account.id()).orElseThrow().cash()).containsEntry("USD",bd("100"));assertThat(portfolios.value().supportedAssetsPlusCash()).isNull();assertThat(portfolios.value().unrealizedPnL()).isNull();assertThat(result.report().reconciliation().rows()).extracting(r->r.status()).contains("UNSUPPORTED_INSTRUMENT","CASH_DEFINITION_OR_CURRENCY_UNSUPPORTED");
    }
    @Test void brokerCursorLoopFailsWithoutCommittingTheFetchedPrefix(){var account=linked();when(broker.operations(eq("synthetic-B"),any(),any(),eq("page-2"),eq(100))).thenReturn(new BrokerPortfolioProvider.Page(List.of(),true,"page-2"));
        assertThat(sync.synchronize().reason()).isEqualTo("BROKER_CURSOR_LOOP");assertThat(store.transactions(account.id())).isEmpty();assertThat(store.syncState(account.id()).checkpoint()).isNull();
    }
    @Test void linkingAlongsideManualHistoryRequiresExplicitReviewAndPreservesThatReview(){record(1,Type.DEPOSIT,null,null,"100",null);
        when(broker.accounts()).thenReturn(List.of(new BrokerPortfolioProvider.BrokerAccount("synthetic-distinct","SYNTHETIC DISTINCT BROKER","ACCOUNT_TYPE_TINKOFF","ACCOUNT_STATUS_OPEN",true,NOW.minusSeconds(86400))));
        var candidate=(UUID)sync.discover().getFirst().get("id");assertThatThrownBy(()->sync.link(candidate,NOW.minusSeconds(86400),"SYNTHETIC LINK")).hasMessage("MANUAL_HISTORY_REVIEW_REQUIRED");
        var linked=sync.link(candidate,NOW.minusSeconds(86400),"SYNTHETIC DISTINCT ACCOUNT REVIEWED",true);assertThat(jdbc.queryForObject("SELECT manual_overlap_reviewed_at IS NOT NULL FROM portfolio_accounts WHERE id=?",Boolean.class,linked.id())).isTrue();
        assertThat(portfolios.accounts()).hasSize(2);assertThat(portfolios.transactions(10)).hasSize(1);
    }
}
