-- ============================================================
-- CoreBank Phase 2 - Daily Snapshots
--
-- Purpose:
-- - Create daily snapshots of account balances
-- - Support reconciliation between snapshots
-- - Provide historical balance views
--
-- DO NOT MODIFY THIS FILE. CREATE NEW MIGRATION FOR CHANGES.
-- ============================================================

-- ============================================================
-- 1. ACCOUNT BALANCE SNAPSHOTS TABLE
-- ============================================================

-- Drop existing table if it has wrong schema
DROP TABLE IF EXISTS account_balance_snapshots CASCADE;

CREATE TABLE account_balance_snapshots (
    snapshot_id         bigserial PRIMARY KEY,
    customer_account_id uuid NOT NULL REFERENCES customer_accounts(customer_account_id),
    snapshot_date       date NOT NULL,
    posted_balance      bigint NOT NULL,
    available_balance   bigint NOT NULL,
    currency            char(3) NOT NULL DEFAULT 'VND',
    created_at          timestamptz NOT NULL DEFAULT now(),
    UNIQUE (customer_account_id, snapshot_date)
);

-- ============================================================
-- 2. INDEXES
-- ============================================================

CREATE INDEX IF NOT EXISTS ix_account_balance_snapshots_date
    ON account_balance_snapshots(snapshot_date, customer_account_id);

CREATE INDEX IF NOT EXISTS ix_account_balance_snapshots_account
    ON account_balance_snapshots(customer_account_id, snapshot_date DESC);
