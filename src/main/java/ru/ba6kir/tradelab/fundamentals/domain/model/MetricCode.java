package ru.ba6kir.tradelab.fundamentals.domain.model;

/** Exact field mapping; null field means unsupported, never a locally reconstructed metric. */
public enum MetricCode {
    MARKET_CAPITALIZATION("market_capitalization","Капитализация",Unit.MONEY,null),
    PE("pe_ratio_ttm","P/E",Unit.MULTIPLE,"TTM"),
    PB("price_to_book_ttm","P/B",Unit.MULTIPLE,"TTM"),
    PS("price_to_sales_ttm","P/S",Unit.MULTIPLE,"TTM"),
    EV_EBITDA("ev_to_ebitda_mrq","EV/EBITDA",Unit.MULTIPLE,"MRQ"),
    ROE("roe","ROE",Unit.UNVERIFIED_RATE,null),
    ROA("roa","ROA",Unit.UNVERIFIED_RATE,null),
    ROIC("roic","ROIC (провайдер)",Unit.UNVERIFIED_RATE,null),
    NET_MARGIN("net_margin_mrq","Чистая маржа",Unit.UNVERIFIED_RATE,"MRQ"),
    EBITDA_MARGIN(null,"Маржа EBITDA",Unit.UNVERIFIED_RATE,null),
    TOTAL_DEBT("total_debt_mrq","Общий долг",Unit.MONEY,"MRQ"),
    NET_DEBT(null,"Чистый долг",Unit.MONEY,null),
    DEBT_EBITDA("total_debt_to_ebitda_mrq","Debt/EBITDA",Unit.MULTIPLE,"MRQ"),
    NET_DEBT_EBITDA("net_debt_to_ebitda","Net Debt/EBITDA",Unit.MULTIPLE,null),
    CURRENT_RATIO("current_ratio_mrq","Текущая ликвидность",Unit.RATIO,"MRQ"),
    INTEREST_COVERAGE(null,"Покрытие процентов",Unit.RATIO,null),
    FIXED_CHARGE_COVERAGE("fixed_charge_coverage_ratio_fy","Покрытие фиксированных платежей (FCCR)",Unit.RATIO,"FY"),
    REVENUE("revenue_ttm","Выручка",Unit.MONEY,"TTM"),
    NET_INCOME("net_income_ttm","Чистая прибыль",Unit.MONEY,"TTM"),
    EBITDA("ebitda_ttm","EBITDA",Unit.MONEY,"TTM"),
    FREE_CASH_FLOW("free_cash_flow_ttm","Свободный денежный поток",Unit.MONEY,"TTM"),
    REVENUE_GROWTH_1Y("one_year_annual_revenue_growth_rate","Рост выручки за год",Unit.UNVERIFIED_RATE,"1Y"),
    EARNINGS_GROWTH(null,"Рост чистой прибыли",Unit.UNVERIFIED_RATE,null),
    DIVIDEND_YIELD("dividend_yield_daily_ttm","Дивидендная доходность TTM",Unit.UNVERIFIED_RATE,"TTM"),
    EPS("eps_ttm","EPS",Unit.MONEY_PER_SHARE,"TTM"),
    BOOK_VALUE_PER_SHARE(null,"Балансовая стоимость на акцию",Unit.MONEY_PER_SHARE,null),
    SHARES_OUTSTANDING("shares_outstanding","Акций в обращении",Unit.COUNT,null);

    public enum Unit { MONEY, MONEY_PER_SHARE, MULTIPLE, RATIO, UNVERIFIED_RATE, COUNT }
    private final String field,label,period;
    private final Unit unit;
    MetricCode(String field,String label,Unit unit,String period) { this.field=field; this.label=label; this.unit=unit; this.period=period; }
    public String field() { return field; }
    public String label() { return label; }
    public Unit unit() { return unit; }
    public String period() { return period; }
    public boolean supported() { return field!=null; }
}
