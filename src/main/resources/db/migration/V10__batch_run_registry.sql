-- ============================================================
-- CoreBank Phase 2 - Batch Run Registry
--
-- Purpose:
-- - Track batch job execution status
-- - Support idempotent batch execution
-- - Provide visibility into batch operations
-- - Support EOD/BOD workflow
--
-- DO NOT MODIFY THIS FILE. CREATE NEW MIGRATION FOR CHANGES.
-- ============================================================

-- ============================================================
-- 1. BATCH RUNS TABLE
-- ============================================================

CREATE TABLE IF NOT EXISTS batch_runs (
    run_id              bigserial PRIMARY KEY,
    batch_name          text NOT NULL,
    batch_type          text NOT NULL,
    status              text NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING', 'RUNNING', 'COMPLETED', 'FAILED')),
    started_at          timestamptz,
    completed_at        timestamptz,
    parameters_json     jsonb,
    result_json         jsonb,
    error_message       text,
    retry_count         integer NOT NULL DEFAULT 0,
    created_at          timestamptz NOT NULL DEFAULT now(),
    updated_at          timestamptz NOT NULL DEFAULT now(),
    UNIQUE (batch_name, status) DEFERRABLE INITIALLY DEFERRED
);

-- ============================================================
-- 2. INDEXES
-- ============================================================

CREATE INDEX IF NOT EXISTS ix_batch_runs_name
    ON batch_runs(batch_name, status);

CREATE INDEX IF NOT EXISTS ix_batch_runs_type
    ON batch_runs(batch_type, status);

CREATE INDEX IF NOT EXISTS ix_batch_runs_status
    ON batch_runs(status, created_at DESC);