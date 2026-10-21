package ru.ba6kir.tradelab.portfolio;

import java.math.BigDecimal;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import ru.ba6kir.tradelab.portfolio.domain.accounting.*;
import ru.ba6kir.tradelab.portfolio.domain.model.*;
import static org.assertj.core.api.Assertions.*;
import static ru.ba6kir.tradelab.portfolio.PortfolioTestData.*;
import static ru.ba6kir.tradelab.portfolio.domain.model.LedgerTransaction.Type;

class FifoAccountingTest {
    AccountingResult calculate(List<LedgerTransaction> history){return new FifoAccountingEngine().rebuild(ACCOUNT,history,true,NOW);}
    @Test void specifiedSyntheticExampleConservesCashAndCostWithoutDoubleCountingCommissions(){var r=calculate(example());
        assertThat(r.lots().getFirst().originalCost()).isEqualByComparingTo("1010");assertThat(r.positions().getFirst().quantity()).isEqualByComparingTo("5");
        assertThat(r.positions().getFirst().remainingCost()).isEqualByComparingTo("505");assertThat(r.positions().getFirst().averageCost()).isEqualByComparingTo("101");
        assertThat(r.realizedPnL().get("RUB")).isEqualByComparingTo("90");assertThat(r.cash().get("RUB")).isEqualByComparingTo("1585");assertThat(r.fees().get("RUB")).isEqualByComparingTo("15");}
    @Test void saleAcrossSeveralLotsConsumesEarliestCostsAndPurchaseFees(){var r=calculate(List.of(tx(1,Type.DEPOSIT,null,null,"10000",null),tx(2,Type.BUY,"10","100",null,"10"),tx(3,Type.BUY,"20","200",null,"20"),tx(4,Type.SELL,"15","250",null,"15")));
        assertThat(r.disposals()).hasSize(2);assertThat(r.disposals().get(0).allocatedCost()).isEqualByComparingTo("1010");assertThat(r.disposals().get(1).allocatedCost()).isEqualByComparingTo("1005");
        assertThat(r.positions().getFirst().remainingCost()).isEqualByComparingTo("3015");assertThat(r.realizedPnL().get("RUB")).isEqualByComparingTo("1720");}
    @Test void sellCommissionAllocationAcrossFifoLotsIsTraceableWithoutAnotherPnlDeduction(){var r=calculate(List.of(tx(1,Type.DEPOSIT,null,null,"10000",null),tx(2,Type.BUY,"10","100",null,"0"),tx(3,Type.BUY,"10","200",null,"0"),tx(4,Type.SELL,"15","250",null,"15")));
        assertThat(r.disposals()).extracting(AccountingResult.Disposal::allocatedSaleCommission).containsExactly(bd("10.000000000000000000"),bd("5.000000000000000000"));assertThat(r.realizedPnL().get("RUB")).isEqualByComparingTo("1735");}
    @Test void fullLiquidationKeepsHistoricalZeroPositionAndConsumesResidualExactly(){var history=new ArrayList<>(example());history.add(tx(4,Type.SELL,"5","130",null,"5"));var r=calculate(history);
        assertThat(r.positions().getFirst().quantity()).isEqualByComparingTo("0");assertThat(r.positions().getFirst().remainingCost()).isEqualByComparingTo("0");assertThat(r.lots().getFirst().remainingQuantity()).isZero();assertThat(r.realizedPnL().get("RUB")).isEqualByComparingTo("230");}
    @Test void partialCommissionAllocationsConserveTheOriginalLotCostAfterRepeatedDisposals(){var r=calculate(List.of(tx(1,Type.DEPOSIT,null,null,"1000",null),tx(2,Type.BUY,"3","10",null,"1"),tx(3,Type.SELL,"1","20",null,"0"),tx(4,Type.SELL,"1","20",null,"0"),tx(5,Type.SELL,"1","20",null,"0")));
        assertThat(r.disposals().stream().map(AccountingResult.Disposal::allocatedCost).reduce(BigDecimal.ZERO,BigDecimal::add)).isEqualByComparingTo("31");assertThat(r.lots().getFirst().remainingCost()).isZero();assertThat(r.realizedPnL().get("RUB")).isEqualByComparingTo("29");}
    @ParameterizedTest @ValueSource(strings={"11","100"}) void oversellingNeverCreatesAShortPosition(String shares){assertThatThrownBy(()->calculate(List.of(example().get(0),example().get(1),tx(3,Type.SELL,shares,"120",null,"0")))).hasMessage("SALE_EXCEEDS_AVAILABLE_SHARES");}
    @Test void historicalTransactionsAreSortedAndSaleBeforeAcquisitionIsRejected(){var values=new ArrayList<>(example());Collections.reverse(values);assertThat(calculate(values)).isEqualTo(calculate(example()));
        assertThatThrownBy(()->calculate(List.of(tx(0,Type.SELL,"1","100",null,"0"),tx(2,Type.BUY,"10","100",null,"0")))).hasMessage("SALE_EXCEEDS_AVAILABLE_SHARES");}
    @Test void depositAndWithdrawalAreNotInvestmentProfit(){var r=calculate(List.of(tx(1,Type.DEPOSIT,null,null,"10000",null),tx(2,Type.WITHDRAWAL,null,null,"5000",null)));assertThat(r.cash().get("RUB")).isEqualByComparingTo("5000");assertThat(r.realizedPnL()).isEmpty();assertThat(r.positions()).isEmpty();}
    @Test void actualDividendTaxAndStandaloneFeeAreDistinctCashFlows(){var r=calculate(List.of(tx(1,Type.DEPOSIT,null,null,"2000",null),tx(2,Type.DIVIDEND_RECEIVED,null,null,"350",null),tx(3,Type.TAX_WITHHELD,null,null,"50",null),tx(4,Type.BROKER_FEE,null,null,"2",null)));
        assertThat(r.cash().get("RUB")).isEqualByComparingTo("2298");assertThat(r.dividends().get("RUB")).isEqualByComparingTo("350");assertThat(r.taxes().get("RUB")).isEqualByComparingTo("50");assertThat(r.fees().get("RUB")).isEqualByComparingTo("2");assertThat(r.realizedPnL()).isEmpty();}
    @Test void unknownOpeningCostDoesNotUseCurrentPriceOrInventRealizedProfit(){var r=calculate(List.of(tx(1,Type.OPENING_POSITION,"10",null,null,null),tx(2,Type.SELL,"5","120",null,"0")));
        assertThat(r.positions().getFirst().quantity()).isEqualByComparingTo("5");assertThat(r.positions().getFirst().remainingCost()).isNull();assertThat(r.realizedPnL()).containsKey("RUB");assertThat(r.realizedPnL().get("RUB")).isNull();}
    @Test void incompleteBrokerSalesPreserveCashAndMarkInventoryUnknownWithoutInventingOpeningShares(){var r=new FifoAccountingEngine().rebuild(ACCOUNT,List.of(tx(1,Type.SELL,"5","120",null,"5")),false,NOW);
        assertThat(r.positions().getFirst().quantity()).isNull();assertThat(r.cash().get("RUB")).isEqualByComparingTo("595");assertThat(r.realizedPnL().get("RUB")).isNull();assertThat(r.issues()).contains("INCOMPLETE_INVENTORY:"+UID);assertThat(r.lots()).isEmpty();}
    @Test void explicitReversalRebuildsHistoryInsteadOfMutatingOrManufacturingAnOppositeTrade(){var original=example().get(2);var reversal=new LedgerTransaction(id(99),ACCOUNT,Type.REVERSAL,null,null,null,null,BigDecimal.ZERO,"RUB",NOW,NOW,PortfolioAccount.Source.MANUAL,null,"SYNTHETIC explicit correction","reverse-test",original.id(),"CONFIRMED");var values=new ArrayList<>(example());values.add(reversal);var r=calculate(values);
        assertThat(r.positions().getFirst().quantity()).isEqualByComparingTo("10");assertThat(r.cash().get("RUB")).isEqualByComparingTo("990");assertThat(r.realizedPnL()).isEmpty();assertThat(r.disposals()).isEmpty();}
    @Test void duplicateLedgerIdentityAndImpossibleCashAreRejected(){assertThatThrownBy(()->calculate(List.of(example().getFirst(),example().getFirst()))).hasMessage("DUPLICATE_OR_CROSS_ACCOUNT_TRANSACTION");assertThatThrownBy(()->calculate(List.of(tx(1,Type.WITHDRAWAL,null,null,"10",null)))).hasMessage("INSUFFICIENT_CASH_AT_EXECUTION");}
    @Test void fractionalSharesRemainSharesAndAreNotMultipliedByLotSize(){var r=calculate(List.of(tx(1,Type.DEPOSIT,null,null,"1000",null),tx(2,Type.BUY,"0.125","100",null,"0.1")));assertThat(r.positions().getFirst().quantity()).isEqualByComparingTo("0.125");assertThat(r.positions().getFirst().remainingCost()).isEqualByComparingTo("12.6");}
}
