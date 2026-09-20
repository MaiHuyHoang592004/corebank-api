# 29. Interview Prep

## 60-Second Pitch
"CoreBank is a production-like fintech backend portfolio project built as a modular monolith. I focused on the hard parts that make money systems believable: double-entry thinking, posted vs available balance semantics, payment hold/capture/void, deposit and lending lifecycle flows, idempotency, audit, approvals, reconciliation, outbox, and runtime hardening. The key architectural rule is that PostgreSQL remains the source of truth for money and idempotency, while Kafka and Redis are used only for async transport and short-term acceleration."

## Live Demo Entry (Browser)
- Run `docker compose up -d postgres redis`, then `./mvnw spring-boot:run`, and open `http://localhost:9090/dashboard/`. The authoritative procedure, with the dependency order and the environment variables, is [31-operations-runbook.md](31-operations-runbook.md).
- Login with `demo_admin / demo_admin` (or `demo_user` for least privilege).
- Click `Initialize Demo Data` once.
- Execute one flow per tab: Payment, Transfer, Deposit, Lending.
- Keep the narrative strict: PostgreSQL truth first, Redis/Kafka non-authoritative.

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
   - the domain roadmap (Phase 0 to 5) is complete
   - further work goes into platform and operations, not more banking features

## Mapping To A Platform/APM Implementation Role

Added for a specific posting: a customer-facing APM and platform implementation role at a
systems integrator, doing Dynatrace and Red Hat Service Mesh work for banks. That role does
not ask anyone to write an application, so the sections above — which argue about
architecture — are the wrong opening for it. This is the right one.

| What the role asks for | What this project shows | Strength |
|---|---|---|
| Core-banking operations experience *(preferred)* | A real ledger, deliberately broken four times with the outcome measured each time — see [19-runtime-failure-modes.md](19-runtime-failure-modes.md) | **Strong** |
| Banking domain knowledge *(preferred)* | posted vs available balance, hold/capture/void, maker-checker approvals, reconciliation breaks | **Strong** |
| Java and SQL *(preferred)* | Spring Boot 4, PostgreSQL, Flyway, advisory locks, `FOR UPDATE SKIP LOCKED` | **Strong** |
| APM tooling *(preferred)* | OpenTelemetry traces with JDBC connection and query spans, six business metrics, ECS logs correlated by trace and correlation id | **Partial** — the pipeline exists; see [33-cloud-onboarding-guide.md](33-cloud-onboarding-guide.md) for what is still to be run against a live tenant |
| Operations documentation *(a named duty)* | [31-operations-runbook.md](31-operations-runbook.md) and [32-service-levels.md](32-service-levels.md) | **Direct** |
| Kubernetes *(required)* | Manifests, an OpenShift overlay, a collector deployment, all strictly schema-validated in CI | **Partial** — see below |
| Linux, networking, TCP/IP *(required)* | Not evidenced by this project | **Absent** |
| A year in a DevOps/SysOps title | Not evidenced by this project | **Absent** |

### Say the weak parts first

The last three rows are the ones to raise yourself rather than be asked about. Two reasons:
the gap is real, and volunteering it is what makes the strong rows credible.

The Kubernetes row is the honest one to lead with. The manifests are written, validated
strictly on every push, and have an OpenShift overlay — but until the steps in
[33-cloud-onboarding-guide.md](33-cloud-onboarding-guide.md) are run, they have not been
applied to a live cluster. Say that plainly. "Validated, not yet deployed" is a sentence an
implementation engineer respects; being caught claiming otherwise is not recoverable.

### The two rows almost nobody else will have

Most candidates for a banking platform role have never operated a banking application,
because you cannot get that experience outside a bank. This project is not a substitute for
it, but it is unusually close: a ledger that was deliberately put under a SIGKILL mid-transfer
and a retry storm, with the recovery behaviour measured rather than assumed, and three real
defects found that way.

Lead with one of those incidents, not with the architecture. The connection-starvation one is
the best: a first hypothesis that turned out wrong, a measurement that settled it, a fix that
bounds the failure rather than curing it, and a configuration comment that says so. That story
is the job — it is what an APM engagement at a bank actually consists of.

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

### Why did you stop adding banking features?
Because the roadmap's Phase 0 to 5 is complete and the remaining value is in running the system, not widening it. More domain code would add volume faster than it adds anything I can explain.

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
I stopped adding banking features once the roadmap's Phase 5 was complete, because the project had crossed the threshold of a believable fintech backend:
- real money-flow modeling
- production-style controls
- selective infrastructure hardening
- a clear explanation of truth boundaries

Beyond this point, more work would mostly be infra polish or marginal ops features. That may be useful in production, but it adds less portfolio value than keeping the system tight, explainable, and defensible.

