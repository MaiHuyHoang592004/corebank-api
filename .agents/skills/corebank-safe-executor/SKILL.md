---
name: corebank-safe-executor
description: execute coding tasks safely in a finance-sensitive spring boot core banking repository. use when working on ledger, balances, posted vs available semantics, hold capture void, payments, deposits, lending, approvals, outbox, projectors, workers, flyway, sql, constraints, snapshots, limits, hooks, policies, or any multi-file change in sensitive paths.
---

# Corebank Safe Executor

Execute changes conservatively in this repository.
Optimize for correctness, narrow scope, and verifiable progress.

Follow this order:
1. classify risk
2. declare exact file scope
3. inspect current behavior narrowly
4. produce a short plan
5. checkpoint if risky
6. implement the smallest safe slice
7. run the smallest proving verification
8. summarize files changed, tests run, and remaining risks

## Risk classification

Treat the task as high-risk if it touches:
- ledger
- balances
- posted vs available semantics
- hold, capture, void
- payments
- deposits
- lending
- approvals
- outbox
- projectors
- workers
- flyway
- sql
- constraints
- snapshots
- limits
- hooks
- policies
- permissions

If high-risk:
- start in plan mode first
- do not write immediately
- identify the smallest safe implementation slice
- prepare a checkpoint before risky edits

## Scope declaration

Before broad reading, list:
- exact files to inspect first
- exact symbols or modules likely involved
- smallest safe implementation slice
- smallest proving test

Prefer exact symbol targeting over broad repo scans.

## Inspection rules

Inspect current behavior before changing code.
Read only the minimum required files first.
Use repo context tools such as GitNexus before editing important symbols or multi-file paths.
Keep changes localized unless wider refactoring is explicitly required by the task.

## Checkpoint rules

Create a checkpoint before:
- changing sql or flyway
- changing ledger or balance logic
- changing posted vs available semantics
- changing hold, capture, or void behavior
- changing payments, deposits, lending, or approvals
- changing outbox, projectors, or workers
- changing hooks, policies, or permissions
- making multi-file refactors in sensitive paths

## Financial invariants

Never:
- break ledger integrity
- blur posted balance and available balance semantics
- treat cache, projections, redis, or kafka as the source of truth for money
- bypass flyway with ad-hoc database edits
- make destructive schema changes unless explicitly justified and reviewed

## Execution rules

Implement in small reversible slices.
Avoid speculative refactors.
Do not mix cleanup with behavioral changes unless required.
After each meaningful slice, restate:
- what changed
- why it is the smallest safe step
- what will verify it

## Schema-sensitive changes

If the task touches schema, answer explicitly before editing:
- does this affect ledger integrity?
- does this change posted vs available semantics?
- does this affect auditability or replayability?
- does this require backfill?
- does this change historical meaning?
- does this affect read models, projectors, workers, or downstream consumers?
- is the change additive or destructive?

For schema work:
- inspect current migration history
- inspect affected tables and usages
- inspect affected repositories, services, and tests
- state compatibility assumptions
- state rollback or forward-fix plan
- verify migration behavior with the smallest credible proof

## Debugging rules

For balance or transaction bugs:
- reproduce first with the smallest failing test or scenario
- identify likely failure layer before editing
- inspect only the failing path first
- fix the smallest real root cause
- rerun the failing reproduction first
- add or update one regression test when appropriate

Likely failure layers:
- controller or api
- service or command handler
- repository or sql
- ledger posting logic
- outbox, projector, or worker
- integration boundary

## Completion standard

Do not claim completion unless you provide:
- files changed
- tests or verification run
- remaining risks or follow-up items

For finance-sensitive changes, do not claim done without test evidence.