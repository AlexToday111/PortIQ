package ru.ba6kir.tradelab.portfolio;

import java.time.Duration;
import java.util.*;
import java.util.function.Supplier;
import org.junit.jupiter.api.*;
import ru.ba6kir.tradelab.market.application.*;
import ru.ba6kir.tradelab.market.application.price.MarketPriceService;
import ru.ba6kir.tradelab.market.domain.price.MarketQuote;
import ru.ba6kir.tradelab.portfolio.application.port.PortfolioStore;
import ru.ba6kir.tradelab.portfolio.application.service.PortfolioValuationService;
import ru.ba6kir.tradelab.portfolio.domain.model.*;
import ru.ba6kir.tradelab.portfolio.domain.reconciliation.PortfolioReconciliationEngine;
import static ru.ba6kir.tradelab.portfolio.PortfolioTestData.*;
import static ru.ba6kir.tradelab.portfolio.domain.model.LedgerTransaction.Type;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class PortfolioValuationTest {
    PortfolioStore store;InstrumentService instruments;MarketPriceService prices;PortfolioValuationService service;
    @BeforeEach void setup(){store=mock(PortfolioStore.class);instruments=mock(InstrumentService.class);prices=mock(MarketPriceService.class);
        when(store.selected()).thenReturn(Optional.of(new PortfolioStore.Selection(portfolio(),account())));when(store.accounts(PORTFOLIO)).thenReturn(List.of(account()));when(store.transactions(ACCOUNT)).thenReturn(example());
        when(store.readConsistently(any())).thenAnswer(i->((Supplier<?>)i.getArgument(0)).get());when(store.lastSync(ACCOUNT)).thenReturn(Optional.empty());when(store.reconciliation(ACCOUNT)).thenReturn(Optional.empty());
        when(instruments.getCached(UID)).thenReturn(Optional.of(instrument()));when(prices.cachedQuote(UID)).thenReturn(Optional.of(quote()));service=new PortfolioValuationService(store,instruments,prices,CLOCK,Duration.ofDays(3));}
    @Test void syntheticFixtureValuesRemainingSharesAt600With95UnrealizedAnd2185SupportedValue(){var value=service.value();assertThat(value.supportedEquityValue()).isEqualByComparingTo("600");assertThat(value.unrealizedPnL()).isEqualByComparingTo("95");assertThat(value.supportedAssetsPlusCash()).isEqualByComparingTo("2185");assertThat(value.realizedPnL()).isEqualByComparingTo("90");assertThat(value.complete()).isTrue();verify(prices,never()).lastPrice(any());verify(instruments,never()).resolve(any());}
    @Test void unavailableQuoteIsNotAZeroPriceAndCostRemainsVisible(){when(prices.cachedQuote(UID)).thenReturn(Optional.empty());var v=service.value();var p=v.accounts().getFirst().positions().getFirst();assertThat(p.marketValue()).isNull();assertThat(p.position().remainingCost()).isEqualByComparingTo("505");assertThat(v.complete()).isFalse();assertThat(v.supportedAssetsPlusCash()).isNull();assertThat(v.unrealizedPnL()).isNull();}
    @Test void staleAndForeignCurrencyQuotesAreNotUsedForRubValuation(){when(prices.cachedQuote(UID)).thenReturn(Optional.of(new MarketQuote(UID,bd("120"),"RUB",NOW.minus(Duration.ofDays(4)),NOW,"SYNTHETIC")));assertThat(service.value().warnings()).contains("STALE_PRICE");
        when(prices.cachedQuote(UID)).thenReturn(Optional.of(new MarketQuote(UID,bd("120"),"USD",NOW,NOW,"SYNTHETIC")));assertThat(service.value().accounts().getFirst().positions().getFirst().marketValue()).isNull();assertThat(service.value().warnings()).contains("UNSUPPORTED_OR_MISMATCHED_CURRENCY");}
    @Test void missingAcquisitionCostAllowsDatedMarketValueButNeverInventsProfit(){when(store.transactions(ACCOUNT)).thenReturn(List.of(tx(1,Type.OPENING_POSITION,"5",null,null,null)));var v=service.value();var p=v.accounts().getFirst().positions().getFirst();assertThat(p.marketValue()).isEqualByComparingTo("600");assertThat(p.position().remainingCost()).isNull();assertThat(p.unrealizedPnL()).isNull();assertThat(v.complete()).isFalse();}
    @Test void closedPositionsRemainHistoricalAndNeedNoCurrentPriceToValueZero(){var values=new ArrayList<>(example());values.add(tx(4,Type.SELL,"5","120",null,"5"));when(store.transactions(ACCOUNT)).thenReturn(values);when(prices.cachedQuote(UID)).thenReturn(Optional.empty());var p=service.value().accounts().getFirst().positions().getFirst();assertThat(p.position().quantity()).isZero();assertThat(p.marketValue()).isZero();assertThat(p.unrealizedPnL()).isZero();}
    @Test void reconciliationPreservesMismatchesAndUnsupportedCorporateActionEvidenceWithoutGeneratingTrades(){var accounting=new ru.ba6kir.tradelab.portfolio.domain.accounting.FifoAccountingEngine().rebuild(ACCOUNT,example(),true,NOW);var match=new PortfolioReconciliationEngine().reconcile(accounting,brokerSnapshot("5","1585"),true,NOW);assertThat(match.rows()).allSatisfy(r->assertThat(r.status()).isEqualTo("MATCHED"));
        var mismatch=new PortfolioReconciliationEngine().reconcile(accounting,brokerSnapshot("50","1585"),false,NOW);assertThat(mismatch.rows().getFirst().status()).isEqualTo("QUANTITY_MISMATCH");assertThat(mismatch.rows().getFirst().difference()).isEqualByComparingTo("-45");assertThat(mismatch.warnings()).contains("INCOMPLETE_HISTORY");assertThat(accounting.positions().getFirst().quantity()).isEqualByComparingTo("5");}
    @Test void missingPositionsAndBlockedCashHaveDistinctReconciliationSemantics(){var r=new ru.ba6kir.tradelab.portfolio.domain.accounting.FifoAccountingEngine().rebuild(ACCOUNT,List.of(),true,NOW);var snapshot=brokerSnapshot("5","100");var blocked=new ru.ba6kir.tradelab.portfolio.application.port.BrokerPortfolioProvider.Snapshot(NOW,snapshot.holdings(),snapshot.cash(),Map.of("RUB",bd("10")),false,"SYNTHETIC");
        var result=new PortfolioReconciliationEngine().reconcile(r,blocked,false,NOW);assertThat(result.rows()).extracting(row->row.status()).contains("MISSING_INTERNAL_POSITION","CASH_DEFINITION_OR_CURRENCY_UNSUPPORTED");}
}
