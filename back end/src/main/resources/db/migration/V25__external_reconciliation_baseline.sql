-- ============================================================
-- CoreBank Slice 3 - External Reconciliation Baseline
--
-- Purpose:
-- - add immutable external settlement statement evidence tables
-- - harden latest-version lookup and matching lookup indexes
-- - extend reconciliation break taxonomy for external matching
-- ============================================================

CREATE TABLE IF NOT EXISTS external_settlement_statements (
    statement_id         uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    statement_ref        text NOT NULL,
    version_no           integer NOT NULL CHECK (version_no > 0),
    is_latest            boolean NOT NULL DEFAULT false,
    provider             text NOT NULL,
    statement_date       date NOT NULL,
    entry_count          integer NOT NULL CHECK (entry_count >= 0),
    raw_payload_json     jsonb NOT NULL DEFAULT '{}'::jsonb,
    imported_by          text NOT NULL,
    imported_at          timestamptz NOT NULL DEFAULT now(),
    created_at           timestamptz NOT NULL DEFAULT now(),
    CHECK (length(trim(statement_ref)) > 0),
    CHECK (length(trim(provider)) > 0),
    UNIQUE (statement_ref, version_no)
);

CREATE UNIQUE INDEX IF NOT EXISTS ux_ext_stmt_single_latest
    ON external_settlement_statements(statement_ref)
    WHERE is_latest = true;

CREATE INDEX IF NOT EXISTS ix_ext_stmt_ref_version_desc
    ON external_settlement_statements(statement_ref, version_no DESC);

CREATE TABLE IF NOT EXISTS external_settlement_entries (
    external_entry_id    bigserial PRIMARY KEY,
    statement_id         uuid NOT NULL REFERENCES external_settlement_statements(statement_id) ON DELETE CASCADE,
    entry_order          integer NOT NULL CHECK (entry_order > 0),
    external_ref         text,
    reference_type       text NOT NULL,
    reference_id         text NOT NULL,
    currency             char(3) NOT NULL,
    amount_minor         bigint NOT NULL CHECK (amount_minor > 0),
    status               text NOT NULL,
    raw_payload_json     jsonb NOT NULL DEFAULT '{}'::jsonb,
    created_at           timestamptz NOT NULL DEFAULT now(),
    CHECK (length(trim(reference_type)) > 0),
    CHECK (length(trim(reference_id)) > 0),
    CHECK (length(trim(status)) > 0),
    UNIQUE (statement_id, entry_order)
);

CREATE INDEX IF NOT EXISTS ix_ext_entries_match_key
    ON external_settlement_entries(statement_id, reference_type, reference_id, currency);

CREATE INDEX IF NOT EXISTS ix_ext_entries_statement
    ON external_settlement_entries(statement_id, entry_order);

CREATE INDEX IF NOT EXISTS ix_ledger_journals_ref_id_currency_created
    ON ledger_journals(reference_type, reference_id, currency, created_at DESC);

DO $$
DECLARE
    break_type_constraint text;
BEGIN
    SELECT c.conname
    INTO break_type_constraint
    FROM pg_constraint c
    WHERE c.conrelid = 'reconciliation_breaks'::regclass
      AND c.contype = 'c'
      AND pg_get_constraintdef(c.oid) ILIKE '%break_type%'
    ORDER BY c.conname
    LIMIT 1;

    IF break_type_constraint IS NOT NULL THEN
        EXECUTE format('ALTER TABLE reconciliation_breaks DROP CONSTRAINT %I', break_type_constraint);
    END IF;
END;
$$;

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1
        FROM pg_constraint
        WHERE conname = 'ck_reconciliation_breaks_break_type'
    ) THEN
        ALTER TABLE reconciliation_breaks
            ADD CONSTRAINT ck_reconciliation_breaks_break_type
            CHECK (
                break_type IN (
                    'AMOUNT_MISMATCH',
                    'MISSING_ENTRY',
                    'DUPLICATE_ENTRY',
                    'LATE_EVENT',
                    'UNKNOWN',
                    'ORPHAN_EXTERNAL',
                    'MISSING_EXTERNAL',
                    'STATUS_MISMATCH',
                    'AMBIGUOUS_MATCH',
                    'DUPLICATE_EXTERNAL',
                    'DUPLICATE_INTERNAL'
                )
            );
    END IF;
END;
$$;
