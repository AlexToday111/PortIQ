CREATE TABLE market_quotes (
    instrument_uid VARCHAR(36) PRIMARY KEY REFERENCES instruments(uid),
    price NUMERIC(29,9) NOT NULL CHECK (price > 0),
    currency VARCHAR(3) NOT NULL,
    quote_time TIMESTAMPTZ NOT NULL,
    fetched_at TIMESTAMPTZ NOT NULL,
    source TEXT NOT NULL
);

CREATE TABLE daily_candles (
    instrument_uid VARCHAR(36) NOT NULL REFERENCES instruments(uid),
    candle_time TIMESTAMPTZ NOT NULL,
    trading_date DATE NOT NULL,
    open_price NUMERIC(29,9) NOT NULL CHECK (open_price > 0),
    high_price NUMERIC(29,9) NOT NULL CHECK (high_price > 0),
    low_price NUMERIC(29,9) NOT NULL CHECK (low_price > 0),
    close_price NUMERIC(29,9) NOT NULL CHECK (close_price > 0),
    volume BIGINT NOT NULL CHECK (volume >= 0),
    is_complete BOOLEAN NOT NULL,
    source TEXT NOT NULL,
    fetched_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (instrument_uid, candle_time, source),
    CHECK (low_price <= high_price AND open_price BETWEEN low_price AND high_price
           AND close_price BETWEEN low_price AND high_price)
);
CREATE INDEX daily_candles_instrument_date_idx ON daily_candles (instrument_uid, trading_date);
CREATE INDEX daily_candles_incomplete_idx ON daily_candles (instrument_uid, candle_time) WHERE NOT is_complete;

-- Durable per-instrument coverage and manual refresh admission; no automatic job is created.
CREATE TABLE market_sync_state (
    instrument_uid VARCHAR(36) PRIMARY KEY REFERENCES instruments(uid),
    covered_from TIMESTAMPTZ,
    covered_to TIMESTAMPTZ,
    last_attempt TIMESTAMPTZ NOT NULL,
    lease_until TIMESTAMPTZ,
    last_success TIMESTAMPTZ,
    last_failure TIMESTAMPTZ,
    failure_code TEXT,
    CHECK ((covered_from IS NULL AND covered_to IS NULL)
           OR (covered_from IS NOT NULL AND covered_to IS NOT NULL AND covered_from < covered_to))
);
