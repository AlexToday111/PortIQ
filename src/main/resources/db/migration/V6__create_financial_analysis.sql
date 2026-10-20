ALTER TABLE asset_identities ADD COLUMN provider_sector TEXT;
ALTER TABLE fundamental_snapshots ADD CONSTRAINT fundamental_snapshots_asset_id_unique UNIQUE(asset_uid,id);

CREATE TABLE issuer_sector_classifications (
    asset_uid VARCHAR(36) PRIMARY KEY REFERENCES asset_identities(asset_uid),
    issuer_key TEXT,
    sector TEXT NOT NULL,
    business_type TEXT NOT NULL CHECK(business_type IN ('BANK','NON_BANK_FINANCIAL','NON_FINANCIAL','UNKNOWN')),
    source TEXT NOT NULL,
    verified_at TIMESTAMPTZ NOT NULL,
    is_override BOOLEAN NOT NULL,
    evidence TEXT NOT NULL,
    classification JSONB NOT NULL
);
CREATE INDEX issuer_sector_group_idx ON issuer_sector_classifications(sector,business_type);
CREATE INDEX issuer_sector_issuer_idx ON issuer_sector_classifications(issuer_key);

CREATE TABLE analysis_metric_definitions (
    metric_code TEXT PRIMARY KEY,
    family TEXT NOT NULL,
    canonical_unit TEXT NOT NULL,
    definition TEXT NOT NULL
);

-- Original normalized provider values already live in fundamental_metric_values (V4).
-- Only local formula outputs are stored here; provider and derived variants never overwrite each other.
CREATE TABLE derived_financial_observations (
    observation_id TEXT PRIMARY KEY,
    asset_uid VARCHAR(36) NOT NULL REFERENCES asset_identities(asset_uid),
    snapshot_id BIGINT NOT NULL,
    metric_code TEXT NOT NULL REFERENCES analysis_metric_definitions(metric_code),
    metric_value NUMERIC,
    unit TEXT NOT NULL,
    currency TEXT,
    observation_type TEXT NOT NULL,
    reporting_period TEXT,
    period_start DATE,
    reporting_end_date DATE,
    provider_as_of TIMESTAMPTZ,
    fetched_at TIMESTAMPTZ NOT NULL,
    source TEXT NOT NULL,
    quality_status JSONB NOT NULL,
    quality_reason TEXT,
    calculation_method TEXT NOT NULL,
    input_lineage JSONB NOT NULL,
    observation JSONB NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY(asset_uid,snapshot_id) REFERENCES fundamental_snapshots(asset_uid,id)
);
CREATE INDEX derived_financial_asset_metric_idx ON derived_financial_observations(asset_uid,metric_code,snapshot_id DESC);
