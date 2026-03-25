-- Outbox claim-recovery and bounded-retry hardening.
-- Keeps existing outbox schema/contract and adds crash-safe claim reclaim behavior.

ALTER TABLE outbox_events
    ADD COLUMN IF NOT EXISTS processing_started_at TIMESTAMPTZ;

CREATE INDEX IF NOT EXISTS idx_outbox_events_processing_started
    ON outbox_events (processing_started_at)
    WHERE status = 'PROCESSING';

CREATE INDEX IF NOT EXISTS idx_outbox_events_failed_retry
    ON outbox_events (status, retry_count, processed_at)
    WHERE status = 'FAILED';

COMMENT ON COLUMN outbox_events.processing_started_at IS
    'Timestamp when worker claims event for publish; used for stale-claim recovery.';

CREATE OR REPLACE FUNCTION get_pending_outbox_events(
    p_limit INTEGER DEFAULT 100,
    p_max_retries INTEGER DEFAULT 3,
    p_retry_backoff_seconds INTEGER DEFAULT 30,
    p_reclaim_timeout_seconds INTEGER DEFAULT 300
) RETURNS SETOF outbox_events AS $$
DECLARE
    v_safe_limit INTEGER;
    v_safe_max_retries INTEGER;
    v_safe_retry_backoff_seconds INTEGER;
    v_safe_reclaim_timeout_seconds INTEGER;
BEGIN
    v_safe_limit := GREATEST(COALESCE(p_limit, 1), 1);
    v_safe_max_retries := GREATEST(COALESCE(p_max_retries, 1), 1);
    v_safe_retry_backoff_seconds := GREATEST(COALESCE(p_retry_backoff_seconds, 0), 0);
    v_safe_reclaim_timeout_seconds := GREATEST(COALESCE(p_reclaim_timeout_seconds, 1), 1);

    -- Reclaim stale in-flight claims (worker crashed / lost heartbeat scenario).
    UPDATE outbox_events
    SET status = 'PENDING',
        processing_started_at = NULL
    WHERE status = 'PROCESSING'
      AND processing_started_at IS NOT NULL
      AND processing_started_at <= CURRENT_TIMESTAMP - make_interval(secs => v_safe_reclaim_timeout_seconds);

    RETURN QUERY
    WITH candidates AS (
        SELECT id
        FROM outbox_events
        WHERE status = 'PENDING'
           OR (
                status = 'FAILED'
                AND retry_count < v_safe_max_retries
                AND (
                    processed_at IS NULL
                    OR processed_at <= CURRENT_TIMESTAMP - make_interval(secs => v_safe_retry_backoff_seconds)
                )
           )
        ORDER BY created_at ASC, id ASC
        LIMIT v_safe_limit
        FOR UPDATE SKIP LOCKED
    ),
    claimed AS (
        UPDATE outbox_events oe
        SET status = 'PROCESSING',
            processing_started_at = CURRENT_TIMESTAMP
        FROM candidates c
        WHERE oe.id = c.id
        RETURNING oe.*
    )
    SELECT * FROM claimed
    ORDER BY created_at ASC, id ASC;
END;
$$ LANGUAGE plpgsql;
