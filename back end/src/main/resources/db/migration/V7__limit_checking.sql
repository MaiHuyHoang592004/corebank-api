-- ============================================================
-- CoreBank Phase 1 - Limit Checking
--
-- Purpose:
-- - enforce transactional and periodic limits
-- - detect excess usage
-- - support risk and compliance rules
--
-- DO NOT MODIFY THIS FILE. CREATE NEW MIGRATION FOR CHANGES.
-- ============================================================

-- Drop existing tables if they exist (to handle schema changes in dev/test)
DROP TABLE IF EXISTS limit_usage_counters CASCADE;
DROP TABLE IF EXISTS limit_assignments CASCADE;
DROP TABLE IF EXISTS limit_rules CASCADE;
DROP TABLE IF EXISTS limit_profiles CASCADE;

-- ============================================================
-- 1. LIMIT PROFILES
-- ============================================================

CREATE TABLE limit_profiles (
    profile_id          uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    profile_code        text NOT NULL UNIQUE,
    profile_name        text NOT NULL,
    description         text,
    status              text NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'INACTIVE')),
    created_at          timestamptz NOT NULL DEFAULT now(),
    updated_at          timestamptz NOT NULL DEFAULT now()
);

-- Seed default profiles
INSERT INTO limit_profiles (profile_code, profile_name, description) VALUES
    ('STANDARD', 'Standard Account', 'Default limits for standard accounts'),
    ('VIP', 'VIP Account', 'Higher limits for VIP accounts'),
    ('BASIC', 'Basic Account', 'Lower limits for basic accounts');

-- ============================================================
-- 2. LIMIT RULES
-- ============================================================

CREATE TABLE limit_rules (
    rule_id             uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    profile_id          uuid NOT NULL REFERENCES limit_profiles(profile_id),
    rule_name           text NOT NULL,
    rule_type           text NOT NULL CHECK (rule_type IN ('TRANSACTIONAL', 'PERIODIC')),
    limit_type          text NOT NULL CHECK (limit_type IN ('AMOUNT', 'COUNT')),
    limit_value         bigint NOT NULL CHECK (limit_value > 0),
    currency            char(3) NOT NULL DEFAULT 'VND',
    period_type         text CHECK (period_type IN ('DAILY', 'WEEKLY', 'MONTHLY', 'YEARLY')),
    description         text,
    status              text NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'INACTIVE')),
    created_at          timestamptz NOT NULL DEFAULT now(),
    updated_at          timestamptz NOT NULL DEFAULT now(),
    CHECK (
        (rule_type = 'TRANSACTIONAL' AND period_type IS NULL) OR
        (rule_type = 'PERIODIC' AND period_type IS NOT NULL)
    )
);

-- Seed default limit rules for STANDARD profile
INSERT INTO limit_rules (profile_id, rule_name, rule_type, limit_type, limit_value, currency, period_type, description)
SELECT 
    profile_id,
    'TRANSACTION_AMOUNT_LIMIT',
    'TRANSACTIONAL',
    'AMOUNT',
    1000000000, -- 1 billion VND
    'VND',
    NULL,
    'Maximum amount per transaction'
FROM limit_profiles WHERE profile_code = 'STANDARD';

INSERT INTO limit_rules (profile_id, rule_name, rule_type, limit_type, limit_value, currency, period_type, description)
SELECT 
    profile_id,
    'DAILY_AMOUNT_LIMIT',
    'PERIODIC',
    'AMOUNT',
    5000000000, -- 5 billion VND
    'VND',
    'DAILY',
    'Maximum total amount per day'
FROM limit_profiles WHERE profile_code = 'STANDARD';

INSERT INTO limit_rules (profile_id, rule_name, rule_type, limit_type, limit_value, currency, period_type, description)
SELECT 
    profile_id,
    'DAILY_COUNT_LIMIT',
    'PERIODIC',
    'COUNT',
    100, -- 100 transactions per day
    'VND',
    'DAILY',
    'Maximum number of transactions per day'
FROM limit_profiles WHERE profile_code = 'STANDARD';

INSERT INTO limit_rules (profile_id, rule_name, rule_type, limit_type, limit_value, currency, period_type, description)
SELECT 
    profile_id,
    'MONTHLY_AMOUNT_LIMIT',
    'PERIODIC',
    'AMOUNT',
    100000000000, -- 100 billion VND
    'VND',
    'MONTHLY',
    'Maximum total amount per month'
FROM limit_profiles WHERE profile_code = 'STANDARD';

-- ============================================================
-- 3. LIMIT ASSIGNMENTS
-- ============================================================

CREATE TABLE limit_assignments (
    assignment_id       uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    customer_account_id uuid NOT NULL REFERENCES customer_accounts(customer_account_id),
    profile_id          uuid NOT NULL REFERENCES limit_profiles(profile_id),
    currency            char(3) NOT NULL DEFAULT 'VND',
    assigned_at         timestamptz NOT NULL DEFAULT now(),
    expires_at          timestamptz,
    created_at          timestamptz NOT NULL DEFAULT now(),
    updated_at          timestamptz NOT NULL DEFAULT now(),
    UNIQUE (customer_account_id, currency)
);

-- ============================================================
-- 4. LIMIT USAGE COUNTERS
-- ============================================================

CREATE TABLE limit_usage_counters (
    counter_id          bigserial PRIMARY KEY,
    customer_account_id uuid NOT NULL REFERENCES customer_accounts(customer_account_id),
    rule_id             uuid NOT NULL REFERENCES limit_rules(rule_id),
    currency            char(3) NOT NULL,
    period_start        timestamptz NOT NULL,
    period_end          timestamptz NOT NULL,
    used_value          bigint NOT NULL DEFAULT 0,
    usage_count         integer NOT NULL DEFAULT 0,
    created_at          timestamptz NOT NULL DEFAULT now(),
    updated_at          timestamptz NOT NULL DEFAULT now(),
    UNIQUE (customer_account_id, rule_id, period_start)
);

-- ============================================================
-- 5. INDEXES
-- ============================================================

CREATE INDEX ix_limit_rules_profile
    ON limit_rules(profile_id, status);

CREATE INDEX ix_limit_assignments_account
    ON limit_assignments(customer_account_id, currency);

CREATE INDEX ix_limit_usage_counters_account_rule
    ON limit_usage_counters(customer_account_id, rule_id, period_start, period_end);

CREATE INDEX ix_limit_usage_counters_period
    ON limit_usage_counters(period_end);