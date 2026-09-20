-- ============================================================
-- CoreBank — deterministic ordering for the ledger hash chain
--
-- Purpose:
-- - give ledger_journals a monotonic append position
-- - make "the previous link in the chain" a well-defined row
--
-- Background:
-- The tamper-evident chain on ledger_journals stores prev_row_hash, and the
-- application resolves the previous link by reading the most recent journal.
-- That read ordered by (created_at DESC, journal_id DESC). created_at defaults
-- to now(), which in PostgreSQL is transaction start time, so every journal
-- written by the same transaction shares a timestamp and the tie is broken by a
-- random UUID. Under that ordering the chain can pick the wrong predecessor and
-- silently fork.
--
-- chain_seq is assigned by a sequence at INSERT time, so it orders appends
-- unambiguously regardless of timestamp ties.
--
-- DO NOT MODIFY THIS FILE. CREATE NEW MIGRATION FOR CHANGES.
-- ============================================================

ALTER TABLE ledger_journals
    ADD COLUMN IF NOT EXISTS chain_seq bigserial;

-- The chain read is always "latest first, limit 1".
CREATE INDEX IF NOT EXISTS ix_ledger_journals_chain_seq
    ON ledger_journals (chain_seq DESC);
