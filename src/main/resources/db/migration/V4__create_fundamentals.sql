CREATE TABLE asset_identities (
    asset_uid VARCHAR(36) PRIMARY KEY,
    asset_name TEXT,
    company_name TEXT,
    brand_uid TEXT,
    source TEXT NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    last_attempt TIMESTAMPTZ,
    lease_until TIMESTAMPTZ,
    last_success TIMESTAMPTZ,
    last_failure TIMESTAMPTZ,
    failure_code TEXT
);
CREATE TABLE instrument_asset_links (
    instrument_uid VARCHAR(36) PRIMARY KEY REFERENCES instruments(uid),
    asset_uid VARCHAR(36) NOT NULL REFERENCES asset_identities(asset_uid),
    verified_at TIMESTAMPTZ NOT NULL
);
CREATE INDEX instrument_asset_links_asset_idx ON instrument_asset_links(asset_uid);

CREATE TABLE fundamental_snapshots (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    asset_uid VARCHAR(36) NOT NULL REFERENCES asset_identities(asset_uid),
    source TEXT NOT NULL,
    version_number BIGINT NOT NULL,
    fetched_at TIMESTAMPTZ NOT NULL,
    last_fetched_at TIMESTAMPTZ NOT NULL,
    retrieval_count BIGINT NOT NULL DEFAULT 1,
    provider_as_of TIMESTAMPTZ,
    fiscal_period_start TIMESTAMPTZ,
    fiscal_period_end TIMESTAMPTZ,
    content_hash VARCHAR(64) NOT NULL,
    completeness_status TEXT NOT NULL,
    raw_payload JSONB NOT NULL,
    UNIQUE(asset_uid,source,version_number)
);
CREATE INDEX fundamental_snapshots_latest_idx ON fundamental_snapshots(asset_uid,id DESC);
CREATE INDEX fundamental_snapshots_hash_idx ON fundamental_snapshots(asset_uid,content_hash);

CREATE TABLE fundamental_metric_values (
    snapshot_id BIGINT NOT NULL REFERENCES fundamental_snapshots(id),
    metric_code TEXT NOT NULL,
    metric_value NUMERIC,
    unit TEXT NOT NULL,
    currency TEXT,
    reporting_period TEXT,
    quality_status TEXT NOT NULL CHECK(quality_status IN ('VALID','MISSING','SUSPECT','STALE','UNSUPPORTED')),
    quality_reason TEXT,
    raw_value TEXT,
    PRIMARY KEY(snapshot_id,metric_code),
    CHECK(quality_status <> 'VALID' OR metric_value IS NOT NULL),
    CHECK(quality_status NOT IN ('MISSING','UNSUPPORTED') OR metric_value IS NULL)
);

CREATE TABLE financial_report_sync (
    instrument_uid VARCHAR(36) PRIMARY KEY REFERENCES instruments(uid),
    fetched_at TIMESTAMPTZ NOT NULL,
    from_time TIMESTAMPTZ NOT NULL,
    to_time TIMESTAMPTZ NOT NULL
);
CREATE TABLE financial_report_events (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    instrument_uid VARCHAR(36) NOT NULL REFERENCES instruments(uid),
    event_hash VARCHAR(64) NOT NULL,
    report_date TIMESTAMPTZ,
    reporting_year INTEGER,
    period_number INTEGER,
    reporting_period TEXT,
    report_type TEXT,
    provider_created_at TIMESTAMPTZ,
    source TEXT NOT NULL,
    fetched_at TIMESTAMPTZ NOT NULL,
    UNIQUE(instrument_uid,source,event_hash)
);
CREATE INDEX financial_report_events_date_idx ON financial_report_events(instrument_uid,report_date);
