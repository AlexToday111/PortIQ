package ru.ba6kir.tradelab.analysis.domain.metric;

public enum AnalysisMetric {
    MARKET_CAPITALIZATION(Family.VALUATION,Unit.MONEY), PE(Family.VALUATION,Unit.MULTIPLE),PB(Family.VALUATION,Unit.MULTIPLE),
    PS(Family.VALUATION,Unit.MULTIPLE),EV_EBITDA(Family.VALUATION,Unit.MULTIPLE),EARNINGS_YIELD(Family.VALUATION,Unit.PERCENT),
    ROE(Family.PROFITABILITY,Unit.PERCENT),ROA(Family.PROFITABILITY,Unit.PERCENT),ROIC(Family.PROFITABILITY,Unit.PERCENT),
    NET_MARGIN(Family.PROFITABILITY,Unit.PERCENT),EBITDA_MARGIN(Family.PROFITABILITY,Unit.PERCENT),
    TOTAL_DEBT(Family.STABILITY,Unit.MONEY),CASH(Family.STABILITY,Unit.MONEY),NET_DEBT(Family.STABILITY,Unit.MONEY),
    DEBT_EQUITY(Family.STABILITY,Unit.RATIO),DEBT_EBITDA(Family.STABILITY,Unit.MULTIPLE),NET_DEBT_EBITDA(Family.STABILITY,Unit.MULTIPLE),
    CURRENT_RATIO(Family.STABILITY,Unit.RATIO),INTEREST_COVERAGE(Family.STABILITY,Unit.RATIO),FIXED_CHARGE_COVERAGE(Family.STABILITY,Unit.RATIO),
    REVENUE(Family.GROWTH,Unit.MONEY),NET_INCOME(Family.GROWTH,Unit.MONEY),EBITDA(Family.GROWTH,Unit.MONEY),FREE_CASH_FLOW(Family.GROWTH,Unit.MONEY),
    REVENUE_GROWTH_1Y(Family.GROWTH,Unit.PERCENT),EARNINGS_GROWTH(Family.GROWTH,Unit.PERCENT),
    EBITDA_GROWTH(Family.GROWTH,Unit.PERCENT),FCF_GROWTH(Family.GROWTH,Unit.PERCENT),
    DIVIDEND_YIELD(Family.DIVIDENDS,Unit.PERCENT),RECORDED_DIVIDEND_EVENTS(Family.DIVIDENDS,Unit.COUNT),
    DIVIDEND_HISTORY(Family.DIVIDENDS,Unit.HISTORY),
    CAPITAL_ADEQUACY(Family.STABILITY,Unit.PERCENT),ASSET_QUALITY(Family.STABILITY,Unit.PERCENT),
    AVERAGE_EQUITY(Family.CONTEXT,Unit.MONEY),NOPAT(Family.CONTEXT,Unit.MONEY),AVERAGE_INVESTED_CAPITAL(Family.CONTEXT,Unit.MONEY),
    EPS(Family.CONTEXT,Unit.PER_SHARE),BOOK_VALUE_PER_SHARE(Family.CONTEXT,Unit.PER_SHARE),SHARES_OUTSTANDING(Family.CONTEXT,Unit.COUNT);
    public enum Family { VALUATION,PROFITABILITY,STABILITY,GROWTH,DIVIDENDS,CONTEXT }
    public enum Unit { MONEY,PER_SHARE,MULTIPLE,RATIO,PERCENT,UNVERIFIED_RATE,COUNT,HISTORY }
    private final Family family; private final Unit unit;
    AnalysisMetric(Family family,Unit unit) { this.family=family; this.unit=unit; }
    public Family family() { return family; }
    public Unit unit() { return unit; }
    public String definition() {
        return switch (this) {
            case PE -> "Provider price/earnings TTM multiple; positive defined values only";
            case PB -> "Provider price/book multiple; source TTM horizon retained without inventing balance-sheet dates";
            case PS -> "Provider price/sales TTM multiple";
            case EV_EBITDA -> "Provider enterprise-value/EBITDA MRQ multiple; not an industrial bank-solvency measure";
            case EARNINGS_YIELD -> "100 / positive P/E; inherits the P/E observation context";
            case NET_MARGIN -> "100 * net income / positive revenue in the same verified period and currency";
            case EBITDA_MARGIN -> "100 * EBITDA / positive revenue in the same verified period and currency";
            case ROE -> "100 * net income / verified positive average equity over the same period; provider variant separate";
            case ROIC -> "100 * actual NOPAT / verified positive average invested capital; provider variant separate";
            case NET_DEBT -> "Point-in-time total debt minus cash/cash equivalents in one currency/date; negative net cash allowed";
            case NET_DEBT_EBITDA -> "Point-in-time net debt / positive TTM EBITDA at the same verified end date";
            case DIVIDEND_YIELD -> "Separate provider rate and security-scoped recorded-365-day amount / dated same-currency reference";
            case RECORDED_DIVIDEND_EVENTS, DIVIDEND_HISTORY -> "Observed non-cancelled security events in the queried history; not investor receipts";
            case AVERAGE_EQUITY,AVERAGE_INVESTED_CAPITAL -> "Verified period average, never a single balance-sheet snapshot substitute";
            case CAPITAL_ADEQUACY,ASSET_QUALITY -> "Bank-specific sourced measure; unavailable without an authoritative definition/input";
            default -> "Canonical "+name()+"; original provider definition, horizon, units and quality must be retained; no missing-input substitution";
        };
    }
}
