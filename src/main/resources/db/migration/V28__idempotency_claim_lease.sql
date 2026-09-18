-- ============================================================
-- CoreBank — recoverable idempotency claims
--
-- Problem this fixes, observed rather than theorised:
-- Killing the application while money commands were in flight left their
-- idempotency keys at IN_PROGRESS forever. The database rolled the
-- transactions back correctly, so no money was lost or double-posted, but
-- every affected command became permanently unretryable: the same key
-- returned an error on every subsequent attempt, and the maintenance job
-- only ever deleted SUCCEEDED and FAILED rows, so nothing cleared them.
-- Recovery required deleting rows by hand.
--
-- A claim now records when it was taken. A claim older than the lease
-- window belongs to a process that is no longer running, so a retry may
-- take it over. claimed_at is refreshed on every takeover, which is what
-- stops two retries from both deciding a claim is abandoned.
--
-- DO NOT MODIFY THIS FILE. CREATE NEW MIGRATION FOR CHANGES.
-- ============================================================

ALTER TABLE idempotency_keys
    ADD COLUMN IF NOT EXISTS claimed_at timestamptz;

-- Existing rows have only ever been claimed once, at creation.
UPDATE idempotency_keys
SET claimed_at = created_at
WHERE claimed_at IS NULL;

ALTER TABLE idempotency_keys
    ALTER COLUMN claimed_at SET DEFAULT now();

ALTER TABLE idempotency_keys
    ALTER COLUMN claimed_at SET NOT NULL;

-- Takeover and the staleness metric both ask "which IN_PROGRESS claims are
-- older than X", so index exactly that.
CREATE INDEX IF NOT EXISTS ix_idempotency_keys_in_progress_claimed_at
    ON idempotency_keys (claimed_at)
    WHERE status = 'IN_PROGRESS';
