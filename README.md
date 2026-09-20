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
| `SIGKILL` during 60 concurrent transfers | Money correct, every uncommitted transaction rolled back. Four idempotency claims left `IN_PROGRESS` forever. | Those commands had not happened and could never be retried; the cleanup job only deletes terminal rows. Recovery meant editing the database by hand. Claims now carry a lease a retry can take over. |
| 14 requests sharing one idempotency key, against a cold instance | 32 seconds of outage, ten `500`s, and still exactly one journal posted. | Connection starvation, not lock contention: a money command holds two connections at once, so at pool size every command waits for one nobody can release. The identical burst at pool 40 took one second with no errors. Pool now sized explicitly, with the arithmetic written next to the number. |
| Concurrent duplicates of one command | Exactly one journal, but one caller got a `500`. | A unique violation was caught and the row read back inside the same transaction, which PostgreSQL had already aborted. A client retrying on `5xx` would have kept hammering. The claim is now an `ON CONFLICT` whose row count decides the winner. |
| Running the outbox as deployed | It had never run. | `@EnableScheduling` was absent, so the only `@Scheduled` method was inert. Every test passed because each one called it directly. The headline reliability claim was not true outside tests. |
| Probing the health endpoints anonymously | `401` on liveness and readiness. | A kubelet sends no credentials, so this would have restart-looped every replica and kept them all out of the Service. Found by calling the endpoints, not by reading the config. |
| Scanning the container image | Eight fixable `CRITICAL` CVEs, six of them authentication or authorization bypasses in embedded Tomcat. | The image was never published: the pipeline builds, scans, and only then pushes. Dependencies upgraded, one version pinned past what the framework manages. |

The invariant checked after every scenario is the one that matters in a bank: total
money unchanged, no unbalanced journal, no negative balance. **It held in all of them,
including the two that returned `500`s.** What failed was availability and
recoverability, never correctness.

Method, measurements, the scenario that found nothing, and the rough edge still open:
[docs/19-runtime-failure-modes.md](docs/19-runtime-failure-modes.md).

## The rules that follow

- **PostgreSQL decides money.** Accounts, ledger, idempotency, approvals and audit live
  there. A cache can be empty and a broker can be down without changing what is true.
- **Posted and available are different questions.** One asks what has settled, the other
  what can be spent now. A hold moves the second without touching the first, and
  blurring them is how a system lets the same money leave twice.
- **An idempotency key is a claim with a lease, not a flag.** Claims are taken in the
  database, and one whose owner stopped can be taken over rather than blocking the
  operation forever.
- **Events are written with the money, published after it.** The outbox row commits in
  the same transaction as the balance change, so a broker outage delays delivery
  instead of losing it.
- **Liveness must not consult anything shared.** A probe that checks the database turns
  one slow query into every replica restarting at once.
- **Instrumentation and export are separate switches.** Traces and metrics are always
  recorded; where they are sent is a deployment decision.

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
to ninety seconds to wake. Sign in as `demo_admin` / `demo_admin` (also `demo_ops` and
`demo_user`, password the same as the username), initialise the demo data, then
authorize a hold, capture it, run a transfer, and replay that transfer with the same
idempotency key to get the original response back rather than a second transfer.

Walkthrough with expected output: [docs/28-demo-script.md](docs/28-demo-script.md).

## Checking it

```bash
./mvnw verify                 # everything; needs Docker for Testcontainers
./mvnw -Dgroups=fast test     # the container-free subset, seconds
```

Money paths are covered against a real PostgreSQL rather than mocks, because the
behaviour being tested is the database's. CI runs the fast subset for a signal in about
a minute, the full suite for the truth, validates the Kubernetes manifests, then builds
the image, scans it, and publishes only if the scan passes.

## Operating it

```bash
# Metrics, traces and logs
docker compose -f deploy/observability/docker-compose.yml up -d
COREBANK_OTLP_ENABLED=true COREBANK_LOG_FORMAT=ecs ./mvnw spring-boot:run

# Three replicas with probes, rollout and rollback
kind create cluster --name corebank --config deploy/kubernetes/kind-cluster.yaml
cp deploy/kubernetes/secret.example.yaml deploy/kubernetes/secret.yaml   # then edit it
kubectl apply -f deploy/kubernetes/namespace.yaml -f deploy/kubernetes/secret.yaml
kubectl apply -k deploy/kubernetes
```

Six gauges describe the system as a bank rather than as a web server: outbox backlog,
dead letters, open reconciliation breaks, idempotency claims in flight, idempotency
claims past their lease, and journals posted. Each answers something a `200` cannot. A growing outbox backlog means every downstream
system is drifting out of date while the API still reports success.

Telemetry leaves over OpenTelemetry's wire protocol and nothing else, so the backend is
an endpoint change rather than a code change.

[deploy/observability/README.md](deploy/observability/README.md) ·
[deploy/kubernetes/README.md](deploy/kubernetes/README.md) ·
[DEPLOY.md](DEPLOY.md)

## Built with

Java 17, Spring Boot, PostgreSQL with Flyway, Testcontainers. Redis for rate limiting
and an idempotency replay cache, Kafka for asynchronous projection, neither of them
authoritative. Micrometer and OpenTelemetry for telemetry, Prometheus, Tempo and Grafana
for the local stack. Packaged as a modular monolith because the hard part here is
transactional correctness, and splitting services early makes that harder rather than
easier.

Technology is last in this list on purpose.

## Scope

This is a personal project, not a running bank. It models the parts where money
correctness is decided and stops before the parts that are mostly integration work:
there is no card scheme, no clearing or settlement network, no KYC provider, no
customer-facing product.

Deliberately absent, each for a stated reason in the relevant document: database high
availability, TLS termination, log shipping, alert routing, and a production secret
manager. The demo credentials are published because the deployment is a demonstration;
that is a decision, not an oversight.

One known rough edge, unfixed: a duplicate arriving while the original is still running
is answered `400` where `409` is correct. Fixing it properly means giving the API typed
errors rather than patching one branch.

## Documents

Start here: [financial invariants](docs/07-financial-invariants.md) ·
[source-of-truth map](docs/14-source-of-truth-map.md) ·
[runtime failure modes](docs/19-runtime-failure-modes.md)

Then: [system architecture](docs/04-system-architecture.md) ·
[sequence diagrams](docs/16-sequence-diagrams.md) ·
[testing strategy](docs/18-testing-strategy.md) ·
[acceptance criteria](docs/20-acceptance-criteria.md) ·
[project overview](docs/01-project-overview.md)

To operate it: [operations runbook](docs/31-operations-runbook.md) ·
[service levels](docs/32-service-levels.md) ·
[cloud onboarding guide](docs/33-cloud-onboarding-guide.md)
