-- Immutable provider observations. Conflicting versions are retained, never guessed into one payment.
CREATE TABLE dividend_events (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    instrument_uid VARCHAR(36) NOT NULL REFERENCES instruments(uid),
    amount_per_share NUMERIC(40,9), currency VARCHAR(3),
    declared_date DATE, last_buy_date DATE, record_date DATE, payment_date DATE,
    dividend_type TEXT, regularity TEXT, event_status TEXT NOT NULL,
    provider_yield NUMERIC(40,9), provider_reference_price NUMERIC(40,9), reference_currency VARCHAR(3),
    source TEXT NOT NULL, provider_created_at TIMESTAMPTZ,
    first_seen_at TIMESTAMPTZ NOT NULL, last_seen_at TIMESTAMPTZ NOT NULL, updated_at TIMESTAMPTZ NOT NULL,
    content_hash VARCHAR(64) NOT NULL, identity_anchor VARCHAR(64) NOT NULL,
    ambiguous BOOLEAN NOT NULL DEFAULT FALSE,
    observation JSONB NOT NULL,
    UNIQUE(instrument_uid,source,content_hash),
    CHECK (event_status IN ('ANNOUNCED','CANCELLED','HISTORICAL_RECORDED','UNKNOWN'))
);
CREATE INDEX dividend_instrument_record_idx ON dividend_events(instrument_uid,record_date);
CREATE INDEX dividend_record_idx ON dividend_events(record_date);
CREATE INDEX dividend_payment_idx ON dividend_events(payment_date);
CREATE INDEX dividend_anchor_idx ON dividend_events(instrument_uid,source,identity_anchor);

CREATE TABLE dividend_sync_state (
    instrument_uid VARCHAR(36) PRIMARY KEY REFERENCES instruments(uid),
    coverage_from DATE, coverage_to DATE,
    last_attempt TIMESTAMPTZ, lease_until TIMESTAMPTZ,
    last_success TIMESTAMPTZ, last_failure TIMESTAMPTZ, failure_code TEXT
);
