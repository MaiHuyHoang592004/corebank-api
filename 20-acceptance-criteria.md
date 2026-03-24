# 20. Acceptance Criteria

This file defines when the project can honestly be called **production-like**.
It is not enough for the app to run. It must satisfy these criteria.

## A. Financial correctness
The project is not acceptable unless:
- all money-moving commands are idempotent
- ledger posting is balanced and append-only
- available vs posted balance semantics are preserved
- reversals use compensating journals
- audit history exists for money-moving operations

## B. Operational baseline
The project is not acceptable unless:
- local environment is reproducible via containers
- schema migrations are automated
- system mode can block unsafe writes
- health checks and structured logs exist
- backups/restores have at least a documented procedure

## C. Testing baseline
The project is not acceptable unless:
- integration tests cover main money flows
- negative paths are tested
- concurrency risk is tested for at least top money paths
- migration startup path is testable

## D. Async reliability baseline
The project is not acceptable unless:
- outbox is used for async publication
- publisher failure does not lose business events
- consumers are idempotent or duplicate-safe
- read models are explicitly marked non-authoritative

## E. Domain coverage baseline
The project is not acceptable as a core banking portfolio unless it covers at least:
- customer and KYC context
- accounts and balance engine
- ledger and reversals
- payments/holds
- at least one deposit flow
- at least one lending flow
- approvals / operator controls

## F. Production-hardening narrative
To claim production-like maturity, the project should also address:
- partitioning or archive strategy
- reconciliation
- hot-account handling
- product versioning
- field-level sensitive data protection
- retry policy and deterministic lock ordering

## Review questions for final sign-off
1. Can an external reviewer identify the authoritative truth for money and state?
2. Can duplicate requests be handled safely?
3. Can the system explain every balance change through journal history?
4. Can the system survive Kafka/Redis outages without corrupting money truth?
5. Can historical contracts remain valid after product configuration changes?
6. Can the main flows be demonstrated end to end with deterministic scripts?
