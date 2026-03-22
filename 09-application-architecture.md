# 09. Application Architecture

## Recommended implementation style
**Spring Boot modular monolith**

## Package/module suggestion
```text
com.corebank
 ├── common
 ├── identity
 ├── customer
 ├── product
 ├── account
 ├── ledger
 ├── payment
 ├── deposit
 ├── lending
 ├── limits
 ├── operations
 ├── integration
 ├── reporting
 └── platform
```

## Responsibility by layer

### API layer
- controllers
- request validation
- auth context extraction
- mapping DTOs

### Application layer
- command orchestration
- workflow/state transitions
- idempotency/risk/approval coordination
- outbox write triggering

### Domain/service layer
- business policy
- domain services
- posting rule resolution
- command handlers

### Persistence layer
Use:
- JPA/Hibernate for ordinary CRUD modules
- jOOQ or JdbcTemplate for money-critical flows

## Where to use JPA
Good fit:
- customer profiles
- product config
- approvals
- documents
- teller sessions
- exception queue
- loan application CRUD

## Where to use SQL-first access
Required/preferred:
- ledger journals/postings
- balance updates
- hold/capture/void
- reconciliation
- snapshot materialization
- partition-aware queries
- hot account slot writes

## Core service abstractions
Recommended key services:
- `BalanceQueryService`
- `LedgerCommandService`
- `PostingRuleResolver`
- `PaymentApplicationService`
- `HoldService`
- `DepositApplicationService`
- `LoanApplicationService`
- `ApprovalService`
- `LimitCheckService`
- `IdempotencyService`
- `OutboxService`
- `ReadModelProjector`

## Critical implementation rules
- Never let controllers orchestrate money logic directly
- Never let business code query raw balance tables everywhere
- Never let a service publish Kafka directly before DB commit
- Always propagate requestId/correlationId/idempotencyKey

## Transaction boundary
Typical financial command transaction:
1. load/lock required records
2. validate business rules
3. post journal or mutate financial state
4. persist domain event rows / outbox / audit
5. commit
6. async publish after commit
