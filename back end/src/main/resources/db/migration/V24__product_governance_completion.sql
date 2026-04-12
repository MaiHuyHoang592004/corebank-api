-- ============================================================
-- CoreBank Phase 5 - Product Governance Completion
--
-- Purpose:
-- - harden product version integrity constraints
-- - add posting rule set tables
-- - backfill minimal product version rows for legacy products
-- ============================================================

-- ============================================================
-- 1. HARDEN BANK_PRODUCT_VERSIONS
-- ============================================================

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1
        FROM pg_constraint
        WHERE conname = 'fk_bank_product_versions_product'
    ) THEN
        ALTER TABLE bank_product_versions
            ADD CONSTRAINT fk_bank_product_versions_product
            FOREIGN KEY (product_id)
            REFERENCES bank_products(product_id);
    END IF;
END;
$$;

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1
        FROM pg_constraint
        WHERE conname = 'ck_bank_product_versions_effective_range'
    ) THEN
        ALTER TABLE bank_product_versions
            ADD CONSTRAINT ck_bank_product_versions_effective_range
            CHECK (effective_to IS NULL OR effective_to > effective_from);
    END IF;
END;
$$;

CREATE UNIQUE INDEX IF NOT EXISTS ux_bank_product_versions_single_active
    ON bank_product_versions(product_id)
    WHERE status = 'ACTIVE';

CREATE INDEX IF NOT EXISTS ix_bank_product_versions_product_status_effective
    ON bank_product_versions(product_id, status, effective_from DESC);

-- ============================================================
-- 2. POSTING RULE GOVERNANCE TABLES
-- ============================================================

CREATE TABLE IF NOT EXISTS posting_rule_sets (
    posting_rule_set_id      uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    product_version_id       uuid NOT NULL REFERENCES bank_product_versions(product_version_id),
    rule_set_name            text NOT NULL,
    status                   text NOT NULL DEFAULT 'DRAFT' CHECK (status IN ('DRAFT', 'ACTIVE', 'RETIRED')),
    created_at               timestamptz NOT NULL DEFAULT now(),
    updated_at               timestamptz NOT NULL DEFAULT now(),
    UNIQUE (product_version_id, rule_set_name)
);

CREATE TABLE IF NOT EXISTS posting_rule_lines (
    posting_rule_line_id     bigserial PRIMARY KEY,
    posting_rule_set_id      uuid NOT NULL REFERENCES posting_rule_sets(posting_rule_set_id) ON DELETE CASCADE,
    line_no                  integer NOT NULL CHECK (line_no > 0),
    entry_side               text NOT NULL CHECK (entry_side IN ('D', 'C')),
    posting_target_type      text NOT NULL CHECK (posting_target_type IN ('LEDGER_ACCOUNT', 'CUSTOMER_ACCOUNT')),
    posting_target_ref       text NOT NULL,
    amount_expression        text NOT NULL,
    created_at               timestamptz NOT NULL DEFAULT now(),
    UNIQUE (posting_rule_set_id, line_no)
);

CREATE INDEX IF NOT EXISTS ix_posting_rule_sets_product_version
    ON posting_rule_sets(product_version_id, status);

CREATE INDEX IF NOT EXISTS ix_posting_rule_lines_rule_set
    ON posting_rule_lines(posting_rule_set_id, line_no);

-- ============================================================
-- 3. LEGACY BACKFILL FOR PRODUCT VERSIONS
-- ============================================================

INSERT INTO bank_product_versions (
    product_version_id,
    product_id,
    version_no,
    effective_from,
    effective_to,
    status,
    configuration_json,
    created_at
)
SELECT
    gen_random_uuid(),
    bp.product_id,
    1,
    COALESCE(bp.created_at, now()),
    NULL,
    CASE WHEN bp.status = 'ACTIVE' THEN 'ACTIVE' ELSE 'DRAFT' END,
    jsonb_build_object(
        'source', 'legacy-backfill',
        'productCode', bp.product_code,
        'productType', bp.product_type
    ),
    now()
FROM bank_products bp
WHERE NOT EXISTS (
    SELECT 1
    FROM bank_product_versions bpv
    WHERE bpv.product_id = bp.product_id
);
