# 17. Execution Plan

This file translates the architecture roadmap into a practical implementation sequence.
It is intended to answer: **what should be built first so the system works smoothly and safely?**

## Guiding principles
- Build the **smallest financially correct slice** first.
- Prefer **modular monolith** before service extraction.
- Introduce async integration only after the synchronous money path is correct.
- No feature is considered done until it has:
  - deterministic idempotency
  - audit trail
  - integration test
  - failure mode review

## Current execution snapshot (2026-03-24)
- Phase 1: completed and regression-verified for transfer/system-mode/limits.
- Phase 2: completed baseline for exception queue, batch run registry, snapshots.
- Phase 3:
  - Deposit: **in progress** (open/accrue/maturity slice verified).
  - Lending: **in progress** (loan disbursement slice verified; repayment allocation pending).

This snapshot reflects executable status and test evidence in `PROGRESS.log`.

## Phase 0 — Platform bootstrap (must exist before business features)
Goal: create a repeatable local development and delivery baseline.

Build:
- `docker-compose.yml` with PostgreSQL, Redis, optional Kafka, optional PgAdmin
- Spring Boot skeleton with domain packages and health endpoints
- Flyway migrations wired to app startup
- environment profile strategy: `local`, `test`, `staging`
- structured logging with request ID and correlation ID
- baseline auth placeholder for staff/operator context
- seed data for legal entity, branch, base roles, base products, base ledger accounts
- `.env.example` and secret handling conventions

Definition of done:
- A new developer can clone the repo, run one command, and start the stack locally
- Database migrates automatically
- Health endpoints return green
- Logs include request/correlation IDs

## Phase 1 — Financial core slice
Goal: prove money correctness before breadth.

Build:
- customer onboarding (minimal)
- customer account opening
- ledger posting engine
- current account balances (posted + available)
- payment order init
- hold authorize / capture / void
- audit writing
- centralized idempotency
- source-of-truth balance query service

Required tests:
- balanced journal invariant
- hold reduces available, not posted
- capture posts accounting entries
- void restores remaining hold to available balance
- duplicate request returns same result
- concurrent insufficient-funds protection

Definition of done:
- core payment flows are correct under both normal and duplicate requests
- financial invariants hold in integration tests
- no manual SQL is required to test main flows

## Phase 2 — Operations baseline
Goal: make the system operable, not just correct.

Build:
- approvals / maker-checker baseline
- exception queue
- system mode checks (`RUNNING`, `EOD_LOCK`, `MAINTENANCE`, `READ_ONLY`)
- batch run registry
- daily snapshot generation
- basic reconciliation checks
- limit checking foundation

Required tests:
- restricted system mode blocks new money-moving writes
- approval-required operations cannot bypass maker-checker
- batch jobs are idempotent
- balance snapshots are reproducible from authoritative state

Definition of done:
- operators can control runtime mode and review exceptions
- daily operational flows have storage and visibility

## Phase 3 — Banking domain expansion
Goal: broaden capability while preserving correctness.

Build:
- deposits
- deposit accrual events
- term deposit maturity handling
- lending application + contract + repayment schedule
- collateral storage
- delinquency markers
- product version binding for contracts

Required tests:
- changing product config does not change historical contracts
- accrual logic is repeatable and idempotent
- loan repayment splits principal/interest correctly
- maturity and closure flows preserve auditability

Definition of done:
- system covers major banking domains beyond payments
- contracts are explicitly bound to versioned product rules

## Phase 4 — Async integration and CQRS
Goal: separate authoritative write path from scalable read/integration paths.

Build:
- outbox publisher worker
- Kafka topics and schemas
- read-model projector
- read-side dashboard models
- saga persistence for cross-service orchestration
- notification/event consumers

Required tests:
- outbox write occurs in same DB transaction as business change
- publisher retry does not create duplicate downstream side effects
- projector rebuild works from event history or outbox replay
- saga state survives crashes and can continue or compensate safely

Definition of done:
- core write path remains authoritative in PostgreSQL
- async side effects are durable and replayable
- read models can be rebuilt

## Phase 5 — Production hardening
Goal: make the system credible under realistic scale and failure conditions.

Build:
- partition automation for event-heavy tables
- hot-account slotting
- deadlock-safe lock ordering
- retry policy for transient DB/Kafka errors
- encrypted sensitive field handling at app layer
- metrics, dashboards, traces
- backup/restore rehearsal notes
- archive strategy for historical partitions

Required tests:
- partition rollover does not break inserts
- hot account reads use aggregate balance logic
- retry only happens for safe transient errors
- deadlock-prone scenarios are reduced by deterministic locking order
- restore procedure can recreate a usable environment

Definition of done:
- platform can handle growth without redesigning the truth model
- operational failure scenarios have known responses

## Phase 6 — Portfolio/demo polish
Goal: make the system easy to explain, review, and demonstrate.

Build:
- scenario scripts and demo seeders
- sequence diagrams kept in sync with code
- interview-ready walkthrough
- feature-to-domain ownership map
- repo navigation guides
- smoke test scripts for top flows

Definition of done:
- reviewer can understand the project in less than 15 minutes
- demo flows are deterministic and repeatable

## What must never be postponed
Even in the earliest version, do not postpone:
- idempotency for money-moving writes
- audit trail
- financial invariant tests
- deterministic locking strategy
- source-of-truth documentation
