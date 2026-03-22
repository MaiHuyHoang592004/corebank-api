# 03. Scope and Non-Goals

## In scope
### Foundation banking domains
- customers
- KYC/document metadata
- risk profile
- bank products + versioning
- accounts
- balances
- chart of accounts
- double-entry ledger
- payment orders
- holds/capture/void
- refunds (foundation)
- deposits
- loans
- approvals
- limits
- outbox
- audit
- read-model foundation
- reconciliation foundation
- partition-ready event storage

## Production-like technical scope
- idempotency
- audit chain
- outbox pattern
- saga/orchestration storage
- CQRS readiness
- partitioning readiness
- hot-account strategy
- EOD/BOD job model
- system mode control

## Out of scope for now
- real central bank/payment rail integration
- real AML engine
- real sanctions engine
- full treasury
- FX trading
- card network integration
- Basel-style risk engine
- real statement PDF generation
- full regulatory reporting
- customer-facing frontend app complete
- multi-country tax/compliance

## Important non-goal
This project does **not** try to become a real bank core for production deployment.
It aims to be:
- technically serious
- financially correct in core concepts
- extensible
- portfolio-worthy
