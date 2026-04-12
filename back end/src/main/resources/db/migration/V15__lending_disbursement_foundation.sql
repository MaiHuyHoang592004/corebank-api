-- ============================================================
-- CoreBank Phase 3 - Lending Foundation (Disbursement Slice)
--
-- Purpose:
-- - introduce minimal lending persistence for loan disbursement
-- - keep migration additive and reversible via forward-fix only
-- - preserve financial auditability with append-only loan events
--
-- Scope intentionally excludes repayment allocation logic.
-- ============================================================

CREATE TABLE loan_contracts (
    contract_id                   uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    borrower_account_id           uuid NOT NULL REFERENCES customer_accounts(customer_account_id),
    product_id                    uuid NOT NULL REFERENCES bank_products(product_id),
    product_version_id            uuid NOT NULL,
    principal_amount_minor        bigint NOT NULL CHECK (principal_amount_minor > 0),
    outstanding_principal_minor   bigint NOT NULL CHECK (outstanding_principal_minor >= 0),
    currency                      char(3) NOT NULL,
    annual_interest_rate          numeric(10,6) NOT NULL CHECK (annual_interest_rate >= 0 AND annual_interest_rate <= 100),
    term_months                   integer NOT NULL CHECK (term_months > 0),
    disbursed_at                  timestamptz NOT NULL,
    status                        text NOT NULL CHECK (status IN ('ACTIVE', 'CLOSED', 'DEFAULTED')),
    created_at                    timestamptz NOT NULL DEFAULT now(),
    updated_at                    timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE repayment_schedules (
    schedule_id                   bigserial PRIMARY KEY,
    contract_id                   uuid NOT NULL REFERENCES loan_contracts(contract_id) ON DELETE CASCADE,
    installment_no                integer NOT NULL CHECK (installment_no > 0),
    due_date                      date NOT NULL,
    principal_due_minor           bigint NOT NULL CHECK (principal_due_minor >= 0),
    interest_due_minor            bigint NOT NULL CHECK (interest_due_minor >= 0),
    fees_due_minor                bigint NOT NULL DEFAULT 0 CHECK (fees_due_minor >= 0),
    principal_paid_minor          bigint NOT NULL DEFAULT 0 CHECK (principal_paid_minor >= 0),
    interest_paid_minor           bigint NOT NULL DEFAULT 0 CHECK (interest_paid_minor >= 0),
    fees_paid_minor               bigint NOT NULL DEFAULT 0 CHECK (fees_paid_minor >= 0),
    status                        text NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING', 'PARTIALLY_PAID', 'PAID', 'OVERDUE')),
    created_at                    timestamptz NOT NULL DEFAULT now(),
    updated_at                    timestamptz NOT NULL DEFAULT now(),
    UNIQUE (contract_id, installment_no)
);

CREATE TABLE loan_events (
    loan_event_id                 bigserial PRIMARY KEY,
    contract_id                   uuid NOT NULL REFERENCES loan_contracts(contract_id),
    event_type                    text NOT NULL CHECK (event_type IN ('DISBURSED', 'REPAYMENT', 'CLOSED', 'DEFAULTED')),
    amount_minor                  bigint CHECK (amount_minor IS NULL OR amount_minor >= 0),
    metadata_json                 jsonb,
    created_at                    timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX ix_loan_contracts_borrower_status
    ON loan_contracts(borrower_account_id, status, created_at DESC);

CREATE INDEX ix_loan_contracts_product
    ON loan_contracts(product_id, status);

CREATE INDEX ix_repayment_schedules_contract_due
    ON repayment_schedules(contract_id, due_date, installment_no);

CREATE INDEX ix_loan_events_contract_created
    ON loan_events(contract_id, created_at DESC);

CREATE INDEX ix_loan_events_type_created
    ON loan_events(event_type, created_at DESC);

COMMENT ON TABLE loan_contracts IS 'Loan contract state for lending flows; principal truth remains journal-ledger + contract outstanding fields.';
COMMENT ON TABLE repayment_schedules IS 'Installment schedule snapshot generated at disbursement for deterministic repayment processing.';
COMMENT ON TABLE loan_events IS 'Append-only lending domain events; historical events must not be mutated.';