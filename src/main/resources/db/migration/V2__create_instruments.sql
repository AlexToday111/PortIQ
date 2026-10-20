CREATE TABLE instruments (
    uid VARCHAR(36) PRIMARY KEY,
    ticker TEXT,
    class_code TEXT,
    name TEXT,
    figi TEXT,
    isin TEXT,
    currency TEXT,
    exchange TEXT,
    lot_size INTEGER CHECK (lot_size > 0),
    trading_status TEXT,
    api_trade_available BOOLEAN,
    updated_at TIMESTAMPTZ NOT NULL,
    source TEXT NOT NULL
);

-- Tickers are deliberately NOT unique, including within cached search results.
CREATE INDEX instruments_ticker_class_code_idx ON instruments (ticker, class_code);
CREATE INDEX instruments_class_code_idx ON instruments (class_code);
