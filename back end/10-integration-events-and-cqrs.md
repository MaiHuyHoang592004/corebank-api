# 10. Integration, Events, and CQRS

## Why outbox is required
A classic failure mode:
- DB commit succeeds
- Kafka publish fails
If event is published directly in-process, system becomes inconsistent.

Therefore:
- write to `outbox_messages` in same DB transaction
- publish from outbox worker after commit

## Suggested Kafka topics
Minimal set:
- `corebank.payment-events`
- `corebank.ledger-events`
- `corebank.account-events`
- `corebank.deposit-events`
- `corebank.loan-events`
- `corebank.audit-events`

Optional later:
- `corebank.limit-events`
- `corebank.reconciliation-events`
- `corebank.notification-events`

## Event envelope recommendation
Every event should include:
- eventId
- aggregateType
- aggregateId
- eventType
- occurredAt
- schemaVersion
- correlationId
- requestId
- actor
- payload

## CQRS model
### Write model
- normalized business/ledger tables
- strong consistency
- transactionally correct

### Read model
- account summaries
- dashboard query tables
- reporting projections
- history views
- operational summary views

## Read model sync options

### Option A — trigger-based
Pros:
- simpler
- fast for portfolio
Cons:
- logic buried in DB
- harder to scale

### Option B — outbox/Kafka projector
Pros:
- closer to production large-system pattern
- replayable
- decoupled projections
Cons:
- eventual consistency
- more moving parts

Recommended approach:
- V1: simple projector/background sync
- V2: Kafka projector

## Saga/orchestration
If a workflow spans multiple services/steps:
- keep saga instance state
- keep step execution log
- define compensating actions
- ensure idempotent retry per step

Use saga for:
- external payment provider coordination
- multi-step disbursement/settlement
- cross-boundary booking/payment flows
