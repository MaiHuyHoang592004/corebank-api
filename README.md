# CoreBank

A core banking backend built around one question: **when this system fails, what happens
to the money?**

[![CI](https://github.com/MaiHuyHoang592004/corebank-api/actions/workflows/ci.yml/badge.svg)](https://github.com/MaiHuyHoang592004/corebank-api/actions/workflows/ci.yml)

## The problem

An API that returns `200` has said very little about a money system. The cases that
decide whether it can be trusted are the ones where it cannot answer at all:

- the process dies between reserving funds and recording that it did
- a client retries a transfer it never got a response for
- a broker is unreachable while the ledger is perfectly available
- a balance is high enough to spend and also already committed elsewhere
- two operators act on the same loan at the same moment

In each of those the request either has no answer or gets a misleading one, and the
truth has to live somewhere the request cannot reach. That is what this repository is
about: deciding where money truth lives, what is allowed to move it, and what is
supposed to happen when a part of the system stops.

## What broke when it was made to fail

Reasoning about failure is cheap. These are the ones that were induced on a running
system against a real PostgreSQL, chosen because nobody knew the outcome in advance.
Killing a pod to watch Kubernetes restart it is not on the list: that demonstrates
Kubernetes, not this.

| Induced | What happened | What it cost, and the fix |
|---|---|---|
| `SIGKILL` during 60 concurrent transfers | Money correct, every uncommitted transaction rolled back. Four idempotency claims left `IN_PROGRESS` forever. | Those commands had not happened and could never be retried; the cleanup job only deletes terminal rows. Claims now carry a lease a retry can take over. |
| 14 requests sharing one idempotency key, against a cold instance | 32 seconds of outage, ten `500`s, and still exactly one journal posted. | Connection starvation, not lock contention: a money command holds two connections at once, so at pool size every command waits for one nobody can release. The identical burst at pool 40 took one second with no errors. |
| Concurrent duplicates of one command | Exactly one journal, but one caller got a `500`. | A unique violation was caught and the row read back inside the same transaction, which PostgreSQL had already aborted. The claim is now an `ON CONFLICT` whose row count decides the winner. |
| Running the outbox as deployed | It had never run. | `@EnableScheduling` was absent, so the only `@Scheduled` method was inert. Tests had called it directly and missed the runtime configuration defect. |
| Probing the health endpoints anonymously | `401` on liveness and readiness. | A kubelet sends no credentials, so this would have restart-looped every replica and kept them all out of the Service. |
| Scanning the container image | Eight fixable `CRITICAL` CVEs, six in embedded Tomcat. | The pipeline now builds and scans the image before publishing it. |

The invariant checked after every scenario is the one that matters in a bank: total
money unchanged, no unbalanced journal, no negative balance. **It held in all of them.**
What failed was availability and recoverability, never correctness.

Method and measurements:
[docs/19-runtime-failure-modes.md](docs/19-runtime-failure-modes.md).

## The rules that follow

- **PostgreSQL decides money.** Accounts, ledger, idempotency, approvals and audit live
  there. A cache can be empty and a broker can be down without changing what is true.
- **Posted and available are different questions.** A hold changes spendability without
  pretending that settlement already happened.
- **An idempotency key is a claim with a lease, not a flag.** Claims are taken in the
  database, and one whose owner stopped can be taken over safely.
- **Events are written with the money, published after it.** The outbox row commits in
  the same transaction as the balance change.
- **Liveness must not consult shared dependencies.** A probe that checks the database can
  turn one dependency failure into a restart storm.
- **Instrumentation and export are separate switches.** The application can remain
  observable even when a telemetry backend is unavailable.

## What it does

Payments with hold, capture, void and refund. Concurrency-safe internal transfers.
Deposits through open, accrual and maturity. Lending through disbursement, repayment,
overdue and default. Around those: double-entry ledger, idempotency, audit trail,
maker/checker approvals, reconciliation with break detection, outbox with dead-letter
handling, runtime mode guards, and rate limiting that degrades open rather than
blocking money.

## Seeing it work

```bash
docker compose up -d postgres
./mvnw spring-boot:run
# http://localhost:9090/
```

Redis and Kafka are optional; the application degrades rather than failing without
them. A hosted instance runs at
[corebank-api-acv7.onrender.com](https://corebank-api-acv7.onrender.com) and may take up
to ninety seconds to wake. Sign in as `demo_admin / demo_admin`, initialise the demo
data, then authorize a hold, capture it, run a transfer, and replay that transfer with
the same idempotency key.

Walkthrough: [docs/28-demo-script.md](docs/28-demo-script.md).

## Checking it

```bash
./mvnw verify
./mvnw -Dgroups=fast test
```

Money paths are covered against a real PostgreSQL rather than mocks, because the
behaviour being tested is the database's. CI runs the fast subset, the full suite,
validates Kubernetes manifests, builds the container image, scans it, and publishes
only after the checks pass.

## Operating it

```bash
# Metrics, traces and logs
docker compose -f deploy/observability/docker-compose.yml up -d
COREBANK_OTLP_ENABLED=true COREBANK_LOG_FORMAT=ecs ./mvnw spring-boot:run

# Kubernetes
kind create cluster --name corebank --config deploy/kubernetes/kind-cluster.yaml
cp deploy/kubernetes/secret.example.yaml deploy/kubernetes/secret.yaml
kubectl apply -f deploy/kubernetes/namespace.yaml -f deploy/kubernetes/secret.yaml
kubectl apply -k deploy/kubernetes
```

The service emits platform metrics plus banking-specific signals for outbox backlog,
dead letters, reconciliation breaks, stale idempotency claims and ledger activity.
Telemetry leaves through OpenTelemetry so the backend remains a deployment choice.

[deploy/observability/README.md](deploy/observability/README.md) ·
[deploy/kubernetes/README.md](deploy/kubernetes/README.md) ·
[DEPLOY.md](DEPLOY.md)

## Built with

Java 17, Spring Boot, PostgreSQL with Flyway, Testcontainers, Redis, Kafka, Micrometer
and OpenTelemetry. Prometheus, Tempo and Grafana provide the local observability stack.
The application remains a modular monolith because transactional correctness is the
hard problem here; splitting services early would make that harder without solving a
real requirement.

## Scope

This is not a running bank. It models the parts where money correctness is decided and
stops before card schemes, clearing and settlement networks, KYC integrations and a
customer-facing banking product.

Some production concerns are intentionally left outside the repository: database high
availability, production secret management, centralized log shipping and human alert
routing.

## Documentation

The curated documentation index is at **[docs/README.md](docs/README.md)**.

Recommended entry points:

- [Financial invariants](docs/07-financial-invariants.md)
- [Source-of-truth map](docs/14-source-of-truth-map.md)
- [Runtime failure modes](docs/19-runtime-failure-modes.md)
- [Operations runbook](docs/31-operations-runbook.md)
- [Service levels](docs/32-service-levels.md)
