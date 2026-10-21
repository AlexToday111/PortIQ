package ru.ba6kir.tradelab.portfolio.domain.valuation;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import ru.ba6kir.tradelab.market.domain.price.MarketQuote;
import ru.ba6kir.tradelab.portfolio.domain.accounting.AccountingResult;

public record PortfolioValuation(UUID portfolioId,String name,Instant valuedAt,List<AccountValue> accounts,
        BigDecimal supportedEquityValue,BigDecimal rubCashSubtotal,BigDecimal supportedAssetsPlusCash,
        BigDecimal realizedPnL,BigDecimal unrealizedPnL,BigDecimal dividends,BigDecimal fees,BigDecimal taxes,boolean complete,List<String> warnings) {
    public record PositionValue(UUID accountId,String accountName,String ticker,AccountingResult.Position position,MarketQuote quote,
            BigDecimal marketValue,BigDecimal unrealizedPnL,BigDecimal unrealizedPercent,String quoteStatus,List<String> warnings) {public PositionValue{warnings=List.copyOf(warnings);}}
    public record AccountValue(UUID accountId,String name,AccountingResult accounting,List<PositionValue> positions,boolean cashReliable,boolean complete,List<String> warnings) {public AccountValue{positions=List.copyOf(positions);warnings=List.copyOf(warnings);}}
    public PortfolioValuation{accounts=List.copyOf(accounts);warnings=List.copyOf(warnings);}
}
