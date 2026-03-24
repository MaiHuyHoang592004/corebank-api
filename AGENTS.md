# Corebank workspace rules

This is a finance-sensitive repository.
Optimize for correctness, narrow scope, and verifiable progress.

## Operating mode
- Follow: Plan -> Act -> Test -> Review.
- For risky tasks, start in plan mode first.
- List exact files you intend to inspect before scanning broadly.
- Prefer narrow reads and small reversible edits.

## High-risk changes
Treat these as high-risk:
- ledger
- balances
- posted vs available semantics
- hold, capture, void
- payments
- deposits
- lending
- approvals
- outbox, projectors, workers
- Flyway, SQL, constraints, snapshots, limits
- hooks, policies, permissions

For high-risk work:
- create a checkpoint before writing
- change the smallest safe slice
- avoid speculative refactors
- run the smallest test that proves the change

## Financial invariants
- Never break ledger integrity.
- Never blur posted balance and available balance semantics.
- Never treat cache, projections, Redis, or Kafka as the source of truth for money.
- Never bypass Flyway with ad-hoc database edits.
- Never make destructive schema changes unless explicitly justified and reviewed.

## Execution discipline
- Inspect current behavior before changing code.
- Prefer exact symbol/file targeting over wide search.
- Use GitNexus or equivalent repo context tools before editing important symbols.
- Keep changes localized unless the task explicitly requires wider refactoring.

## Completion standard
Do not claim completion unless you provide:
- files changed
- tests or verification run
- remaining risks or follow-up items
- For finance-sensitive domain tasks, use the corebank-safe-executor skill.

## GitNexus usage
- This repository is indexed by GitNexus.
- For finance-sensitive or multi-file code changes, use GitNexus before editing important symbols.
- Run impact/context analysis before changing core symbols in ledger, balances, payments, deposits, lending, outbox, projectors, workers, hooks, or policies.
- If GitNexus reports HIGH or CRITICAL risk, warn before proceeding.
- After refactors or wider changes, verify scope with detect_changes.
- Prefer GitNexus over broad grep when exploring unfamiliar execution flows.

## Workflow guidance
- When producing a plan, suggest the most relevant workflow if the task is non-trivial or finance-sensitive.
- Do not force workflows for simple or low-risk tasks.
- If the task touches high-risk areas (ledger, balances, payments, deposits, lending, approvals, outbox, projectors, workers, Flyway, SQL, hooks, policies), recommend using a workflow before implementation.

Suggested mapping:
- General feature work → /financial-feature.md
- Schema or SQL changes → /schema-safe-change.md
- Bugs, inconsistencies, or balance issues → /incident-balance-debug.md

- If a workflow is recommended, briefly explain why it fits the task.