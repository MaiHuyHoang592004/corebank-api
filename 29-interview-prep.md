# 29. Interview Prep

## 60-Second Pitch
"CoreBank is a production-like fintech backend portfolio project built as a modular monolith. I focused on the hard parts that make money systems believable: double-entry thinking, posted vs available balance semantics, payment hold/capture/void, deposit and lending lifecycle flows, idempotency, audit, approvals, reconciliation, outbox, and runtime hardening. The key architectural rule is that PostgreSQL remains the source of truth for money and idempotency, while Kafka and Redis are used only for async transport and short-term acceleration."

## 5-Minute Architecture Walkthrough
1. Start with the boundary.
   - Spring Boot modular monolith
   - PostgreSQL as authoritative store
   - Kafka for async integration and read-model projection
   - Redis for selective acceleration only
2. Explain the money rule.
   - ledger and account state live in PostgreSQL
   - read models and caches never authorize money movement
3. Explain the control rule.
   - idempotency, audit, approvals, runtime mode, and reconciliation exist to make behavior safe under retries and operations
4. Explain the async rule.
   - business transaction commits first
   - outbox guarantees delayed publication instead of unsafe direct publish
5. Explain the stop line.
   - the repo already demonstrates real fintech maturity
   - more infra work would reduce explainability ROI

## Strongest Five Talking Points
1. PostgreSQL is the financial source of truth.
   - This is the most important architectural decision in the repo.
2. Posted and available balance semantics are modeled explicitly.
   - That instantly separates the project from generic CRUD demos.
3. Idempotency is correctness infrastructure, not just an API checkbox.
   - Duplicate and concurrent request handling is tested in money paths.
4. Async integration is done through outbox, not direct publish.
   - Business correctness is not coupled to broker availability.
5. Redis is used conservatively.
   - It reduces load and improves behavior, but it never becomes money truth.

## Likely Interview Questions
### Why modular monolith instead of microservices?
Because this project is correctness-heavy and domain-heavy. A modular monolith keeps transactional reasoning simpler while still showing service boundaries clearly.

### Why keep PostgreSQL as truth instead of using Redis more aggressively?
Because money correctness and idempotency correctness must survive cache loss, stale cache, and partial failure. Redis is helpful, but it should not decide authoritative financial state.

### What makes this look production-like instead of academic?
Not just the domain breadth. The difference is the controls: idempotency, audit, outbox, approvals, runtime mode, reconciliation, deterministic lock ordering, transient retry, and explicit source-of-truth rules.

### Where do retries happen and where do they not happen?
Retries are bounded and only for safe transient database contention classes. Business validation failures and semantic conflicts are not retried.

### How do you avoid duplicate side effects?
The project relies on idempotency keys, transactional boundaries, outbox-after-commit discipline, and tests that assert single journal/audit/outbox side effects under retry and concurrency.

### What is the Redis story in one sentence?
Redis improves performance and short-term coordination, but PostgreSQL still decides both money truth and idempotency truth.

### Why did you stop at this point?
Because after Phase 5.18 the repo already proves the most valuable interview signals. More hardening would add code volume faster than it adds explainable value.

## What To Emphasize
- correctness over cleverness
- bounded scope over fake breadth
- explicit tradeoffs over vague architecture talk
- tests as proof, not just claims

## What To Avoid
- listing every phase in chronological order
- selling Kafka/Redis as the main achievement
- over-explaining low-ROI ops polish
- pretending the project is a full bank core replacement

## Why I Stopped Here
I stopped after Phase 5.18 because the project already crossed the threshold of a believable fintech backend:
- real money-flow modeling
- production-style controls
- selective infrastructure hardening
- a clear explanation of truth boundaries

Beyond this point, more work would mostly be infra polish or marginal ops features. That may be useful in production, but it adds less portfolio value than keeping the system tight, explainable, and defensible.
