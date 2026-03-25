-- Minimal saga persistence hardening on top of existing V3 saga tables.
-- Non-authoritative: orchestration coordination only, never source of truth for balances.

ALTER TABLE saga_instances
    ADD COLUMN IF NOT EXISTS version BIGINT NOT NULL DEFAULT 0 CHECK (version >= 0);

ALTER TABLE saga_steps
    ADD COLUMN IF NOT EXISTS step_key TEXT;

CREATE UNIQUE INDEX IF NOT EXISTS ux_saga_steps_step_key
    ON saga_steps (saga_instance_id, step_name, direction, step_key)
    WHERE step_key IS NOT NULL;

CREATE INDEX IF NOT EXISTS ix_saga_steps_status
    ON saga_steps (status, started_at DESC);

COMMENT ON TABLE saga_instances IS 'Non-authoritative saga state for orchestration coordination and resume';
COMMENT ON TABLE saga_steps IS 'Non-authoritative saga step execution log for idempotent retry and auditability';
COMMENT ON COLUMN saga_instances.context_json IS 'Saga context document, never source of truth for money';
COMMENT ON COLUMN saga_instances.version IS 'Optimistic version for safe resume/update flows';
COMMENT ON COLUMN saga_steps.step_key IS 'Optional idempotency key per saga step execution';
