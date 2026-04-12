-- ============================================================
-- CoreBank Phase 1 Seed Data
--
-- Scope:
-- - Base bank products (Checking, Savings, Term Deposit)
-- - System ledger accounts (Chart of Accounts)
-- - Reference data for payment flows
--
-- Notes:
-- - Uses fixed UUIDs for deterministic test/demo scenarios
-- - Currency: VND (Vietnamese Dong)
-- - All amounts in minor units (1 VND = 1 unit)
-- ============================================================

-- ============================================================
-- 1. BASE BANK PRODUCTS
-- ============================================================

INSERT INTO bank_products (product_id, product_code, product_name, product_type, currency, status)
VALUES
    ('a1b2c3d4-e5f6-7890-abcd-ef1234567801', 'CHK-VND', 'Checking Account', 'CHECKING', 'VND', 'ACTIVE'),
    ('a1b2c3d4-e5f6-7890-abcd-ef1234567802', 'SAV-VND', 'Savings Account', 'SAVINGS', 'VND', 'ACTIVE'),
    ('a1b2c3d4-e5f6-7890-abcd-ef1234567803', 'TD-VND', 'Term Deposit', 'TERM_DEPOSIT', 'VND', 'ACTIVE')
ON CONFLICT (product_id) DO NOTHING;

-- ============================================================
-- 2. SYSTEM LEDGER ACCOUNTS (Chart of Accounts)
-- ============================================================

-- Asset Accounts (1xxx)
INSERT INTO ledger_accounts (ledger_account_id, account_code, account_name, account_type, currency, is_active)
VALUES
    ('b1c2d3e4-f5a6-7890-bcde-f12345678011', '1011', 'Cash on Hand', 'ASSET', 'VND', true),
    ('b1c2d3e4-f5a6-7890-bcde-f12345678012', '1012', 'Due from Banks', 'ASSET', 'VND', true),
    ('b1c2d3e4-f5a6-7890-bcde-f12345678013', '1013', 'Settlement Account', 'ASSET', 'VND', true),
    ('b1c2d3e4-f5a6-7890-bcde-f12345678014', '1014', 'Merchant Settlement', 'ASSET', 'VND', true)
ON CONFLICT (ledger_account_id) DO NOTHING;

-- Liability Accounts (2xxx)
INSERT INTO ledger_accounts (ledger_account_id, account_code, account_name, account_type, currency, is_active)
VALUES
    ('b1c2d3e4-f5a6-7890-bcde-f12345678021', '2111', 'Customer Deposits', 'LIABILITY', 'VND', true),
    ('b1c2d3e4-f5a6-7890-bcde-f12345678022', '2112', 'Term Deposits', 'LIABILITY', 'VND', true),
    ('b1c2d3e4-f5a6-7890-bcde-f12345678023', '2113', 'Checking Deposits', 'LIABILITY', 'VND', true),
    ('b1c2d3e4-f5a6-7890-bcde-f12345678024', '2114', 'Savings Deposits', 'LIABILITY', 'VND', true)
ON CONFLICT (ledger_account_id) DO NOTHING;

-- Equity Accounts (3xxx)
INSERT INTO ledger_accounts (ledger_account_id, account_code, account_name, account_type, currency, is_active)
VALUES
    ('b1c2d3e4-f5a6-7890-bcde-f12345678031', '3111', 'Share Capital', 'EQUITY', 'VND', true),
    ('b1c2d3e4-f5a6-7890-bcde-f12345678032', '3112', 'Retained Earnings', 'EQUITY', 'VND', true)
ON CONFLICT (ledger_account_id) DO NOTHING;

-- Revenue Accounts (4xxx)
INSERT INTO ledger_accounts (ledger_account_id, account_code, account_name, account_type, currency, is_active)
VALUES
    ('b1c2d3e4-f5a6-7890-bcde-f12345678041', '4111', 'Interest Income', 'REVENUE', 'VND', true),
    ('b1c2d3e4-f5a6-7890-bcde-f12345678042', '4112', 'Fee Income', 'REVENUE', 'VND', true),
    ('b1c2d3e4-f5a6-7890-bcde-f12345678043', '4113', 'Commission Income', 'REVENUE', 'VND', true)
ON CONFLICT (ledger_account_id) DO NOTHING;

-- Expense Accounts (5xxx)
INSERT INTO ledger_accounts (ledger_account_id, account_code, account_name, account_type, currency, is_active)
VALUES
    ('b1c2d3e4-f5a6-7890-bcde-f12345678051', '5111', 'Interest Expense', 'EXPENSE', 'VND', true),
    ('b1c2d3e4-f5a6-7890-bcde-f12345678052', '5112', 'Operating Expense', 'EXPENSE', 'VND', true),
    ('b1c2d3e4-f5a6-7890-bcde-f12345678053', '5113', 'Provision for Losses', 'EXPENSE', 'VND', true)
ON CONFLICT (ledger_account_id) DO NOTHING;

-- ============================================================
-- 3. SYSTEM RUNTIME CONFIGURATION (already in V2, but ensure)
-- ============================================================

INSERT INTO system_configs (config_key, config_value, description)
VALUES
    ('runtime_mode', '{"status":"RUNNING"}'::jsonb, 'RUNNING | EOD_LOCK | MAINTENANCE | READ_ONLY'),
    ('eod_control', '{"is_open":true,"business_date":null}'::jsonb, 'Business date and EOD/BOD control flags')
ON CONFLICT (config_key) DO NOTHING;