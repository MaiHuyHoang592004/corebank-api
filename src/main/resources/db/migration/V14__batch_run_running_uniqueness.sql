-- ============================================================
-- CoreBank Phase 2 - Batch Run Registry Hardening
--
-- Purpose:
-- - allow recurring historical runs for the same batch name
-- - enforce at most one RUNNING instance per batch name
--
-- Notes:
-- - replaces broad unique(batch_name, status) with partial uniqueness
--   scoped to status = 'RUNNING'.
-- ============================================================

ALTER TABLE batch_runs
    DROP CONSTRAINT IF EXISTS batch_runs_batch_name_status_key;

CREATE UNIQUE INDEX IF NOT EXISTS ux_batch_runs_one_running_per_name
    ON batch_runs (batch_name)
    WHERE status = 'RUNNING';
