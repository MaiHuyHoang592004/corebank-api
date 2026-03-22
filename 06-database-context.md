# 06. Database Context

## Database philosophy
The database is not just storage.
For this project, PostgreSQL is used as:
- source of truth for money
- enforcement layer for data integrity
- atomic transaction engine
- long-term append-only history store

## Key design choices

### 1. Double-entry accounting
All financial mutations must be represented as balanced journal postings:
- total debit = total credit
- no mutation of posted history
- reversal by compensating journal only

### 2. Two balance concepts
Each bookable account may have:
- **posted balance** = accounting truth
- **available balance** = spendable balance

These must not be confused.

### 3. Append-only financial history
The following are treated as append-only:
- ledger journals
- ledger postings
- audit events
- hold events

### 4. Read model is not financial truth
Read models/snapshots are for:
- UI
- reporting
- faster reads

They must never replace ledger truth.

### 5. Partitioning is required for scale
Historical/event-heavy tables should be partitioned by time:
- ledger_journals
- ledger_postings
- audit_events

### 6. Product config must be versioned
Never change the meaning of old contracts by editing a current config row.

### 7. Idempotency must be centralized
Every money-moving write command must have deterministic idempotency support.

## Suggested PostgreSQL schema namespaces
Recommended logical separation:
- iam
- customer
- product
- account
- ledger
- payment
- deposit
- lending
- ops
- integration
- reporting
- audit

Even if V1 keeps everything in one schema, code should still think in these domain boundaries.

## Migration strategy
Use **Flyway** with SQL-first migrations.

Recommended sequence:
1. identity/system configs
2. customer/KYC
3. product/versioning
4. ledger/COA
5. accounts/balances
6. payments/holds
7. deposits
8. lending
9. approvals/limits/ops
10. integration/audit/outbox
11. partitions/read models/hardening
