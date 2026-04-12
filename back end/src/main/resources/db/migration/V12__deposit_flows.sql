-- ============================================================
-- CoreBank Phase 3 - Deposit Flows
--
-- Purpose:
-- - Support term deposit contracts
-- - Track interest accruals
-- - Manage deposit lifecycle events
-- - Bind contracts to product versions for historical correctness
--
-- DO NOT MODIFY THIS FILE. CREATE NEW MIGRATION FOR CHANGES.
-- ============================================================

-- ============================================================
-- 1. DEPOSIT CONTRACTS TABLE
-- ============================================================

CREATE TABLE deposit_contracts (
    contract_id         uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    customer_account_id uuid NOT NULL REFERENCES customer_accounts(customer_account_id),
    product_id          uuid NOT NULL REFERENCES bank_products(product_id),
    product_version_id  uuid NOT NULL, -- References specific product version for historical correctness
    principal_amount    bigint NOT NULL CHECK (principal_amount > 0),
    currency            char(3) NOT NULL,
    interest_rate       numeric(10,6) NOT NULL CHECK (interest_rate >= 0),
    term_months         integer NOT NULL CHECK (term_months > 0),
    start_date          date NOT NULL,
    maturity_date       date NOT NULL,
    status              text NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'MATURING', 'MATURED', 'CLOSED', 'LIQUIDATED')),
    early_closure_penalty_rate numeric(10,6) DEFAULT 0.0,
    auto_renew          boolean NOT NULL DEFAULT false,
    created_at          timestamptz NOT NULL DEFAULT now(),
    updated_at          timestamptz NOT NULL DEFAULT now(),
    CHECK (maturity_date > start_date),
    CHECK (interest_rate <= 100.0) -- Maximum 100% interest rate
);

-- ============================================================
-- 2. DEPOSIT ACCRUALS TABLE
-- ============================================================

CREATE TABLE deposit_accruals (
    accrual_id          bigserial PRIMARY KEY,
    contract_id         uuid NOT NULL REFERENCES deposit_contracts(contract_id),
    accrual_date        date NOT NULL,
    accrued_interest    bigint NOT NULL CHECK (accrued_interest >= 0), -- in minor units
    running_balance     bigint NOT NULL CHECK (running_balance >= 0), -- cumulative accrued interest
    created_at          timestamptz NOT NULL DEFAULT now(),
    UNIQUE (contract_id, accrual_date)
);

-- ============================================================
-- 3. DEPOSIT EVENTS TABLE
-- ============================================================

CREATE TABLE deposit_events (
    event_id            bigserial PRIMARY KEY,
    contract_id         uuid NOT NULL REFERENCES deposit_contracts(contract_id),
    event_type          text NOT NULL CHECK (event_type IN ('OPENED', 'ACCURED', 'MATURING', 'MATURED', 'CLOSED', 'LIQUIDATED', 'PENALTY_APPLIED')),
    amount_minor        bigint CHECK (amount_minor IS NULL OR amount_minor >= 0),
    metadata_json       jsonb,
    created_at          timestamptz NOT NULL DEFAULT now()
);

-- ============================================================
-- 4. POSTING RULES FOR DEPOSITS (Hardcoded for now)
-- ============================================================
-- Note: Posting rules are currently hardcoded in the application layer
-- Future enhancement: Create posting_rule_sets and posting_rule_lines tables

-- ============================================================
-- 5. INDEXES
-- ============================================================

CREATE INDEX IF NOT EXISTS ix_deposit_contracts_account
    ON deposit_contracts(customer_account_id, status, created_at DESC);

CREATE INDEX IF NOT EXISTS ix_deposit_contracts_product
    ON deposit_contracts(product_id, status);

CREATE INDEX IF NOT EXISTS ix_deposit_contracts_maturity
    ON deposit_contracts(maturity_date, status);

CREATE INDEX IF NOT EXISTS ix_deposit_accruals_contract_date
    ON deposit_accruals(contract_id, accrual_date DESC);

CREATE INDEX IF NOT EXISTS ix_deposit_accruals_date
    ON deposit_accruals(accrual_date, contract_id);

CREATE INDEX IF NOT EXISTS ix_deposit_events_contract
    ON deposit_events(contract_id, created_at DESC);

CREATE INDEX IF NOT EXISTS ix_deposit_events_type
    ON deposit_events(event_type, created_at DESC);

-- ============================================================
-- 6. SEED DEPOSIT PRODUCT CONFIGURATION
-- ============================================================

-- Add configuration to existing term deposit product
UPDATE bank_products 
SET status = 'ACTIVE'
WHERE product_type = 'TERM_DEPOSIT';
