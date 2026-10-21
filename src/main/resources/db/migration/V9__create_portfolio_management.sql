CREATE TABLE portfolios (
    id UUID PRIMARY KEY, name VARCHAR(120) NOT NULL, description TEXT NOT NULL DEFAULT '',
    base_currency CHAR(3) NOT NULL DEFAULT 'RUB' CHECK(base_currency='RUB'),status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    creation_key VARCHAR(240) NOT NULL UNIQUE,created_at TIMESTAMPTZ NOT NULL,updated_at TIMESTAMPTZ NOT NULL
);
CREATE TABLE broker_account_candidates (
    id UUID PRIMARY KEY,external_id TEXT NOT NULL UNIQUE,name TEXT NOT NULL,account_type TEXT NOT NULL,status TEXT NOT NULL,
    read_only BOOLEAN NOT NULL,opened_at TIMESTAMPTZ,discovered_at TIMESTAMPTZ NOT NULL
);
CREATE TABLE portfolio_accounts (
    id UUID PRIMARY KEY,portfolio_id UUID NOT NULL REFERENCES portfolios(id),name VARCHAR(120) NOT NULL,
    account_type VARCHAR(20) NOT NULL CHECK(account_type IN('MANUAL','BROKERAGE','IIS')),
    source_type VARCHAR(20) NOT NULL CHECK(source_type IN('MANUAL','T_INVEST')),broker VARCHAR(40),external_account_id TEXT UNIQUE,
    sync_enabled BOOLEAN NOT NULL DEFAULT FALSE,history_from TIMESTAMPTZ,broker_opened_at TIMESTAMPTZ,
    checkpoint TIMESTAMPTZ,last_successful_sync_at TIMESTAMPTZ,manual_overlap_reviewed_at TIMESTAMPTZ,creation_key VARCHAR(240) NOT NULL UNIQUE,
    created_at TIMESTAMPTZ NOT NULL,updated_at TIMESTAMPTZ NOT NULL,
    CHECK((source_type='T_INVEST')=(external_account_id IS NOT NULL)),CHECK(NOT sync_enabled OR source_type='T_INVEST')
);
CREATE INDEX portfolio_account_membership ON portfolio_accounts(portfolio_id,id);
CREATE TABLE portfolio_owner_selection(id SMALLINT PRIMARY KEY CHECK(id=1),portfolio_id UUID REFERENCES portfolios(id),account_id UUID REFERENCES portfolio_accounts(id));
INSERT INTO portfolio_owner_selection(id) VALUES(1);
CREATE TABLE portfolio_transactions (
    id UUID PRIMARY KEY,account_id UUID NOT NULL REFERENCES portfolio_accounts(id),
    transaction_type VARCHAR(32) NOT NULL CHECK(transaction_type IN('BUY','SELL','DEPOSIT','WITHDRAWAL','DIVIDEND_RECEIVED','BROKER_FEE','TAX_WITHHELD','CASH_ADJUSTMENT','OPENING_POSITION','REVERSAL')),
    instrument_uid VARCHAR(36),quantity NUMERIC,price NUMERIC,gross_amount NUMERIC,commission NUMERIC NOT NULL CHECK(commission>=0),
    currency CHAR(3) NOT NULL,executed_at TIMESTAMPTZ NOT NULL,recorded_at TIMESTAMPTZ NOT NULL,
    source_type VARCHAR(20) NOT NULL CHECK(source_type IN('MANUAL','T_INVEST')),external_operation_id TEXT,
    source_reference TEXT NOT NULL,idempotency_key VARCHAR(240) NOT NULL,correction_reference UUID,
    status VARCHAR(20) NOT NULL CHECK(status='CONFIRMED'),evidence JSONB NOT NULL,
    UNIQUE(account_id,idempotency_key),UNIQUE(account_id,id),FOREIGN KEY(account_id,correction_reference) REFERENCES portfolio_transactions(account_id,id),
    CHECK(quantity IS NULL OR quantity>0),CHECK(price IS NULL OR price>0)
);
CREATE UNIQUE INDEX portfolio_one_reversal ON portfolio_transactions(correction_reference) WHERE transaction_type='REVERSAL';
CREATE INDEX portfolio_transaction_history ON portfolio_transactions(account_id,executed_at,id);
-- Confirmed accounting facts are append-only, including explicit reversals/replacements.
CREATE FUNCTION protect_portfolio_ledger() RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN RAISE EXCEPTION 'Confirmed portfolio ledger records are immutable'; END $$;
CREATE TRIGGER portfolio_immutable_ledger BEFORE UPDATE OR DELETE ON portfolio_transactions FOR EACH ROW EXECUTE FUNCTION protect_portfolio_ledger();
CREATE TABLE portfolio_requests (
    token UUID PRIMARY KEY,account_id UUID NOT NULL REFERENCES portfolio_accounts(id),request_key VARCHAR(240) NOT NULL UNIQUE,
    payload_hash CHAR(64) NOT NULL,draft JSONB NOT NULL,expires_at TIMESTAMPTZ NOT NULL,
    confirmed_transaction_id UUID,FOREIGN KEY(account_id,confirmed_transaction_id) REFERENCES portfolio_transactions(account_id,id)
);
CREATE TABLE acquisition_lots (
    origin_transaction_id UUID PRIMARY KEY REFERENCES portfolio_transactions(id),account_id UUID NOT NULL REFERENCES portfolio_accounts(id),
    instrument_uid VARCHAR(36) NOT NULL,currency CHAR(3) NOT NULL,initial_quantity NUMERIC NOT NULL CHECK(initial_quantity>0),
    remaining_quantity NUMERIC NOT NULL CHECK(remaining_quantity>=0),original_cost NUMERIC,remaining_cost NUMERIC,acquired_at TIMESTAMPTZ NOT NULL,
    FOREIGN KEY(account_id,origin_transaction_id) REFERENCES portfolio_transactions(account_id,id),
    CHECK(remaining_quantity<=initial_quantity),CHECK(original_cost IS NULL OR original_cost>=0),CHECK(remaining_cost IS NULL OR remaining_cost>=0)
);
CREATE TABLE lot_disposals (
    sale_transaction_id UUID NOT NULL REFERENCES portfolio_transactions(id),acquisition_lot_id UUID NOT NULL REFERENCES acquisition_lots(origin_transaction_id),
    disposed_quantity NUMERIC NOT NULL CHECK(disposed_quantity>0),allocated_cost NUMERIC,allocated_sale_commission NUMERIC NOT NULL CHECK(allocated_sale_commission>=0),
    PRIMARY KEY(sale_transaction_id,acquisition_lot_id)
);
CREATE TABLE portfolio_position_projections (
    account_id UUID NOT NULL REFERENCES portfolio_accounts(id),instrument_uid VARCHAR(36) NOT NULL,currency CHAR(3) NOT NULL,
    quantity NUMERIC CHECK(quantity>=0),remaining_cost NUMERIC,average_cost NUMERIC,cost_quality_status VARCHAR(40) NOT NULL,
    calculated_at TIMESTAMPTZ NOT NULL,PRIMARY KEY(account_id,instrument_uid,currency)
);
CREATE TABLE broker_source_operations (
    id UUID PRIMARY KEY,account_id UUID NOT NULL REFERENCES portfolio_accounts(id),external_operation_id TEXT NOT NULL,
    identity_key TEXT NOT NULL,source_fingerprint CHAR(64) NOT NULL,evidence JSONB NOT NULL,mapping_status TEXT NOT NULL,reason TEXT,
    first_seen TIMESTAMPTZ NOT NULL,last_seen TIMESTAMPTZ NOT NULL,UNIQUE(account_id,external_operation_id)
);
CREATE INDEX broker_operation_identity ON broker_source_operations(account_id,identity_key);
CREATE TABLE broker_operation_versions (
    account_id UUID NOT NULL REFERENCES portfolio_accounts(id),external_operation_id TEXT NOT NULL,source_fingerprint CHAR(64) NOT NULL,
    observed_at TIMESTAMPTZ NOT NULL,evidence JSONB NOT NULL,PRIMARY KEY(account_id,external_operation_id,source_fingerprint)
);
CREATE TABLE broker_sync_runs (
    id UUID PRIMARY KEY,account_id UUID NOT NULL REFERENCES portfolio_accounts(id),started_at TIMESTAMPTZ NOT NULL,
    finished_at TIMESTAMPTZ,status VARCHAR(32) NOT NULL,operations_created INTEGER NOT NULL DEFAULT 0,
    operations_unchanged INTEGER NOT NULL DEFAULT 0,operations_corrected INTEGER NOT NULL DEFAULT 0,
    operations_unsupported INTEGER NOT NULL DEFAULT 0,error_summary VARCHAR(80),result JSONB
);
CREATE TABLE broker_sync_state (
    account_id UUID PRIMARY KEY REFERENCES portfolio_accounts(id),run_id UUID REFERENCES broker_sync_runs(id),last_attempt TIMESTAMPTZ,
    lease_until TIMESTAMPTZ,last_error VARCHAR(80)
);
CREATE TABLE broker_account_snapshots (
    id UUID PRIMARY KEY,account_id UUID NOT NULL REFERENCES portfolio_accounts(id),captured_at TIMESTAMPTZ NOT NULL,evidence JSONB NOT NULL
);
CREATE INDEX broker_account_snapshot_history ON broker_account_snapshots(account_id,captured_at DESC);
CREATE TABLE broker_reconciliation_results (
    id UUID PRIMARY KEY,account_id UUID NOT NULL REFERENCES portfolio_accounts(id),checked_at TIMESTAMPTZ NOT NULL,evidence JSONB NOT NULL
);
CREATE INDEX broker_reconciliation_history ON broker_reconciliation_results(account_id,checked_at DESC);
