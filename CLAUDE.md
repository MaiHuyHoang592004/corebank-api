# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is

CoreBank API is a Spring Boot **modular monolith** fintech backend (Java 17, Spring Boot 4.0.4). It's a portfolio project proving correct money semantics (double-entry ledger, idempotency, holds/capture/void, reconciliation) rather than a CRUD demo. **PostgreSQL is the sole source of financial truth** — Kafka and Redis are optional, non-authoritative (async transport and acceleration only).

## Commands

```bash
# Start dependencies (Postgres required, Redis optional)
docker compose up -d postgres
docker compose up -d postgres redis   # full stack

# Run the app (default profile, Kafka/Redis disabled-safe)
./mvnw spring-boot:run
# Run with showcase profile (full stack)
./mvnw spring-boot:run -Dspring-boot.run.profiles=showcase

# Build / verify
./mvnw clean verify
./mvnw test                                    # all tests
./mvnw test -Dtest=ClassName                   # single test class
./mvnw test -Dtest=ClassName#methodName        # single test method
```

App listens on `http://localhost:9090` (dashboard at `/dashboard/index.html`). Datasource defaults to `jdbc:postgresql://localhost:5433/corebank` (see `docker-compose.yml` for the mapped Postgres port). Integration tests use Testcontainers (`TestcontainersConfiguration`), not mocked repositories, for money-critical paths — expect Docker to be required when running the test suite.

There is no linter/formatter plugin wired into `pom.xml` (no Checkstyle/Spotless/PMD) and no JS toolchain (`package.json` has no real scripts) — don't invent lint/build commands for those.

## Architecture

### Module layout (`com.corebank.corebank_api.*`)
Package-per-domain, each typically with `api/` (controllers), application/domain services at the package root, and repositories:
`account`, `customer`, `product`, `ledger`, `payment`, `deposit`, `lending`, `limits`, `ops`, `integration`, `reporting`, `notification`, `security`, `demo`, `config`, `common`.

### Layering rules (see `09-application-architecture.md`)
- **API layer** (controllers): request validation, auth context, DTO mapping only — **never orchestrate money logic in controllers**.
- **Application layer**: command orchestration, idempotency/limit/approval coordination, outbox write triggering.
- **Domain/service layer**: business policy, posting rule resolution, command handlers.
- **Persistence**: JPA/Hibernate for ordinary CRUD (customer profiles, product config, approvals); **SQL-first (jOOQ/JdbcTemplate) required for money-critical flows** — ledger postings, balance updates, hold/capture/void, reconciliation, hot-account slot writes.

Transaction boundary for a financial command: lock/load records → validate → post journal/mutate state → write domain event/outbox/audit rows → commit → publish to Kafka **after** commit (never before).

### Source-of-truth map (see `14-source-of-truth-map.md`)
When data conflicts, resolve in this order:
- **Money/balance**: `ledger.ledger_journals` + `ledger.ledger_postings` → `account.customer_accounts` / `account_balances_current` → `payment.funds_holds` → read models/snapshots → cache/UI.
- **Idempotency**: `integration.idempotency_keys` is authoritative; Redis is burst protection only.
- **Async/outbox**: `integration.outbox_messages` decides what "needs publishing"; Kafka broker/consumer state is not authoritative.
- **Runtime mode** (`RUNNING`/`MAINTENANCE`/`EOD_LOCK`/`READ_ONLY`): authoritative in `iam.system_configs`, enforced at the API layer.
- Read models (`reporting.account_read_models`) are query convenience only — **never use them to authorize money movement**.

### Financial invariants (see `07-financial-invariants.md`, non-negotiable)
- Every journal must balance: debit = credit.
- Ledger history is append-only — corrections are reversal journals, never mutation/deletion of posted rows.
- Hold changes available balance only; capture posts the journal and reduces the hold; void/expiry restores available balance.
- Idempotent commands: same key + same payload → same result; same key + different payload → reject.
- Deposit/loan contracts bind to a specific product **version**; historical contracts keep historical rules even after product config changes.
- Multi-account operations lock/update in deterministic order (e.g. smaller account id first) to avoid deadlocks.
- Never treat Redis/Kafka/read models as financial source of truth; never bypass Flyway with ad-hoc schema/data edits.

### Migrations
Flyway migrations live in `src/main/resources/db/migration` (`V<n>__description.sql`, currently up to V26). Schema changes are high-risk — see `policy-kit/workflows/schema-change.md`.

## Repo-specific conventions (from `AGENTS.md`)

This repo treats the following as **high-risk** and expects small, reversible, verified changes: ledger, balances, posted-vs-available semantics, hold/capture/void, payments, deposits, lending, approvals, outbox/projectors/workers, Flyway/SQL/constraints/snapshots/limits, hooks/policies/permissions.

For work touching these areas:
- Inspect current behavior before changing code; prefer exact symbol/file targeting over broad search.
- Change the smallest safe slice; avoid speculative refactors.
- Run the smallest test that proves the change (unit → integration → concurrency, per `18-testing-strategy.md`).
- Don't claim completion without listing: files changed, tests/verification run, and remaining risks/follow-ups.

## Numbered docs (`01-*.md` … `30-*.md`)

The repo root has a full numbered doc set (project overview, domain modules, financial invariants, application architecture, source-of-truth map, sequence diagrams, testing strategy, runtime failure modes, acceptance criteria, demo script, etc.) written to give AI agents and new contributors full project context. Check the relevant numbered doc before making non-trivial changes in an unfamiliar area — `14-source-of-truth-map.md`, `07-financial-invariants.md`, and `18-testing-strategy.md` are the ones most likely to prevent a wrong change.
