# 01. Project Overview

## Purpose

CoreBank models the parts of a banking backend where correctness is decided: account
balances, double-entry ledger posting, payment lifecycle, deposits, lending,
idempotency, approvals, audit and reconciliation.

The project deliberately does **not** attempt to clone a complete digital bank. Its
scope is the transactional core and the operational controls around it.

## Design question

The central question is:

> When a request is retried, a process dies, a dependency is unavailable or two
> operations race, what remains true about the money?

That question drives the architecture more than framework choice.

## Core capabilities

- double-entry ledger and balanced-journal enforcement
- posted vs available balance semantics
- payment authorization, capture, void and refund
- concurrency-safe internal transfers
- deposit lifecycle and accrual
- lending disbursement, repayment and default transitions
- maker-checker approvals for sensitive operations
- idempotent command handling
- transactional outbox and dead-letter recovery
- reconciliation and break detection
- audit trail and runtime operational controls

## Architecture

The application is a **modular monolith** backed by PostgreSQL.

PostgreSQL is authoritative for financial state, idempotency and approvals. Redis is
used only for non-authoritative acceleration. Kafka carries asynchronous events and
projections but never decides whether money moved.

Service extraction is intentionally deferred until a concrete scaling, ownership or
deployment boundary requires it.

## Engineering principles

1. Correctness before optimization.
2. Financial truth lives in one authoritative transactional store.
3. Retries and concurrency are first-class design inputs.
4. Failure recovery is part of the feature, not an operational afterthought.
5. Async infrastructure may delay derived state but may not change financial truth.
6. Architecture is introduced only when a real requirement justifies its cost.

## Boundaries

The repository does not model card networks, external clearing/settlement, KYC
providers, a complete customer-facing banking product or production-grade database
high availability.

Those boundaries are explicit so that the implemented guarantees can be tested and
explained precisely.
