# 18. Testing Strategy

This project is not complete when code compiles. It is complete only when financial behavior is proven.

## Test pyramid for this project
The normal test pyramid still applies, but the emphasis is different:
- unit tests validate pure business rules and mappers
- integration tests validate DB-backed money movement
- concurrency tests validate balance safety under simultaneous writes
- scenario tests validate end-to-end workflows
- migration tests validate schema evolution safety

## Highest-priority tests

### 1. Financial invariant tests
These are mandatory and should be treated as first-class regression tests.

Must prove:
- every journal is balanced (`debit = credit`)
- available balance never goes negative when policy forbids it
- posted balance only changes through journal posting or reversal
- hold affects available balance only
- capture affects posted balance and hold remaining amount
- reversal preserves append-only history

### 2. Idempotency tests
Must prove:
- same request + same key => same outcome
- same key + different payload => reject
- duplicate retries after timeout do not double-post money
- idempotent consumers do not re-apply the same external event twice

### 3. Concurrency tests
Must prove:
- two simultaneous debits cannot overspend the same available balance
- transfers touching the same pair of accounts do not deadlock under normal ordering
- hot-account writes remain correct when multiple workers update slot balances

### 4. Workflow tests
Must prove:
- authorize -> capture -> void sequences behave correctly
- approval-required operations do not bypass approval
- system mode blocks writes when not in `RUNNING`
- product version changes do not mutate historical contract meaning

## Recommended test layers

### Unit tests
Use for:
- command validation
- amount split logic
- posting rule assembly
- limit evaluation logic
- product/version selection logic

### Integration tests (required)
Use real PostgreSQL.
Do not fake the money-critical database layer.

Use for:
- posting journals
- hold/capture/void
- idempotency tables
- audit/outbox writes
- approvals
- snapshots and read-model projector logic

### Concurrency tests (required for money flows)
Use multiple parallel threads/processes.

Scenarios:
- 100 simultaneous debits against same account
- two-way transfer races (`A->B` and `B->A`)
- retry storms on the same idempotency key
- outbox claim contention between workers

### End-to-end scenario tests
Use HTTP/API-level tests plus database verification.

Canonical scenarios:
- open account -> deposit funds -> transfer -> verify journal
- authorize hold -> capture -> verify balances and events
- authorize hold -> void -> verify available restored
- create loan -> disburse -> repay installment -> verify split
- switch system mode to `EOD_LOCK` -> verify blocked writes

## Test data strategy
- use deterministic seed data for demo scenarios
- isolate test accounts by scenario
- create reusable fixtures for products, ledger accounts, and staff users
- avoid sharing mutable data across tests

## Migration tests
Every migration must answer:
- can it run from empty database?
- can it run from previous version?
- does it preserve historical meaning?
- does it require backfill?
- can app startup fail safely if migration fails?

## Acceptance gates before merge
A money-moving feature should not merge unless:
- integration tests pass
- at least one negative-path test exists
- idempotency behavior is tested
- audit/outbox side effects are asserted
- concurrency risk is reviewed

## Anti-patterns
Never rely only on:
- mocked repositories for money paths
- controller-only tests without DB verification
- manual Postman checks as evidence of correctness
- UI behavior as proof of balance correctness
