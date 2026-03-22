# AGENTS.md

## Purpose
This repository contains a **production-like core banking / fintech backend portfolio project**.
It is designed to be implemented with **Cline as the primary AI coding agent**, under human supervision.

The main objective is to model a bank-grade backend with strong accounting correctness, not just CRUD features.

Any AI agent working on this project must prioritize:
1. financial correctness
2. data integrity
3. idempotency
4. append-only history
5. deterministic behavior
6. explicit invariants over convenience
7. safe Cline workflows (Plan -> Act -> Test -> Review)

## Cline-first operating model
This repository is optimized for **Cline**. That means:
- Use **Plan mode first** for anything beyond a tiny edit.
- Use **Act mode** only after the plan is accepted.
- Use **Checkpoints** before risky edits, schema changes, or refactors.
- Use **Auto-Approve** only for low-risk reads/searches and safe test/lint commands.
- Use **manual approval** for write-heavy changes, shell commands with side effects, browser automation, and MCP servers.
- Do not use headless/YOLO mode on this repo unless explicitly requested and policy is constrained.

## Absolute financial rules
- **Ledger is the financial source of truth**
- Never mutate history in ledger or audit tables
- Reversals must be done via compensating entries, not UPDATE/DELETE
- Available balance and posted balance are not the same
- A hold reduces available balance, not posted balance
- A capture posts accounting entries and reduces hold remaining amount
- A void/expiry restores available balance from remaining hold
- Idempotency is mandatory for all money-moving write commands
- Async messaging must go through outbox, never publish directly from uncommitted transactions
- Read models are projections, not financial truth
- Redis is never a source of truth for money
- Kafka is never a source of truth for balances
- Product configuration must be versioned
- Approvals/maker-checker must not be bypassed for high-risk operations

## Cline guardrails
### Approve automatically only for
- read-only file access
- search/grep/find operations
- git status / git diff
- safe local test/lint/typecheck commands
- safe docs generation inside repo

### Require manual approval for
- schema migrations
- updates to ledger/payment/deposit/lending modules
- commands that change database state
- commands with redirects, pipes, sudo, curl, rm, chmod, docker exec into prod-like services
- creation or enabling of MCP servers
- git push

### Never allow by default
- rm -rf
- sudo
- curl to unknown endpoints
- ssh/scp/nc
- database commands against non-local environments
- direct writes to deployment configs without explicit request

## System intent
This project is intended to evolve in this order:
1. modular monolith
2. production-like async integration
3. selected service extraction if needed

Do not introduce microservices prematurely unless explicitly requested.

## Tech stack intent
- Spring Boot = application orchestration
- PostgreSQL = financial source of truth
- Kafka = async backbone
- Redis = cache/coordination/rate limit
- Flyway = schema migration
- jOOQ or JdbcTemplate = money-critical SQL paths
- JPA/Hibernate = non-critical CRUD modules

## Where to place logic
### Keep in application layer
- business workflow orchestration
- authorization
- approval flow
- product selection
- limit/risk decisions
- saga state transitions
- event publishing through outbox
- Cline task-specific orchestration prompts and templates

### Keep in database layer
- constraints
- atomic money mutation
- accounting posting
- append-only enforcement
- hot-account slot persistence
- partitioned event storage

## How Cline should execute work
### For feature work
1. Read `21-cline-operating-model.md`
2. Read relevant domain docs
3. Produce a short plan first
4. List exact files to inspect
5. Make changes in small steps
6. Run minimum tests after each meaningful step
7. Summarize changes, risks, and remaining gaps

### For schema changes
1. Read `06-database-context.md`, `07-financial-invariants.md`, and `22-cline-policy-kit.md`
2. Propose migration impact explicitly
3. Create/modify Flyway migration, not ad-hoc SQL
4. Explain backfill/replay impact
5. Run DB tests / integration tests

### For debugging
1. Start in Plan mode
2. Identify likely failing layer: API / service / DB / projector / worker
3. Reproduce with the smallest command/test possible
4. Avoid broad refactors during diagnosis
5. Use checkpoints before risky fixes

## When modifying schema
Any schema change must answer:
- Does this break ledger invariants?
- Does this break replayability or auditability?
- Does this break product versioning?
- Does this change financial meaning of historical contracts?
- Does this require migration/backfill?
- Does this affect read model projection?

## When writing code
Agent should avoid:
- loading many ledger rows into memory to compute balance
- summing historical postings on hot paths
- bypassing idempotency
- directly updating read model as source of truth
- embedding raw SQL table knowledge deep inside business code
- making large multi-module edits in one Act step

Agent should prefer:
- service abstractions like `BalanceQueryService`
- explicit command handlers
- immutable domain events
- integration tests around money flows
- transaction boundaries around financial commands
- Plan -> Act -> Test -> Review loops

## Required reading order
1. `AGENTS.md`
2. `01-project-overview.md`
3. `04-system-architecture.md`
4. `05-domain-modules.md`
5. `06-database-context.md`
6. `07-financial-invariants.md`
7. `08-core-workflows.md`
8. `09-application-architecture.md`
9. `14-source-of-truth-map.md`
10. `17-execution-plan.md`
11. `18-testing-strategy.md`
12. `19-runtime-failure-modes.md`
13. `20-acceptance-criteria.md`
14. `21-cline-operating-model.md`
15. `22-cline-policy-kit.md`
16. `23-cline-workflows.md`
17. `24-cline-prompts-and-task-templates.md`
18. `25-cline-model-strategy.md`
19. `26-cline-troubleshooting.md`

## Vocabulary
- posted balance = accounting balance
- available balance = spendable balance
- hold = reserved funds
- capture = accounting realization of held funds
- void = release remaining hold
- reversal = compensating accounting journal
- read model = query projection
- source of truth = authoritative state for a concern
- Plan mode = Cline analysis/proposal mode, no file changes
- Act mode = Cline execution mode, may edit files and run tools
- Checkpoint = reversible snapshot created during work

## If context conflicts
Priority order:
1. explicit financial invariants
2. schema semantics
3. workflow docs
4. Cline guardrails and policy kit
5. application architecture docs
6. roadmap

## Expected engineering style
- explicit > implicit
- deterministic > magical
- correctness > shortcut
- reversible operations > destructive operations
- auditable state > hidden side effects
- smaller agent steps > large speculative changes

## Non-negotiable delivery rule
A feature is not finished just because the endpoint works.
For money-moving or operationally sensitive work, the agent must also consider:
- migration impact
- invariant tests
- failure mode behavior
- acceptance criteria in `20-acceptance-criteria.md`
- whether the chosen Cline workflow was safe for the task
