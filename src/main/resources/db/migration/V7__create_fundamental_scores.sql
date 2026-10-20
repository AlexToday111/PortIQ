CREATE TABLE scoring_model_versions (
    configuration_fingerprint VARCHAR(64) PRIMARY KEY,
    model_version TEXT NOT NULL,
    configuration_version TEXT NOT NULL,
    profile_configuration JSONB NOT NULL,
    weights JSONB NOT NULL,
    thresholds JSONB NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE(model_version,configuration_version)
);
CREATE TABLE stock_score_snapshots (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    instrument_uid VARCHAR(36) REFERENCES instruments(uid),
    asset_uid VARCHAR(36) REFERENCES asset_identities(asset_uid),
    evaluation_time TIMESTAMPTZ NOT NULL,
    data_cutoff TIMESTAMPTZ,
    score_status TEXT NOT NULL CHECK(score_status IN ('SCORED','PARTIAL','INSUFFICIENT_DATA','STALE_DATA','UNSUPPORTED_PROFILE','FAILED')),
    overall_score NUMERIC(12,6) CHECK(overall_score BETWEEN 0 AND 100),
    q_score NUMERIC(12,6) CHECK(q_score BETWEEN 0 AND 100),
    f_score NUMERIC(12,6) CHECK(f_score BETWEEN 0 AND 100),
    v_score NUMERIC(12,6) CHECK(v_score BETWEEN 0 AND 100),
    d_score NUMERIC(12,6) CHECK(d_score BETWEEN 0 AND 100),
    profile_id TEXT NOT NULL,
    model_version TEXT NOT NULL,
    configuration_fingerprint VARCHAR(64) NOT NULL REFERENCES scoring_model_versions(configuration_fingerprint),
    data_coverage JSONB NOT NULL,
    input_fingerprint VARCHAR(64) NOT NULL,
    evaluation_fingerprint VARCHAR(64) NOT NULL UNIQUE,
    input_evidence JSONB NOT NULL,
    result JSONB NOT NULL,
    CHECK((score_status='SCORED') = (overall_score IS NOT NULL))
);
CREATE INDEX stock_score_instrument_history_idx ON stock_score_snapshots(instrument_uid,id DESC);
CREATE INDEX stock_score_asset_history_idx ON stock_score_snapshots(asset_uid,id DESC);
CREATE TABLE scoring_metric_contributions (
    score_snapshot BIGINT NOT NULL REFERENCES stock_score_snapshots(id),
    rule_id TEXT NOT NULL,
    pillar TEXT NOT NULL,
    metric_identifier TEXT NOT NULL,
    observation_reference TEXT,
    normalized_metric_value NUMERIC,
    metric_score NUMERIC(12,6) CHECK(metric_score BETWEEN 0 AND 100),
    applied_metric_weight NUMERIC NOT NULL CHECK(applied_metric_weight BETWEEN 0 AND 1),
    pillar_weight NUMERIC NOT NULL CHECK(pillar_weight BETWEEN 0 AND 1),
    contribution NUMERIC,
    status TEXT NOT NULL,
    reason TEXT,
    methodology TEXT NOT NULL,
    PRIMARY KEY(score_snapshot,rule_id)
);
CREATE TABLE scoring_risk_flags (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    score_snapshot BIGINT NOT NULL REFERENCES stock_score_snapshots(id),
    rule_id TEXT NOT NULL,
    severity TEXT NOT NULL CHECK(severity IN ('INFO','WARNING','CRITICAL')),
    explanation TEXT NOT NULL,
    supporting_inputs JSONB NOT NULL,
    condition TEXT NOT NULL,
    actual_value NUMERIC,
    observed_at TIMESTAMPTZ
);
