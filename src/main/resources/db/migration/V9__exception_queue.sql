-- ============================================================
-- CoreBank Phase 2 - Exception Queue
--
-- Purpose:
-- - Store failed operations for manual review
-- - Allow operators to retry/resolve exceptions
-- - Track exception lifecycle
--
-- DO NOT MODIFY THIS FILE. CREATE NEW MIGRATION FOR CHANGES.
-- ============================================================

-- ============================================================
-- 1. EXCEPTION QUEUE TABLE
-- ============================================================

CREATE TABLE IF NOT EXISTS exception_queue (
    exception_id        bigserial PRIMARY KEY,
    exception_type      text NOT NULL,
    error_message       text NOT NULL,
    error_detail        text,
    source_service      text NOT NULL,
    source_operation    text NOT NULL,
    payload_json        jsonb,
    status              text NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING', 'IN_PROGRESS', 'RESOLVED', 'RETRIED', 'IGNORED')),
    retry_count         integer NOT NULL DEFAULT 0,
    max_retries         integer NOT NULL DEFAULT 3,
    resolved_by         text,
    resolved_at         timestamptz,
    resolution_note     text,
    created_at          timestamptz NOT NULL DEFAULT now(),
    updated_at          timestamptz NOT NULL DEFAULT now()
);

-- ============================================================
-- 2. INDEXES
-- ============================================================

CREATE INDEX IF NOT EXISTS ix_exception_queue_status
    ON exception_queue(status, created_at DESC);

CREATE INDEX IF NOT EXISTS ix_exception_queue_type
    ON exception_queue(exception_type, status);

CREATE INDEX IF NOT EXISTS ix_exception_queue_source
    ON exception_queue(source_service, source_operation, status);