CREATE TABLE ranking_runs (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    created_at TIMESTAMPTZ NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL CHECK (expires_at > created_at),
    model_version VARCHAR(80) NOT NULL,
    configuration_fingerprint CHAR(64) NOT NULL,
    eligibility_policy_version VARCHAR(80) NOT NULL,
    policy_fingerprint CHAR(64) NOT NULL,
    universe_hash CHAR(64) NOT NULL,
    ranking_scope VARCHAR(32) NOT NULL CHECK (ranking_scope = 'PROFILE_PERIOD_GROUPS'),
    status VARCHAR(16) NOT NULL CHECK (status = 'COMPLETE'),
    eligible_count INTEGER NOT NULL CHECK (eligible_count >= 0),
    excluded_count INTEGER NOT NULL CHECK (excluded_count >= 0),
    universe JSONB NOT NULL,
    policy JSONB NOT NULL
);
CREATE TABLE ranking_entries (
    ranking_run_id BIGINT NOT NULL REFERENCES ranking_runs(id),
    member_key VARCHAR(100) NOT NULL,
    instrument_uid VARCHAR(36) REFERENCES instruments(uid),
    score_snapshot_id BIGINT REFERENCES stock_score_snapshots(id),
    sector VARCHAR(80),
    comparison_group TEXT,
    rank INTEGER CHECK (rank > 0),
    score NUMERIC(12,6) CHECK (score BETWEEN 0 AND 100),
    eligibility_status VARCHAR(32) NOT NULL CHECK (eligibility_status IN ('ELIGIBLE','INSUFFICIENT_DATA','STALE_DATA','UNSUPPORTED_PROFILE','TRADING_UNAVAILABLE','NOT_COMPARABLE')),
    exclusion_reasons JSONB NOT NULL,
    evidence JSONB NOT NULL,
    PRIMARY KEY (ranking_run_id, member_key),
    CHECK ((eligibility_status = 'ELIGIBLE') = (rank IS NOT NULL)),
    CHECK (rank IS NULL OR (score IS NOT NULL AND score_snapshot_id IS NOT NULL AND comparison_group IS NOT NULL))
);
CREATE UNIQUE INDEX ranking_group_positions ON ranking_entries(ranking_run_id,comparison_group,rank) WHERE rank IS NOT NULL;
CREATE INDEX ranking_score_references ON ranking_entries(score_snapshot_id);
CREATE TABLE ranking_refresh_state (
    id SMALLINT PRIMARY KEY CHECK (id = 1),
    last_attempt TIMESTAMPTZ,
    lease_until TIMESTAMPTZ,
    last_success TIMESTAMPTZ,
    last_failure VARCHAR(80)
);
INSERT INTO ranking_refresh_state(id) VALUES(1);
