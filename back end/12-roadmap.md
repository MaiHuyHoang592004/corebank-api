# 12. Roadmap

## Phase 0 — Platform bootstrap
Build:
- docker-compose local stack
- Flyway migrations
- Spring Boot skeleton
- structured logging
- health endpoints
- seed data
- local profile/config strategy

Deliverable:
- reproducible local development and delivery baseline

## Phase 1 — Strong core foundation
Build:
- customer
- account
- ledger
- payment order
- hold/capture/void
- audit
- idempotency
- approval foundation

Deliverable:
- end-to-end financial correctness for core payment flows

## Phase 2 — Banking capability expansion
Build:
- deposits
- lending
- limits
- exception queue
- teller/admin ops
- EOD/BOD jobs
- snapshots/read-model basics

Deliverable:
- broader banking capability coverage

## Phase 3 — Async and projection maturity
Build:
- outbox publisher
- Kafka integration
- read-model projector
- notification/event consumers
- saga/orchestration persistence

Deliverable:
- production-like async integration story

## Phase 4 — Production hardening
Build:
- partition automation
- hot-account slotting
- reconciliation tooling
- observability
- runtime/system mode enforcement
- encryption hardening
- deadlock handling/retry policy

Deliverable:
- credible production-readiness narrative

## Phase 5 — Portfolio polish
Build:
- demo data generator
- walkthrough docs
- sequence diagrams
- interview-friendly README
- integration tests and scenario scripts

Deliverable:
- project easy to demo and explain

## Decision checklist before adding feature
Ask:
1. Which domain owns this feature?
2. Does it move money?
3. Does it require journal posting?
4. Does it affect available or posted balance?
5. Is approval required?
6. Is idempotency required?
7. Does it need outbox/event?
8. Does it need product version binding?
9. Does it change read-model needs?
10. Does it create new reconciliation risk?
