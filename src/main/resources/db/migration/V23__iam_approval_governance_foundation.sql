-- ============================================================
-- CoreBank Phase 5 - IAM + Approval Governance Foundation
--
-- Purpose:
-- - add operator IAM role/permission tables
-- - extend approvals to operation-driven governance
-- - keep backward compatibility with additive-only schema changes
-- ============================================================

-- ============================================================
-- 1. IAM TABLES
-- ============================================================

CREATE TABLE IF NOT EXISTS iam_staff_users (
    user_id          uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    username         text NOT NULL UNIQUE,
    display_name     text,
    status           text NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'INACTIVE')),
    created_at       timestamptz NOT NULL DEFAULT now(),
    updated_at       timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS iam_roles (
    role_id          uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    role_code        text NOT NULL UNIQUE,
    role_name        text NOT NULL,
    status           text NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'INACTIVE')),
    created_at       timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS iam_permissions (
    permission_id    uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    permission_code  text NOT NULL UNIQUE,
    description      text,
    created_at       timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS iam_user_roles (
    user_id          uuid NOT NULL REFERENCES iam_staff_users(user_id) ON DELETE CASCADE,
    role_id          uuid NOT NULL REFERENCES iam_roles(role_id) ON DELETE CASCADE,
    assigned_at      timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (user_id, role_id)
);

CREATE TABLE IF NOT EXISTS iam_role_permissions (
    role_id          uuid NOT NULL REFERENCES iam_roles(role_id) ON DELETE CASCADE,
    permission_id    uuid NOT NULL REFERENCES iam_permissions(permission_id) ON DELETE CASCADE,
    granted_at       timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (role_id, permission_id)
);

CREATE INDEX IF NOT EXISTS ix_iam_staff_users_username
    ON iam_staff_users(username);

CREATE INDEX IF NOT EXISTS ix_iam_user_roles_role
    ON iam_user_roles(role_id);

CREATE INDEX IF NOT EXISTS ix_iam_role_permissions_permission
    ON iam_role_permissions(permission_id);

-- ============================================================
-- 2. APPROVAL TABLE EXTENSIONS (ADDITIVE-ONLY)
-- ============================================================

ALTER TABLE approvals
    ADD COLUMN IF NOT EXISTS operation_type text;

ALTER TABLE approvals
    ADD COLUMN IF NOT EXISTS operation_payload_json jsonb NOT NULL DEFAULT '{}'::jsonb;

ALTER TABLE approvals
    ADD COLUMN IF NOT EXISTS execution_status text NOT NULL DEFAULT 'NOT_EXECUTED';

ALTER TABLE approvals
    ADD COLUMN IF NOT EXISTS executed_by text;

ALTER TABLE approvals
    ADD COLUMN IF NOT EXISTS executed_at timestamptz;

ALTER TABLE approvals
    ADD COLUMN IF NOT EXISTS expires_at timestamptz;

ALTER TABLE approvals
    ADD COLUMN IF NOT EXISTS decision_reason text;

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1
        FROM pg_constraint
        WHERE conname = 'ck_approvals_execution_status'
    ) THEN
        ALTER TABLE approvals
            ADD CONSTRAINT ck_approvals_execution_status
            CHECK (execution_status IN ('NOT_EXECUTED', 'EXECUTED'));
    END IF;
END;
$$;

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1
        FROM pg_constraint
        WHERE conname = 'ck_approvals_execution_fields'
    ) THEN
        ALTER TABLE approvals
            ADD CONSTRAINT ck_approvals_execution_fields
            CHECK (
                (execution_status = 'NOT_EXECUTED' AND executed_by IS NULL AND executed_at IS NULL)
                OR
                (execution_status = 'EXECUTED' AND executed_by IS NOT NULL AND executed_at IS NOT NULL)
            );
    END IF;
END;
$$;

CREATE INDEX IF NOT EXISTS ix_approvals_operation_status_created
    ON approvals(operation_type, status, created_at DESC);

CREATE INDEX IF NOT EXISTS ix_approvals_execution_status_created
    ON approvals(execution_status, created_at DESC);

-- ============================================================
-- 3. ROLE / PERMISSION BASELINE SEED
-- ============================================================

INSERT INTO iam_roles (role_code, role_name, status)
VALUES
    ('ROLE_ADMIN', 'Administrator', 'ACTIVE'),
    ('ROLE_OPS', 'Operations', 'ACTIVE'),
    ('ROLE_MAKER', 'Maker', 'ACTIVE'),
    ('ROLE_APPROVER', 'Approver', 'ACTIVE')
ON CONFLICT (role_code) DO UPDATE
SET role_name = EXCLUDED.role_name,
    status = EXCLUDED.status;

INSERT INTO iam_permissions (permission_code, description)
VALUES
    ('APPROVAL_CREATE', 'Create approval requests'),
    ('APPROVAL_DECIDE', 'Approve or reject approval requests'),
    ('APPROVAL_EXECUTE', 'Execute approved operations'),
    ('PRODUCT_GOVERNANCE_READ', 'Read product governance state'),
    ('PRODUCT_GOVERNANCE_WRITE', 'Modify product governance state')
ON CONFLICT (permission_code) DO UPDATE
SET description = EXCLUDED.description;

INSERT INTO iam_role_permissions (role_id, permission_id)
SELECT r.role_id, p.permission_id
FROM iam_roles r
JOIN iam_permissions p ON p.permission_code IN (
    'APPROVAL_CREATE',
    'APPROVAL_DECIDE',
    'APPROVAL_EXECUTE',
    'PRODUCT_GOVERNANCE_READ',
    'PRODUCT_GOVERNANCE_WRITE'
)
WHERE r.role_code = 'ROLE_ADMIN'
ON CONFLICT (role_id, permission_id) DO NOTHING;

INSERT INTO iam_role_permissions (role_id, permission_id)
SELECT r.role_id, p.permission_id
FROM iam_roles r
JOIN iam_permissions p ON p.permission_code IN (
    'APPROVAL_EXECUTE',
    'PRODUCT_GOVERNANCE_READ',
    'PRODUCT_GOVERNANCE_WRITE'
)
WHERE r.role_code = 'ROLE_OPS'
ON CONFLICT (role_id, permission_id) DO NOTHING;

INSERT INTO iam_role_permissions (role_id, permission_id)
SELECT r.role_id, p.permission_id
FROM iam_roles r
JOIN iam_permissions p ON p.permission_code = 'APPROVAL_CREATE'
WHERE r.role_code = 'ROLE_MAKER'
ON CONFLICT (role_id, permission_id) DO NOTHING;

INSERT INTO iam_role_permissions (role_id, permission_id)
SELECT r.role_id, p.permission_id
FROM iam_roles r
JOIN iam_permissions p ON p.permission_code = 'APPROVAL_DECIDE'
WHERE r.role_code = 'ROLE_APPROVER'
ON CONFLICT (role_id, permission_id) DO NOTHING;
