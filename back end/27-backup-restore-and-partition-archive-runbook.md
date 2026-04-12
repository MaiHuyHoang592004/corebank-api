# 27. Backup/Restore and Partition Archive Runbook

## Purpose and boundaries
This runbook provides a safe baseline for:
- logical backup and restore rehearsal
- partition archive readiness review

Boundaries:
- PostgreSQL remains the authoritative source of truth for money and state.
- This runbook does **not** change business semantics, posting behavior, or runtime write paths.
- Archive execution (detach/drop/compress) is intentionally out of scope in this phase.

## Backup procedure (logical dump template)
Prerequisites:
- operator has read access to the target database
- destination storage path has enough free space

Template command:

```bash
pg_dump \
  --format=custom \
  --compress=9 \
  --no-owner \
  --no-privileges \
  --file=corebank_backup_$(date +%Y%m%d_%H%M%S).dump \
  --dbname="${COREBANK_DB_URL}"
```

Recommended metadata to capture with the backup artifact:
- environment name
- backup timestamp (UTC)
- application commit SHA
- Flyway schema version

## Restore procedure (scratch/test environment)
Prerequisites:
- restore target must be non-production (scratch/test)
- target database is empty or explicitly disposable

Template commands:

```bash
dropdb --if-exists corebank_restore
createdb corebank_restore
pg_restore \
  --clean \
  --if-exists \
  --no-owner \
  --no-privileges \
  --dbname=corebank_restore \
  corebank_backup_YYYYMMDD_HHMMSS.dump
```

Post-restore startup:
- start application against restored database in test profile
- allow Flyway validation/startup checks to complete

## Post-restore verification checklist
Minimum verification after restore:
- Flyway version check:
  - expected latest migration matches repository baseline (currently `v24`)
- Core table row-count sanity:
  - `customers`
  - `customer_accounts`
  - `ledger_journals`
  - `ledger_postings`
  - `outbox_events`
- Sample integrity queries:
  - no negative available balance where domain rules forbid it
  - outbox dead-letter rows remain queryable
  - reconciliation breaks remain queryable
- Application-level smoke checks:
  - health endpoints up
  - reporting endpoints respond
  - ops batch-run reporting responds

## Partition archive readiness workflow (dry-run only)
Use the archive-candidate API to review old partitions before any archive action:

```http
GET /api/ops/maintenance/partitions/archive-candidates
GET /api/ops/maintenance/partitions/archive-candidates?retentionMonths=12&limit=200
GET /api/ops/maintenance/partitions/archive-candidates?parentTable=ledger_postings_p&parentTable=audit_events_p
```

Response highlights:
- `candidateCount`: total partitions older than retention window
- `truncated`: whether response is limited by `limit`
- `items[]`: partition list with `reason=OLDER_THAN_RETENTION`

Safety gate before any archive execution:
1. Capture archive-candidates output as evidence.
2. Review with OPS/ADMIN approver.
3. Execute manual archive plan in a separate, approved change slice.

## Operational notes
- Keep this runbook versioned with code and update after any schema or ops job changes.
- Prefer rehearsals in predictable windows and record outcomes in `PROGRESS.log`.
