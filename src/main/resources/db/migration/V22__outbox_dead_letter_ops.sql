-- Outbox dead-letter operational visibility.
-- Keeps outbox write-path unchanged and captures exhausted retry events for operators.

CREATE TABLE IF NOT EXISTS outbox_dead_letters (
    dead_letter_id BIGSERIAL PRIMARY KEY,
    outbox_event_id BIGINT NOT NULL UNIQUE,
    aggregate_type VARCHAR(100) NOT NULL,
    aggregate_id VARCHAR(100) NOT NULL,
    event_type VARCHAR(100) NOT NULL,
    event_data JSONB NOT NULL,
    retry_count INTEGER NOT NULL,
    last_error TEXT,
    dead_lettered_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_outbox_dead_letters_dead_lettered_at
    ON outbox_dead_letters (dead_lettered_at DESC);

CREATE INDEX IF NOT EXISTS idx_outbox_dead_letters_event_type
    ON outbox_dead_letters (event_type, dead_lettered_at DESC);

COMMENT ON TABLE outbox_dead_letters IS
    'Operational dead-letter log for outbox events that exhausted retry attempts.';
COMMENT ON COLUMN outbox_dead_letters.outbox_event_id IS
    'Original outbox event id that exhausted retries.';
