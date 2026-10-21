package ru.ba6kir.tradelab.portfolio;

import java.util.*;
import org.junit.jupiter.api.Test;
import ru.ba6kir.tradelab.portfolio.application.port.BrokerPortfolioProvider.*;
import ru.ba6kir.tradelab.portfolio.application.service.BrokerOperationMapper;
import static ru.ba6kir.tradelab.portfolio.PortfolioTestData.*;
import static org.assertj.core.api.Assertions.*;

class BrokerOperationMapperTest {
    final BrokerOperationMapper mapper=new BrokerOperationMapper(HASHES);
    @Test void embeddedAndRelatedCommissionAreNotCountedTwice(){var buy=buy("b","100","10");var fee=new Operation("f","b","OPERATION_TYPE_BROKER_FEE","OPERATION_STATE_EXECUTED",null,null,NOW.minusSeconds(3000),bd("-10"),"RUB",null,null,bd("0"),List.of(),"SYNTHETIC related commission");var plan=mapper.map(List.of(buy,fee));
        assertThat(plan.desired()).hasSize(1);assertThat(plan.desired().values().iterator().next().commission()).isEqualByComparingTo("10");assertThat(plan.statuses().get("f")).isEqualTo("INCLUDED_IN_TRADE");}
    @Test void relatedCommissionCanSupplyMissingEmbeddedFeeWithoutCreatingStandaloneDuplicate(){var buy=buy("b","100",null);var fee=new Operation("f","b","OPERATION_TYPE_BROKER_FEE","OPERATION_STATE_EXECUTED",null,null,NOW.minusSeconds(3000),bd("-10"),"RUB",null,null,bd("0"),List.of(),"SYNTHETIC");assertThat(mapper.map(List.of(buy,fee)).desired().values().iterator().next().commission()).isEqualByComparingTo("10");assertThat(mapper.map(List.of(buy)).reasons().get("b")).isEqualTo("MISSING_COMMISSION_EVIDENCE");}
    @Test void partialExecutionsUseActualExecutedSharesAndSplitAttributedFeesExactly(){var op=new Operation("b",null,"OPERATION_TYPE_BUY","OPERATION_STATE_EXECUTED",UID,"share",NOW.minusSeconds(3000),bd("-1010"),"RUB",bd("101"),bd("1"),bd("10"),
                List.of(new Execution("e1",NOW.minusSeconds(3000),bd("4"),bd("100"),"RUB"),new Execution("e2",NOW.minusSeconds(2900),bd("6"),bd("101.666666666666666666"),"RUB")),"SYNTHETIC");
        // Unequal-price executions must reconcile exactly to the reported gross amount; no rounding guess.
        assertThat(mapper.map(List.of(op)).reasons().get("b")).isEqualTo("TRADE_PAYMENT_COMPONENTS_MISMATCH");
        op=new Operation("b",null,"OPERATION_TYPE_BUY","OPERATION_STATE_EXECUTED",UID,"share",NOW.minusSeconds(3000),bd("-1000"),"RUB",bd("100"),bd("1"),bd("10"),List.of(new Execution("e1",NOW.minusSeconds(3000),bd("4"),bd("100"),"RUB"),new Execution("e2",NOW.minusSeconds(2900),bd("6"),bd("100"),"RUB")),"SYNTHETIC");
        var plan=mapper.map(List.of(op));assertThat(plan.desired()).hasSize(2);assertThat(plan.desired().values().stream().map(BrokerOperationMapper.Spec::commission).reduce(bd("0"),java.math.BigDecimal::add)).isEqualByComparingTo("1");assertThat(plan.desired().values().stream().map(BrokerOperationMapper.Spec::quantity).reduce(bd("0"),java.math.BigDecimal::add)).isEqualByComparingTo("10");}
    @Test void stableExecutionAliasesCannotCreateDuplicateTradesAndConflictingAliasesRequireReview(){var a=buy("a","100","10");var b=buy("b","100","10");var plan=mapper.map(List.of(a,b));assertThat(plan.desired()).hasSize(1);assertThat(plan.statuses().get("b")).isEqualTo("ALIAS");
        var conflict=mapper.map(List.of(a,buy("b","101","10")));assertThat(conflict.desired()).isEmpty();assertThat(conflict.reasons().values()).contains("CHANGED_OPERATION_ID_REQUIRES_RECONCILIATION");}
    @Test void indistinguishableCashIdsArePreservedForReviewAndNeverBlindlyDoubled(){var a=cashOp("a","OPERATION_TYPE_INPUT","100",1);var b=cashOp("b","OPERATION_TYPE_INPUT","100",1);var plan=mapper.map(List.of(a,b));assertThat(plan.desired()).hasSize(1);assertThat(plan.reasons().get("b")).isEqualTo("POSSIBLE_CHANGED_CASH_OPERATION_ID");}
    @Test void unsupportedCorporateActionPendingAndForeignCurrencyAreNotInventedIntoTrades(){var split=cashOp("split","OPERATION_TYPE_INPUT_SECURITIES","100",1);assertThat(mapper.map(List.of(split)).desired()).isEmpty();assertThat(mapper.map(List.of(split)).reasons().get("split")).contains("UNSUPPORTED_OPERATION_TYPE");
        var base=buy("b","100","0");var pending=new Operation(base.externalId(),null,base.type(),"OPERATION_STATE_PROGRESS",UID,"share",base.executedAt(),base.payment(),"RUB",base.price(),base.commission(),base.quantityDone(),base.executions(),"SYNTHETIC pending");assertThat(mapper.map(List.of(pending)).desired()).isEmpty();assertThat(mapper.map(List.of(pending)).reasons().get("b")).contains("UNFINALIZED");}
}
