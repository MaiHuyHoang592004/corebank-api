-- ============================================================
-- CoreBank Phase 1 foundation schema
--
-- Scope:
-- - foundation only for customer/account/ledger/payment/idempotency/audit/outbox/approval
-- - no deposit tables yet
-- - no lending tables yet
-- - customer_accounts is the authoritative current balance table
-- - idempotency authority lives only in idempotency_keys
-- - ledger/audit/hold/payment event tables are append-only by design
--
-- UNKNOWN / intentionally deferred:
-- - dedicated PostgreSQL schemas per logical domain
-- - business_date stamping model for EOD/BOD-aware financial records
-- - overdraft-specific balance constraints and product-version binding
-- ============================================================

CREATE EXTENSION IF NOT EXISTS pgcrypto;

-- ============================================================
-- 0. SYSTEM RUNTIME CONTROL
-- ============================================================

CREATE TABLE system_configs (
    config_key      text PRIMARY KEY,
    config_value    jsonb NOT NULL,
    description     text,
    updated_at      timestamptz NOT NULL DEFAULT now(),
    updated_by      text
);

INSERT INTO system_configs (config_key, config_value, description)
VALUES
    ('runtime_mode', '{"status":"RUNNING"}'::jsonb, 'RUNNING | EOD_LOCK | MAINTENANCE | READ_ONLY'),
    ('eod_control', '{"is_open":true,"business_date":null}'::jsonb, 'Business date and EOD/BOD control flags')
ON CONFLICT (config_key) DO NOTHING;

-- ============================================================
-- 1. CUSTOMER / PRODUCT / ACCOUNT FOUNDATIONS
-- ============================================================

CREATE TABLE customers (
    customer_id     uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    customer_type   text NOT NULL DEFAULT 'INDIVIDUAL',
    full_name       text NOT NULL,
    email           text,
    phone           text,
    status          text NOT NULL DEFAULT 'ACTIVE',
    risk_band       text,
    created_at      timestamptz NOT NULL DEFAULT now(),
    updated_at      timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE bank_products (
    product_id      uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    product_code    text NOT NULL UNIQUE,
    product_name    text NOT NULL,
    product_type    text NOT NULL,
    currency        char(3) NOT NULL,
    status          text NOT NULL DEFAULT 'ACTIVE',
    created_at      timestamptz NOT NULL DEFAULT now(),
    updated_at      timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE ledger_accounts (
    ledger_account_id   uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    account_code        text NOT NULL UNIQUE,
    account_name        text NOT NULL,
    account_type        text NOT NULL CHECK (account_type IN ('ASSET', 'LIABILITY', 'EQUITY', 'REVENUE', 'EXPENSE')),
    currency            char(3) NOT NULL,
    is_active           boolean NOT NULL DEFAULT true,
    created_at          timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE customer_accounts (
    customer_account_id     uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    customer_id             uuid NOT NULL REFERENCES customers(customer_id),
    product_id              uuid NOT NULL REFERENCES bank_products(product_id),
    account_number          text NOT NULL UNIQUE,
    currency                char(3) NOT NULL,
    status                  text NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'BLOCKED', 'CLOSED')),
    posted_balance_minor    bigint NOT NULL DEFAULT 0,
    available_balance_minor bigint NOT NULL DEFAULT 0,
    version                 bigint NOT NULL DEFAULT 0 CHECK (version >= 0),
    created_at              timestamptz NOT NULL DEFAULT now(),
    updated_at              timestamptz NOT NULL DEFAULT now(),
    CHECK (available_balance_minor >= 0)
);

COMMENT ON TABLE customer_accounts IS
    'Authoritative current balance table for customer accounts. Financial posting truth remains in ledger_journals and ledger_postings.';
COMMENT ON COLUMN customer_accounts.posted_balance_minor IS
    'Authoritative current posted balance in minor units. Update only inside SQL-first financial transactions.';
COMMENT ON COLUMN customer_accounts.available_balance_minor IS
    'Authoritative current available balance in minor units. Reflects hold effects separately from posted balance.';

-- ============================================================
-- 2. LEDGER FOUNDATIONS
-- ============================================================

CREATE TABLE ledger_journals (
    journal_id               uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    journal_type             text NOT NULL,
    reference_type           text NOT NULL,
    reference_id             uuid NOT NULL,
    currency                 char(3) NOT NULL,
    reversal_of_journal_id   uuid REFERENCES ledger_journals(journal_id),
    created_by_actor         text,
    correlation_id           uuid,
    created_at               timestamptz NOT NULL DEFAULT now(),
    prev_row_hash            bytea,
    row_hash                 bytea NOT NULL,
    CHECK (journal_id IS DISTINCT FROM reversal_of_journal_id)
);

CREATE TABLE ledger_postings (
    posting_id               bigserial PRIMARY KEY,
    journal_id               uuid NOT NULL REFERENCES ledger_journals(journal_id),
    ledger_account_id        uuid NOT NULL REFERENCES ledger_accounts(ledger_account_id),
    customer_account_id      uuid REFERENCES customer_accounts(customer_account_id),
    entry_side               text NOT NULL CHECK (entry_side IN ('D', 'C')),
    amount_minor             bigint NOT NULL CHECK (amount_minor > 0),
    currency                 char(3) NOT NULL,
    created_at               timestamptz NOT NULL DEFAULT now()
);

COMMENT ON TABLE ledger_journals IS
    'Append-only ledger journal header. Financial corrections must use reversal journals instead of UPDATE or DELETE.';
COMMENT ON TABLE ledger_postings IS
    'Append-only ledger posting lines. Balanced journal invariant must be enforced in SQL-first posting logic and tests.';

-- ============================================================
-- 3. PAYMENT / HOLD FOUNDATIONS
-- ============================================================

CREATE TABLE payment_orders (
    payment_order_id     uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    payer_account_id     uuid NOT NULL REFERENCES customer_accounts(customer_account_id),
    payee_account_id     uuid REFERENCES customer_accounts(customer_account_id),
    amount_minor         bigint NOT NULL CHECK (amount_minor > 0),
    currency             char(3) NOT NULL,
    payment_type         text NOT NULL,
    status               text NOT NULL DEFAULT 'INITIATED' CHECK (status IN ('INITIATED', 'AUTHORIZED', 'PARTIALLY_CAPTURED', 'CAPTURED', 'VOIDED', 'FAILED', 'EXPIRED')),
    description          text,
    created_at           timestamptz NOT NULL DEFAULT now(),
    updated_at           timestamptz NOT NULL DEFAULT now()
);

COMMENT ON TABLE payment_orders IS
    'Business payment order state. Idempotency authority does not live here; it lives in idempotency_keys.';

CREATE TABLE funds_holds (
    hold_id              uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    payment_order_id     uuid NOT NULL UNIQUE REFERENCES payment_orders(payment_order_id),
    customer_account_id  uuid NOT NULL REFERENCES customer_accounts(customer_account_id),
    amount_minor         bigint NOT NULL CHECK (amount_minor > 0),
    remaining_minor      bigint NOT NULL,
    status               text NOT NULL DEFAULT 'AUTHORIZED' CHECK (status IN ('AUTHORIZED', 'PARTIALLY_CAPTURED', 'FULLY_CAPTURED', 'VOIDED', 'EXPIRED')),
    authorized_at        timestamptz NOT NULL DEFAULT now(),
    captured_at          timestamptz,
    voided_at            timestamptz,
    created_at           timestamptz NOT NULL DEFAULT now(),
    CHECK (remaining_minor >= 0),
    CHECK (remaining_minor <= amount_minor),
    CHECK (
        (status = 'AUTHORIZED' AND remaining_minor = amount_minor AND captured_at IS NULL AND voided_at IS NULL)
        OR (status = 'PARTIALLY_CAPTURED' AND remaining_minor > 0 AND remaining_minor < amount_minor AND captured_at IS NOT NULL AND voided_at IS NULL)
        OR (status = 'FULLY_CAPTURED' AND remaining_minor = 0 AND captured_at IS NOT NULL AND voided_at IS NULL)
        OR (status IN ('VOIDED', 'EXPIRED') AND remaining_minor = 0 AND voided_at IS NOT NULL)
    )
);

CREATE TABLE hold_events (
    hold_event_id        bigserial PRIMARY KEY,
    hold_id              uuid NOT NULL REFERENCES funds_holds(hold_id),
    event_type           text NOT NULL CHECK (event_type IN ('AUTHORIZED', 'PARTIALLY_CAPTURED', 'FULLY_CAPTURED', 'VOIDED', 'EXPIRED')),
    amount_minor         bigint CHECK (amount_minor IS NULL OR amount_minor > 0),
    metadata_json        jsonb,
    created_at           timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE payment_events (
    payment_event_id     bigserial PRIMARY KEY,
    payment_order_id     uuid NOT NULL REFERENCES payment_orders(payment_order_id),
    event_type           text NOT NULL CHECK (event_type IN ('INITIATED', 'AUTHORIZED', 'PARTIALLY_CAPTURED', 'CAPTURED', 'VOIDED', 'FAILED', 'EXPIRED')),
    amount_minor         bigint CHECK (amount_minor IS NULL OR amount_minor > 0),
    metadata_json        jsonb,
    created_at           timestamptz NOT NULL DEFAULT now()
);

COMMENT ON TABLE hold_events IS
    'Append-only hold lifecycle events. Use new event rows instead of mutating prior history.';
COMMENT ON TABLE payment_events IS
    'Append-only payment lifecycle events. Use new event rows instead of mutating prior history.';

-- ============================================================
-- 4. IDEMPOTENCY / AUDIT / OUTBOX / APPROVAL FOUNDATIONS
-- ============================================================

CREATE TABLE idempotency_keys (
    idempotency_key      text PRIMARY KEY,
    request_hash         text NOT NULL,
    status               text NOT NULL DEFAULT 'IN_PROGRESS' CHECK (status IN ('IN_PROGRESS', 'SUCCEEDED', 'FAILED')),
    response_body        jsonb,
    created_at           timestamptz NOT NULL DEFAULT now(),
    completed_at         timestamptz,
    expires_at           timestamptz NOT NULL,
    CHECK (expires_at > created_at),
    CHECK (
        (status = 'IN_PROGRESS' AND completed_at IS NULL)
        OR (status IN ('SUCCEEDED', 'FAILED') AND completed_at IS NOT NULL)
    )
);

COMMENT ON TABLE idempotency_keys IS
    'Authoritative idempotency store for money-moving requests. Do not infer idempotency from business tables.';

CREATE TABLE audit_events (
    audit_id             bigserial PRIMARY KEY,
    actor                text NOT NULL,
    action               text NOT NULL,
    resource_type        text NOT NULL,
    resource_id          text NOT NULL,
    correlation_id       uuid,
    request_id           uuid,
    session_id           uuid,
    trace_id             text,
    before_state_json    jsonb,
    after_state_json     jsonb,
    created_at           timestamptz NOT NULL DEFAULT now(),
    prev_row_hash        bytea,
    row_hash             bytea NOT NULL
);

CREATE TABLE outbox_messages (
    outbox_id            bigserial PRIMARY KEY,
    aggregate_type       text NOT NULL,
    aggregate_id         text NOT NULL,
    event_type           text NOT NULL,
    payload_json         jsonb NOT NULL,
    status               text NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING', 'PUBLISHED', 'FAILED')),
    created_at           timestamptz NOT NULL DEFAULT now(),
    published_at         timestamptz,
    CHECK (
        (status = 'PUBLISHED' AND published_at IS NOT NULL)
        OR (status IN ('PENDING', 'FAILED') AND published_at IS NULL)
    )
);

CREATE TABLE approvals (
    approval_id          uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    reference_type       text NOT NULL,
    reference_id         text NOT NULL,
    approval_type        text NOT NULL,
    status               text NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING', 'APPROVED', 'REJECTED')),
    requested_by         text NOT NULL,
    decided_by           text,
    decision_note        text,
    created_at           timestamptz NOT NULL DEFAULT now(),
    decided_at           timestamptz,
    CHECK (
        (status = 'PENDING' AND decided_by IS NULL AND decided_at IS NULL)
        OR (status IN ('APPROVED', 'REJECTED') AND decided_by IS NOT NULL AND decided_at IS NOT NULL)
    )
);

COMMENT ON TABLE audit_events IS
    'Append-only audit trail. Historical audit rows must not be updated or deleted.';

-- ============================================================
-- 5. APPEND-ONLY PROTECTION FOR EVENT / AUDIT / LEDGER TABLES
-- ============================================================

CREATE OR REPLACE FUNCTION forbid_append_only_mutation()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION 'Append-only table % does not allow % operations', TG_TABLE_NAME, TG_OP;
END;
$$;

CREATE TRIGGER tr_ledger_journals_append_only
    BEFORE UPDATE OR DELETE ON ledger_journals
    FOR EACH ROW
    EXECUTE FUNCTION forbid_append_only_mutation();

CREATE TRIGGER tr_ledger_postings_append_only
    BEFORE UPDATE OR DELETE ON ledger_postings
    FOR EACH ROW
    EXECUTE FUNCTION forbid_append_only_mutation();

CREATE TRIGGER tr_hold_events_append_only
    BEFORE UPDATE OR DELETE ON hold_events
    FOR EACH ROW
    EXECUTE FUNCTION forbid_append_only_mutation();

CREATE TRIGGER tr_payment_events_append_only
    BEFORE UPDATE OR DELETE ON payment_events
    FOR EACH ROW
    EXECUTE FUNCTION forbid_append_only_mutation();

CREATE TRIGGER tr_audit_events_append_only
    BEFORE UPDATE OR DELETE ON audit_events
    FOR EACH ROW
    EXECUTE FUNCTION forbid_append_only_mutation();

-- ============================================================
-- 6. HOT-PATH INDEXES
-- ============================================================

CREATE INDEX ix_customer_accounts_customer
    ON customer_accounts(customer_id);

CREATE INDEX ix_customer_accounts_product
    ON customer_accounts(product_id);

CREATE INDEX ix_ledger_journals_reference
    ON ledger_journals(reference_type, reference_id, created_at DESC);

CREATE INDEX ix_ledger_journals_reversal
    ON ledger_journals(reversal_of_journal_id)
    WHERE reversal_of_journal_id IS NOT NULL;

CREATE INDEX ix_ledger_postings_journal
    ON ledger_postings(journal_id, created_at);

CREATE INDEX ix_ledger_postings_ledger_account
    ON ledger_postings(ledger_account_id, created_at);

CREATE INDEX ix_ledger_postings_customer_account
    ON ledger_postings(customer_account_id, created_at)
    WHERE customer_account_id IS NOT NULL;

CREATE INDEX ix_payment_orders_payer_status
    ON payment_orders(payer_account_id, status, created_at DESC);

CREATE INDEX ix_payment_orders_payee_status
    ON payment_orders(payee_account_id, status, created_at DESC)
    WHERE payee_account_id IS NOT NULL;

CREATE INDEX ix_funds_holds_account_status
    ON funds_holds(customer_account_id, status, created_at DESC);

CREATE INDEX ix_hold_events_hold_created
    ON hold_events(hold_id, created_at DESC);

CREATE INDEX ix_payment_events_order_created
    ON payment_events(payment_order_id, created_at DESC);

CREATE INDEX ix_idempotency_keys_expires_at
    ON idempotency_keys(expires_at);

CREATE INDEX ix_audit_events_resource
    ON audit_events(resource_type, resource_id, created_at DESC);

CREATE INDEX ix_audit_events_request_id
    ON audit_events(request_id)
    WHERE request_id IS NOT NULL;

CREATE INDEX ix_outbox_messages_status_created
    ON outbox_messages(status, created_at);

CREATE INDEX ix_approvals_reference
    ON approvals(reference_type, reference_id, created_at DESC);

CREATE INDEX ix_approvals_status_created
    ON approvals(status, created_at DESC);