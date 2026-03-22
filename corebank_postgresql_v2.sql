-- ============================================================
-- CoreBank PostgreSQL v8 Hardening Patch
-- Additive patch to move a portfolio-grade banking schema closer
-- to production-ready architecture.
--
-- Focus areas:
-- 1. partitioning-ready large tables
-- 2. saga/orchestration state
-- 3. CQRS/read-model readiness
-- 4. field-level encrypted payload storage
-- 5. hard/soft limit management
-- 6. hot-account slotting
-- 7. product versioning
-- 8. system runtime control / EOD hardening
-- ============================================================

CREATE EXTENSION IF NOT EXISTS pgcrypto;

-- ============================================================
-- 0. SYSTEM RUNTIME CONTROL
-- ============================================================

CREATE TABLE IF NOT EXISTS system_configs (
  config_key         text PRIMARY KEY,
  config_value       jsonb NOT NULL,
  description        text,
  updated_at         timestamptz NOT NULL DEFAULT now(),
  updated_by         text
);

INSERT INTO system_configs(config_key, config_value, description)
VALUES
  ('runtime_mode', '{"status":"ONLINE"}'::jsonb, 'ONLINE | EOD_RUNNING | MAINTENANCE | READ_ONLY'),
  ('eod_control', '{"is_open":true,"business_date":null}'::jsonb, 'Business-date and EOD/BOD control flags')
ON CONFLICT (config_key) DO NOTHING;

-- ============================================================
-- 1. PARTITIONING-READY LARGE TABLES
-- ============================================================
-- NOTE:
-- Converting existing large tables to partitioned parents normally needs
-- a data migration window. The objects below define the target pattern.
-- New deployments should use these partitioned tables directly.

CREATE TABLE IF NOT EXISTS ledger_journals_p (
  journal_id               uuid NOT NULL,
  journal_type             text NOT NULL,
  reference_type           text NOT NULL,
  reference_id             uuid NOT NULL,
  currency                 char(3) NOT NULL,
  reversal_of_journal_id   uuid,
  created_by_actor         text,
  correlation_id           uuid,
  created_at               timestamptz NOT NULL DEFAULT now(),
  prev_row_hash            bytea,
  row_hash                 bytea NOT NULL,
  PRIMARY KEY (journal_id, created_at)
) PARTITION BY RANGE (created_at);

CREATE TABLE IF NOT EXISTS ledger_postings_p (
  posting_id               bigserial,
  journal_id               uuid NOT NULL,
  created_at               timestamptz NOT NULL DEFAULT now(),
  ledger_account_id        uuid NOT NULL,
  entry_side               text NOT NULL CHECK (entry_side IN ('D','C')),
  amount_minor             bigint NOT NULL CHECK (amount_minor > 0),
  currency                 char(3) NOT NULL,
  PRIMARY KEY (posting_id, created_at)
) PARTITION BY RANGE (created_at);

CREATE TABLE IF NOT EXISTS audit_events_p (
  audit_id                 bigserial,
  actor                    text NOT NULL,
  action                   text NOT NULL,
  resource_type            text NOT NULL,
  resource_id              uuid NOT NULL,
  correlation_id           uuid,
  request_id               uuid,
  session_id               uuid,
  trace_id                 text,
  before_state_json        jsonb,
  after_state_json         jsonb,
  created_at               timestamptz NOT NULL DEFAULT now(),
  prev_row_hash            bytea,
  row_hash                 bytea NOT NULL,
  PRIMARY KEY (audit_id, created_at)
) PARTITION BY RANGE (created_at);

-- Example monthly partitions.
CREATE TABLE IF NOT EXISTS ledger_journals_2026_01 PARTITION OF ledger_journals_p
  FOR VALUES FROM ('2026-01-01') TO ('2026-02-01');
CREATE TABLE IF NOT EXISTS ledger_journals_2026_02 PARTITION OF ledger_journals_p
  FOR VALUES FROM ('2026-02-01') TO ('2026-03-01');
CREATE TABLE IF NOT EXISTS ledger_postings_2026_01 PARTITION OF ledger_postings_p
  FOR VALUES FROM ('2026-01-01') TO ('2026-02-01');
CREATE TABLE IF NOT EXISTS ledger_postings_2026_02 PARTITION OF ledger_postings_p
  FOR VALUES FROM ('2026-02-01') TO ('2026-03-01');
CREATE TABLE IF NOT EXISTS audit_events_2026_01 PARTITION OF audit_events_p
  FOR VALUES FROM ('2026-01-01') TO ('2026-02-01');
CREATE TABLE IF NOT EXISTS audit_events_2026_02 PARTITION OF audit_events_p
  FOR VALUES FROM ('2026-02-01') TO ('2026-03-01');

CREATE INDEX IF NOT EXISTS ix_ledger_journals_p_ref ON ledger_journals_p(reference_type, reference_id, created_at DESC);
CREATE INDEX IF NOT EXISTS ix_ledger_postings_p_journal ON ledger_postings_p(journal_id, created_at DESC);
CREATE INDEX IF NOT EXISTS ix_audit_events_p_resource ON audit_events_p(resource_type, resource_id, created_at DESC);

-- ============================================================
-- 2. SAGA / ORCHESTRATION STATE
-- ============================================================

CREATE TABLE IF NOT EXISTS saga_instances (
  saga_instance_id         uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  saga_type                text NOT NULL,
  business_key             text NOT NULL,
  status                   text NOT NULL CHECK (status IN ('PENDING','RUNNING','COMPLETED','FAILED','COMPENSATING','COMPENSATED','TIMED_OUT')),
  current_step             text,
  started_at               timestamptz NOT NULL DEFAULT now(),
  last_updated_at          timestamptz NOT NULL DEFAULT now(),
  deadline_at              timestamptz,
  correlation_id           uuid,
  context_json             jsonb NOT NULL DEFAULT '{}'::jsonb,
  error_json               jsonb,
  UNIQUE (saga_type, business_key)
);

CREATE TABLE IF NOT EXISTS saga_steps (
  saga_step_id             bigserial PRIMARY KEY,
  saga_instance_id         uuid NOT NULL REFERENCES saga_instances(saga_instance_id) ON DELETE CASCADE,
  step_no                  integer NOT NULL,
  step_name                text NOT NULL,
  direction                text NOT NULL CHECK (direction IN ('FORWARD','COMPENSATE')),
  status                   text NOT NULL CHECK (status IN ('PENDING','RUNNING','SUCCEEDED','FAILED','SKIPPED')),
  started_at               timestamptz,
  finished_at              timestamptz,
  request_payload_json     jsonb,
  response_payload_json    jsonb,
  error_json               jsonb,
  UNIQUE (saga_instance_id, step_no, direction)
);

CREATE INDEX IF NOT EXISTS ix_saga_instances_status ON saga_instances(status, last_updated_at DESC);
CREATE INDEX IF NOT EXISTS ix_saga_steps_saga ON saga_steps(saga_instance_id, step_no);

-- ============================================================
-- 3. CQRS / READ-MODEL READINESS
-- ============================================================

CREATE TABLE IF NOT EXISTS account_balance_snapshots (
  snapshot_id              bigserial PRIMARY KEY,
  customer_account_id      uuid NOT NULL,
  business_date            date NOT NULL,
  posted_balance_minor     bigint NOT NULL,
  available_balance_minor  bigint NOT NULL,
  source_journal_id        uuid,
  created_at               timestamptz NOT NULL DEFAULT now(),
  UNIQUE (customer_account_id, business_date)
);

CREATE INDEX IF NOT EXISTS ix_account_balance_snapshots_account_date
  ON account_balance_snapshots(customer_account_id, business_date DESC);

-- Read-model projection for teller/dashboard/search-heavy reads.
CREATE TABLE IF NOT EXISTS account_read_models (
  customer_account_id      uuid PRIMARY KEY,
  account_number           text NOT NULL,
  customer_id              uuid NOT NULL,
  product_id               uuid,
  account_status           text NOT NULL,
  posted_balance_minor     bigint NOT NULL,
  available_balance_minor  bigint NOT NULL,
  last_transaction_at      timestamptz,
  risk_band                text,
  updated_at               timestamptz NOT NULL DEFAULT now()
);

-- ============================================================
-- 4. FIELD-LEVEL ENCRYPTION STORAGE
-- ============================================================
-- Application should encrypt before insert. DB stores ciphertext + metadata.

CREATE TABLE IF NOT EXISTS encrypted_customer_secrets (
  secret_id                uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  customer_id              uuid NOT NULL,
  secret_type              text NOT NULL CHECK (secret_type IN ('NATIONAL_ID','TAX_ID','ADDRESS','PHONE','EMAIL','BIOMETRIC_REF')),
  cipher_text              bytea NOT NULL,
  key_version              integer NOT NULL,
  encryption_algorithm     text NOT NULL,
  nonce                    bytea,
  created_at               timestamptz NOT NULL DEFAULT now(),
  UNIQUE (customer_id, secret_type)
);

CREATE INDEX IF NOT EXISTS ix_encrypted_customer_secrets_customer
  ON encrypted_customer_secrets(customer_id);

-- ============================================================
-- 5. LIMITS MANAGEMENT (HARD / SOFT LIMIT)
-- ============================================================

CREATE TABLE IF NOT EXISTS limit_profiles (
  limit_profile_id         uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  profile_code             text NOT NULL UNIQUE,
  profile_name             text NOT NULL,
  status                   text NOT NULL CHECK (status IN ('ACTIVE','RETIRED')),
  created_at               timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS limit_rules (
  limit_rule_id            uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  limit_profile_id         uuid NOT NULL REFERENCES limit_profiles(limit_profile_id) ON DELETE CASCADE,
  limit_scope              text NOT NULL CHECK (limit_scope IN ('CUSTOMER','ACCOUNT','PRODUCT','CHANNEL')),
  limit_type               text NOT NULL CHECK (limit_type IN ('TXN_AMOUNT','DAILY_AMOUNT','DAILY_COUNT','MONTHLY_AMOUNT','MONTHLY_COUNT','FAILED_PIN_COUNT')),
  soft_limit_minor         bigint,
  hard_limit_minor         bigint,
  hard_limit_count         integer,
  window_code              text NOT NULL CHECK (window_code IN ('PER_TXN','DAILY','MONTHLY','ROLLING_24H')),
  currency                 char(3),
  severity_on_soft_breach  text NOT NULL DEFAULT 'WARN' CHECK (severity_on_soft_breach IN ('WARN','REVIEW','BLOCK')),
  created_at               timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS limit_assignments (
  limit_assignment_id      uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  limit_profile_id         uuid NOT NULL REFERENCES limit_profiles(limit_profile_id),
  customer_id              uuid,
  customer_account_id      uuid,
  product_id               uuid,
  channel_code             text,
  created_at               timestamptz NOT NULL DEFAULT now(),
  CHECK (
    (customer_id IS NOT NULL)::integer +
    (customer_account_id IS NOT NULL)::integer +
    (product_id IS NOT NULL)::integer +
    (channel_code IS NOT NULL)::integer >= 1
  )
);

CREATE TABLE IF NOT EXISTS limit_usage_counters (
  limit_usage_counter_id   bigserial PRIMARY KEY,
  limit_assignment_id      uuid NOT NULL REFERENCES limit_assignments(limit_assignment_id) ON DELETE CASCADE,
  limit_rule_id            uuid NOT NULL REFERENCES limit_rules(limit_rule_id) ON DELETE CASCADE,
  window_start_at          timestamptz NOT NULL,
  window_end_at            timestamptz NOT NULL,
  amount_used_minor        bigint NOT NULL DEFAULT 0,
  count_used               integer NOT NULL DEFAULT 0,
  updated_at               timestamptz NOT NULL DEFAULT now(),
  UNIQUE (limit_assignment_id, limit_rule_id, window_start_at)
);

CREATE INDEX IF NOT EXISTS ix_limit_usage_assignment ON limit_usage_counters(limit_assignment_id, window_start_at DESC);

-- ============================================================
-- 6. HOT ACCOUNT SLOT MANAGEMENT
-- ============================================================

CREATE TABLE IF NOT EXISTS hot_account_profiles (
  hot_account_profile_id   uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  ledger_account_id        uuid NOT NULL UNIQUE,
  slot_count               integer NOT NULL CHECK (slot_count > 1),
  selection_strategy       text NOT NULL DEFAULT 'HASH' CHECK (selection_strategy IN ('HASH','ROUND_ROBIN','RANDOM')),
  created_at               timestamptz NOT NULL DEFAULT now(),
  is_active                boolean NOT NULL DEFAULT true
);

CREATE TABLE IF NOT EXISTS ledger_account_balance_slots (
  balance_slot_id          bigserial PRIMARY KEY,
  ledger_account_id        uuid NOT NULL,
  slot_no                  integer NOT NULL,
  posted_balance_minor     bigint NOT NULL DEFAULT 0,
  available_balance_minor  bigint NOT NULL DEFAULT 0,
  updated_at               timestamptz NOT NULL DEFAULT now(),
  UNIQUE (ledger_account_id, slot_no)
);

CREATE INDEX IF NOT EXISTS ix_balance_slots_account ON ledger_account_balance_slots(ledger_account_id, slot_no);

-- ============================================================
-- 7. PRODUCT VERSIONING
-- ============================================================

CREATE TABLE IF NOT EXISTS bank_product_versions (
  product_version_id       uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  product_id               uuid NOT NULL,
  version_no               integer NOT NULL,
  effective_from           timestamptz NOT NULL,
  effective_to             timestamptz,
  status                   text NOT NULL CHECK (status IN ('DRAFT','ACTIVE','RETIRED')),
  configuration_json       jsonb NOT NULL,
  created_at               timestamptz NOT NULL DEFAULT now(),
  UNIQUE (product_id, version_no)
);

CREATE INDEX IF NOT EXISTS ix_bank_product_versions_effective
  ON bank_product_versions(product_id, effective_from DESC);

-- Optional explicit contract binding to frozen product version.
ALTER TABLE loan_contracts
  ADD COLUMN IF NOT EXISTS product_version_id uuid;

ALTER TABLE term_deposit_contracts
  ADD COLUMN IF NOT EXISTS product_version_id uuid;

-- ============================================================
-- 8. RECONCILIATION / OPERATIONS HARDENING
-- ============================================================

CREATE TABLE IF NOT EXISTS reconciliation_breaks (
  reconciliation_break_id  uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  reconciliation_run_id    uuid,
  break_type               text NOT NULL CHECK (break_type IN ('AMOUNT_MISMATCH','MISSING_ENTRY','DUPLICATE_ENTRY','LATE_EVENT','UNKNOWN')),
  reference_type           text NOT NULL,
  reference_id             text NOT NULL,
  severity                 text NOT NULL CHECK (severity IN ('LOW','MEDIUM','HIGH','CRITICAL')),
  status                   text NOT NULL CHECK (status IN ('OPEN','INVESTIGATING','RESOLVED','ACCEPTED_RISK')),
  details_json             jsonb NOT NULL DEFAULT '{}'::jsonb,
  opened_at                timestamptz NOT NULL DEFAULT now(),
  resolved_at              timestamptz
);

CREATE INDEX IF NOT EXISTS ix_reconciliation_breaks_status
  ON reconciliation_breaks(status, opened_at DESC);

-- ============================================================
-- 9. CONCURRENCY / DEADLOCK HARDENING NOTE
-- ============================================================
-- For money-movement functions, lock account rows in deterministic order:
-- always sort target account identifiers ascending before SELECT ... FOR UPDATE.
-- Example pattern inside PL/pgSQL:
--   SELECT customer_account_id
--   FROM customer_accounts
--   WHERE customer_account_id = ANY(v_account_ids)
--   ORDER BY customer_account_id
--   FOR UPDATE;
--
-- This is a code-level rule, not just a schema rule.
-- ============================================================
