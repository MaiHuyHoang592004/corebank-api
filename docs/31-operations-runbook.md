# 31. Operations Runbook

## Purpose and boundaries

This is the document an operations team runs the system from: how to start it, how to
stop it safely, what to do when each alert fires, which jobs have to be invoked and by
what means, and which numbers the system is configured against.

It covers `corebank-api` and the PostgreSQL instance it treats as the source of truth.
Redis and Kafka appear only where their absence changes what an operator should do.

Boundaries:
- PostgreSQL remains authoritative for money and state. Nothing in this runbook
  authorises repairing a balance, a journal or a posting by editing a row.
- Backup, restore and partition archive procedures are not repeated here. They live in
  [27-backup-restore-and-partition-archive-runbook.md](27-backup-restore-and-partition-archive-runbook.md).
- The service level objectives and the recording rules that feed them are not defined
  here.

### What is verified, and what has never been run

This matters more than usual, so it is stated before anything else.

Every path, property name, metric name, threshold, SQL statement and HTTP status in
this document was verified by reading the file that defines it — the controller's
`@RequestMapping` and `@GetMapping`/`@PostMapping` annotations, `application.yml`, the
Flyway migrations under `src/main/resources/db/migration/`, `deploy/kubernetes/*.yaml`
and `deploy/observability/alerts.yml`. Where a claim could not be grounded in a file it
is written below as an open question rather than as an instruction.

**No command in this runbook has been executed.** The environment this document was
written in has no Docker daemon, so the application was never started, no endpoint was
ever called, no alert was ever evaluated against a live Prometheus, and no `curl`
invocation below has produced a response. The response shapes quoted are the Java
`record` definitions the controllers return, read from source; they are what the code
will serialise, not what an operator has seen come back.

The three incident procedures in [Incident procedures](#incident-procedures) are the
exception in one direction only: the failures themselves were induced on a running
system against a real PostgreSQL and the measurements are real, recorded in
[19-runtime-failure-modes.md](19-runtime-failure-modes.md). The operator steps written
around those measurements have not been rehearsed.

Treat the whole document as a reviewed design for operating this system, and rehearse
each procedure in a scratch environment before relying on it in an incident.

## Daily health verification

Run these in order. Each one answers a question the one before it cannot.

Prerequisites:
- base URL for the environment, `$BASE` below (local default `http://localhost:9090`)
- credentials for an account holding `ROLE_OPS` or `ROLE_ADMIN`

Template commands:

```bash
BASE="${BASE:-http://localhost:9090}"
OPS="demo_ops:demo_ops"

# 1. Is the process up and is its database reachable?
curl -s "$BASE/actuator/health"
curl -s "$BASE/actuator/health/readiness"
curl -s "$BASE/actuator/health/liveness"

# 2. Which build is running?
curl -s -u "$OPS" "$BASE/actuator/info"

# 3. Are money events reaching their consumers?
curl -s -u "$OPS" "$BASE/api/reporting/outbox/summary"

# 4. Has anything exhausted its retries?
curl -s -u "$OPS" "$BASE/api/reporting/outbox/dead-letters?limit=20"

# 5. Do the books agree with the external statements?
curl -s -u "$OPS" "$BASE/api/reporting/reconciliation/breaks?status=OPEN&limit=50"

# 6. The bank-shaped metrics, in one read.
curl -s -u "$OPS" "$BASE/actuator/prometheus" | grep -E '^corebank_'
```

What each answers:

| Check | Question it answers | Healthy result |
|---|---|---|
| `/actuator/health` | is the aggregate status up? | `{"status":"UP"}` |
| `/actuator/health/readiness` | should this instance take traffic? Consults `readinessState` and `db` | `UP` |
| `/actuator/health/liveness` | is this JVM wedged? Consults `livenessState` only, never the database | `UP` |
| `/actuator/info` | which build is this? Populated by `spring-boot-maven-plugin`'s `build-info` goal | a version and build timestamp |
| `GET /api/reporting/outbox/summary` | are events being published? | `pendingCount` and `processingCount` low and not trending up |
| `GET /api/reporting/outbox/dead-letters` | has anything permanently failed? | `items` empty |
| `GET /api/reporting/reconciliation/breaks` | do ledger and statement agree? | `items` empty |
| `/actuator/prometheus` | the eleven `corebank_*` series in one place — the seven below, plus four `corebank_read_model_*` gauges from `ReadModelMetricsBinder` | see below |

The `corebank_*` series and what a bad value means, read from `BankingMetrics` and
`LedgerCommandService`:

| Series | Type | Meaning |
|---|---|---|
| `corebank_outbox_pending` | gauge | `outbox_events` rows in `PENDING` or `PROCESSING`. Sustained growth means the publisher is behind or absent. |
| `corebank_outbox_dead_letters` | gauge | rows in `outbox_dead_letters`. Every one needs a human. |
| `corebank_reconciliation_open_breaks` | gauge | `reconciliation_breaks` rows in `OPEN` or `INVESTIGATING`. |
| `corebank_idempotency_in_flight` | gauge | `idempotency_keys` rows at `IN_PROGRESS`. Traffic, not fault. |
| `corebank_idempotency_stale` | gauge | `IN_PROGRESS` claims whose `claimed_at` is older than the takeover lease. Any non-zero value is a fault. |
| `corebank_ledger_journals` | gauge | total `ledger_journals` rows. A **size**, for growth and retention. It is a global `COUNT(*)`, so every replica reports it identically — do not `rate()` it and do not `sum()` it across replicas. |
| `corebank_ledger_journals_posted_total` | counter | journals **committed** by this process, incremented from an `afterCommit` synchronisation in `LedgerCommandService`. This is the throughput signal and the one that is safe to `sum(rate(...))` across replicas. |

All six gauges are refreshed by `BankingMetrics.refresh()` on a `fixedDelay` of 15000 ms
with an `initialDelay` of 5000 ms, and are served from memory rather than queried per
scrape. A gauge is therefore up to 15 seconds stale, and reads `0` for roughly the first
five seconds of a process's life. Both matter when reading a dashboard immediately after
a restart.

`/actuator/prometheus` requires authentication. Exposed actuator endpoints are
`health`, `info`, `metrics` and `prometheus`; only `/actuator/health`,
`/actuator/health/liveness` and `/actuator/health/readiness` are anonymous, and they are
listed individually in `DemoSecurityConfig` rather than as `/actuator/health/**`, so
per-indicator detail paths stay authenticated.

## Starting the service

This section is the authoritative startup procedure for local operation.

Use the committed Maven wrapper (`./mvnw`) so the build tool version is controlled by
the repository. PostgreSQL is the only dependency required for the documented startup
path.

Redis is optional for startup because it provides rate limiting and idempotency replay
acceleration rather than financial truth. Start Redis when those behaviours are being
exercised.

PostgreSQL alone is sufficient because nothing in the documented startup path requires
Redis. `management.health.redis.enabled` is `false` in `application.yml`, so Redis
cannot make the aggregate health endpoint report `DOWN`; readiness consults
`readinessState` and `db` only. Redis provides rate limiting and an idempotency replay
cache, both of which degrade open. Start Redis when rate limiting or the replay cache is
what is being exercised — not to get the service up.

### Start procedure (local, authoritative)

Prerequisites:
- Docker available for the database container
- JDK 17
- port `9090` free, and port `5433` free for the mapped database port

Template commands:

```bash
docker compose up -d postgres
docker compose ps postgres          # wait for the healthcheck to report healthy

./mvnw spring-boot:run
```

The application listens on `9090`. The browser dashboard is at
`http://localhost:9090/dashboard/`. Flyway runs inside the application at startup; the
migration set currently ends at `V28__idempotency_claim_lease.sql`.

To start it with the local telemetry stack attached:

```bash
docker compose -f deploy/observability/docker-compose.yml up -d
COREBANK_OTLP_ENABLED=true COREBANK_LOG_FORMAT=ecs ./mvnw spring-boot:run
```

Do not set `COREBANK_OTLP_ENABLED=true` without a collector listening. Instrumentation
and export are separate switches precisely so that this is a choice: with export on and
no collector, the application produces a steady stream of export failures and no
telemetry.

### Start procedure (Kubernetes)

Covered in [deploy/kubernetes/README.md](../deploy/kubernetes/README.md). The one
operational point worth repeating: apply the directory through kustomize
(`kubectl apply -k .`), never `kubectl apply -f deployment.yaml`, because
`deployment.yaml` carries a placeholder `:latest` tag and `kustomization.yaml` carries
the `sha-<commit>` tag that is actually deployed.

### Environment variable reference

Every entry below was read from `application.yml`, `application-showcase.yml`,
`.env.example`, `Dockerfile` or `deploy/kubernetes/configmap.yaml`.

| Variable | Bound property | Default | Notes |
|---|---|---|---|
| `PORT` / `SERVER_PORT` | `server.port` | `9090` | `PORT` wins; `SERVER_PORT` is the fallback |
| `SPRING_DATASOURCE_URL` | `spring.datasource.url` | `jdbc:postgresql://localhost:5433/corebank` | a `postgres://` URL is rewritten to JDBC at startup by `RenderDatabaseUrlEnvironmentPostProcessor` |
| `SPRING_DATASOURCE_USERNAME` | `spring.datasource.username` | `corebank` | from the Secret in Kubernetes |
| `SPRING_DATASOURCE_PASSWORD` | `spring.datasource.password` | `corebank123` | from the Secret in Kubernetes |
| `COREBANK_DB_POOL_SIZE` | `spring.datasource.hikari.maximum-pool-size` | `20` | see [Operating thresholds and capacity](#operating-thresholds-and-capacity) before changing |
| `SPRING_PROFILES_ACTIVE` | — | none | `showcase` disables Kafka auto-configuration and **denies** the destructive ops paths |
| `COREBANK_KAFKA_ENABLED` | `corebank.kafka.enabled` | `true` when unset | `false` removes the `OutboxEventPublisher` bean entirely |
| `COREBANK_LOG_FORMAT` | `logging.structured.format.console` | empty (plain console) | `ecs` emits one Elastic Common Schema JSON document per line |
| `COREBANK_ENVIRONMENT` | `logging.structured.ecs.service.environment`, `management.metrics.tags.environment`, `deployment.environment` | `local` | one value feeds logs, metric tags and the OTLP resource |
| `COREBANK_OTLP_ENABLED` | `management.tracing.export.enabled`, `management.otlp.metrics.export.enabled` | `false` | export only; instrumentation is always on |
| `COREBANK_OTLP_TRACES_ENDPOINT` | `management.opentelemetry.tracing.export.otlp.endpoint` | `http://localhost:4318/v1/traces` | |
| `COREBANK_OTLP_METRICS_ENDPOINT` | `management.otlp.metrics.export.url` | `http://localhost:4318/v1/metrics` | |
| `COREBANK_OTLP_TEMPORALITY` | `management.otlp.metrics.export.aggregation-temporality` | `delta` | delta, not Spring Boot's cumulative default; Dynatrace ingests deltas |
| `COREBANK_TRACE_SAMPLE_RATE` | `management.tracing.sampling.probability` | `1.0` | lower under real load |
| `MANAGEMENT_SERVER_PORT` | `management.server.port` | unset (actuator shares `9090`) | set to `9091` in the Kubernetes ConfigMap so the Service and Ingress cannot reach actuator |
| `LOGGING_LEVEL_COM_COREBANK` | `logging.level.com.corebank` | framework default | set to `INFO` in the Kubernetes ConfigMap |
| `JAVA_OPTS` | — | `-XX:MaxRAMPercentage=70` in the image | the Kubernetes ConfigMap overrides it to `-XX:MaxRAMPercentage=60 -XX:+UseG1GC` |
| `TZ` | — | `UTC` in the image | the business date derives from the JVM default zone, so this must not follow the host |
| `SPRING_DATA_REDIS_HOST` / `SPRING_DATA_REDIS_PORT` | standard Spring binding | `localhost` / `6379` | optional; the Redis health indicator is disabled |
| `COREBANK_SHOWCASE_TOKEN` | `corebank.showcase.token` | `demo-secure-token-change-me` | **bound but never read** — see the note below |

Two entries need their behaviour stated rather than their default:

`SPRING_PROFILES_ACTIVE=showcase` is the profile the Kubernetes ConfigMap sets and
`DEPLOY.md` sets on Render. It turns on `corebank.showcase.token-gate-enabled`, and
`DemoSecurityConfig` responds to that flag with `denyAll()` on
`/api/ops/maintenance/**`, `/api/ops/executions/**` and `/api/ops/security/**`. That is
a flat denial, not a token gate: nothing anywhere in `src/main/java` reads
`corebank.showcase.token`, so `COREBANK_SHOWCASE_TOKEN` unlocks nothing. In any
`showcase` deployment the partition, idempotency-cleanup and approval-execution jobs in
[Scheduled and on-demand ops jobs](#scheduled-and-on-demand-ops-jobs) are unreachable
over HTTP and return `403`. This is a gap, not a decision — the property name and the
comment beside it in `application-showcase.yml` both describe a token check that was
never implemented.

The same matcher list has a second problem worth recording: `/api/ops/security/**`
matches no controller. The customer-secret endpoints are at `/api/ops/customers/**`
(`OpsCustomerSecretController`), so the gate covers a path that does not exist while
leaving the endpoints it was evidently meant to cover ungated.

## Stopping and draining

### Graceful shutdown

`spring.lifecycle.timeout-per-shutdown-phase` is `30s` and `server.shutdown` is
`graceful`, so an in-flight money command finishes rather than being killed while
holding `SELECT ... FOR UPDATE` locks. In Kubernetes,
`terminationGracePeriodSeconds: 45` and a `preStop` `sleep 5` sit around that: the sleep
lets kube-proxy remove the pod from the Service endpoints before the application stops
accepting connections, which is what actually prevents dropped requests during a
rollout.

Locally, stop with `Ctrl-C` or `SIGTERM`. Never `SIGKILL` a running instance — see
[Abrupt termination during money commands](#abrupt-termination-during-money-commands)
for what that costs.

### Draining the money path — and the control that is missing

`SystemModeService` reads a runtime mode from `system_configs` and four modes exist:
`RUNNING`, `EOD_LOCK`, `MAINTENANCE`, `READ_ONLY`. The enforcement is real, and it
applies in two different directions:

- `SystemModeService.enforceWriteAllowed()` is called by
  `IdempotentMoneyCommandTemplate` (so every idempotent money command passes through it)
  and by `LoanApplicationService` in two places. Anything other than `RUNNING` blocks
  the write.
- `OpsRuntimeModePolicy.requireRunningForMoneyImpactWrite()` guards outbox dead-letter
  requeue and approval execution — those also need `RUNNING`.
- `OpsRuntimeModePolicy.requireNonRunningForMaintenanceJob()` guards partition
  maintenance, idempotency cleanup and both reconciliation run endpoints — those
  **refuse** to run while the mode is `RUNNING`, and answer `409`.

That last one is the operationally important half: the maintenance jobs and the money
path are mutually exclusive by design, so draining is a prerequisite for running them,
not an optional precaution.

**There is no controller under `ops/system`, and no endpoint anywhere changes the
mode.** `SystemModeService.setMode(...)` exists and is called by nothing in
`src/main/java`. The only way an operator can change the runtime mode is a hand-written
`UPDATE` against `system_configs`.

Stated plainly, that means: draining this system for maintenance requires a human with
write access to the production database issuing an ad-hoc `UPDATE` to a table that
controls whether money can move. The change leaves no audit event, passes through no
maker/checker approval, is not rate limited, is not reversible by any application
control, and a typo in the JSON — anything `SystemMode.valueOf` cannot parse — is
silently treated as `RUNNING` by `getCurrentMode()`, which fails **open** on the money
path. This is the most dangerous procedure in this runbook.

`system_configs` is created in `V2__base_schema.sql` and seeded again in
`V4__seed_data.sql`, both with the same two rows:

```sql
INSERT INTO system_configs (config_key, config_value, description)
VALUES
    ('runtime_mode', '{"status":"RUNNING"}'::jsonb, 'RUNNING | EOD_LOCK | MAINTENANCE | READ_ONLY'),
    ('eod_control', '{"is_open":true,"business_date":null}'::jsonb, 'Business date and EOD/BOD control flags')
ON CONFLICT (config_key) DO NOTHING;
```

### Drain procedure (manual database update)

Prerequisites:
- a change record naming the operator, the window and the mode being set
- a second operator observing, because nothing in the application enforces four eyes here
- the current value captured first, so the restore step is a known value rather than an assumption

Template commands:

```sql
-- 1. Capture the current mode. Keep this output with the change record.
SELECT config_key, config_value, updated_at, updated_by
FROM system_configs
WHERE config_key = 'runtime_mode';

-- 2. Drain. Use exactly one of MAINTENANCE, EOD_LOCK, READ_ONLY — the string must
--    match SystemMode.valueOf or getCurrentMode() falls back to RUNNING and the
--    money path stays open.
UPDATE system_configs
SET config_value = jsonb_set(config_value, '{status}', to_jsonb('MAINTENANCE'::text)),
    updated_at = now(),
    updated_by = 'ops:<operator-id>'
WHERE config_key = 'runtime_mode';

-- 3. Confirm the value the application will read, not the value you believe you set.
SELECT config_value->>'status' AS status FROM system_configs WHERE config_key = 'runtime_mode';
```

Restore by repeating step 2 with `'RUNNING'`.

How to confirm the drain took effect: `SystemModeService.getCurrentMode()` runs a fresh
query per call, so there is no cache to wait out and no restart needed. Verify
behaviourally rather than by reading the row back — a money command should now be
refused, and a maintenance job should now be accepted.

What a blocked caller sees: on the money path `enforceWriteAllowed()` raises
`CoreBankException("System is in MAINTENANCE mode. Writes are not allowed.")`, which
each money controller maps to `409 CONFLICT` by matching the substring
`writes are not allowed`. That mapping is present in `TransferController`,
`PaymentController`, `LendingController` and `DepositController`. There is no
`@ControllerAdvice` in this application, so any money-path `CoreBankException` outside
those four controllers' `try`/`catch` becomes a `500`.

One consequence operators should know before draining: the idempotency key is claimed
*before* the mode check runs, and the failure path marks it `FAILED`. A `FAILED` key is
retryable — `evaluateExisting` transitions it back to `IN_PROGRESS` on a later attempt
with the same payload hash — so a client that retries after the mode returns to
`RUNNING` succeeds. Draining does not strand keys.

**Gap, not a decision: there is no operator control for runtime mode.** The missing
piece is a controller under `ops/system` exposing mode transitions behind
`IamAuthorizationService` and the existing maker/checker approval flow, writing an audit
event through `AuditService` the way every other operator action with financial impact
does. `SystemModeService` already has `setMode`, `setBusinessDate` and `setEodOpen`
ready to be called. Until that exists, the EOD/BOD transition job in the next section
has no implementation either, for the same reason.

## Alert response

The six subsections below match the `alert:` names in
[deploy/observability/alerts.yml](../deploy/observability/alerts.yml) exactly, so the
`runbook:` annotation on each rule resolves to the heading here.

A correction that applies to one of them: the `ReconciliationBreaksOpen` annotation
points at `GET /api/ops/reconciliation/breaks`. That endpoint does not exist.
`OpsReconciliationController` maps `/api/ops/reconciliation` and defines only
`POST /runs` and `POST /external/runs`. Breaks are read from
`GET /api/reporting/reconciliation/breaks` on `ReportingController`. The annotation
should be corrected; this runbook uses the path that exists.

### OutboxBacklogGrowing

**What fired:** `corebank_outbox_pending > 100` for 10 minutes. Severity `warning`.

**What it means:** events were written inside money transactions and committed with the
balance change, but have not been published. Every downstream consumer is drifting out
of date while the API still returns `200`. The money is correct; the rest of the estate
does not know about it.

**Check:**

```bash
curl -s -u "$OPS" "$BASE/api/reporting/outbox/summary"
```

The response is an `OutboxSummary`: `pendingCount`, `processingCount`, `failedCount`,
`processedCount`, `deadLetterCount`, `retryQueueCount`, `oldestRetryQueueCreatedAt`,
`oldestRetryQueueAgeSeconds`. A growing `pendingCount` with `processedCount` flat means
the publisher is not consuming; a growing `retryQueueCount` with a rising
`oldestRetryQueueAgeSeconds` means it is consuming and failing to deliver.

**Action — check this first, before anything else.** `OutboxEventPublisher` is annotated
`@ConditionalOnProperty(name = "corebank.kafka.enabled", havingValue = "true", matchIfMissing = true)`.
Both `application-showcase.yml` and `deploy/kubernetes/configmap.yaml` set that property
to `false`. In any deployment running the `showcase` profile or those Kubernetes
manifests, **the publisher bean does not exist**, nothing drains the outbox, and this
alert will fire and stay firing permanently. That is the expected steady state of the
current deployment configuration, not an incident.

So the first question is which deployment this is:

```bash
curl -s -u "$OPS" "$BASE/actuator/prometheus" | grep -E '^corebank_outbox_pending'
```

- If Kafka is disabled by configuration, the alert is telling the truth about a
  deliberate configuration, and the response is to silence it for that environment
  rather than to investigate. Money events are still durably recorded in
  `outbox_events`; only publication is paused.
- If Kafka is meant to be enabled, confirm the publisher is scheduled at all
  (`@EnableScheduling` is on `CorebankApiApplication`; its absence was a real defect
  once), then check broker reachability from the pod, then check for exceptions logged
  by `OutboxEventPublisher`.

The publisher runs on a `fixedDelay` of 5000 ms and takes `BATCH_SIZE` 10 events per
pass, with `MAX_RETRIES` 3, `RETRY_BACKOFF_SECONDS` 30, `RECLAIM_TIMEOUT_SECONDS` 300
and `PUBLISH_TIMEOUT_SECONDS` 10. At 10 events per 5 seconds, a backlog drains at
roughly 120 events per minute per instance — which is the number to use when estimating
how long recovery will take, and a reason a backlog of tens of thousands will not clear
inside a maintenance window.

**Escalate when:** the publisher is running, the broker is reachable, and `pendingCount`
is still climbing after two drain intervals. That combination means events are being
claimed and not completed, which is a different fault from the two common causes.

**Confirm cleared:** `pendingCount` falls and `processedCount` rises in successive calls
to `/api/reporting/outbox/summary`. The alert clears when
`corebank_outbox_pending` drops to 100 or below; allow up to 15 seconds for the gauge
refresh plus one Prometheus evaluation interval.

### OutboxDeadLetters

**What fired:** `corebank_outbox_dead_letters > 0` for 5 minutes. Severity `critical`.

**What it means:** these events exhausted `MAX_RETRIES` and will never be published
without intervention. Each one is a downstream system that has permanently missed a
money event.

**Check:**

```bash
curl -s -u "$OPS" "$BASE/api/reporting/outbox/dead-letters?limit=50"
curl -s -u "$OPS" "$BASE/api/reporting/outbox/dead-letters?eventType=LOAN_DEFAULTED&limit=50"
```

The response is an `OutboxDeadLetterPage` of `OutboxDeadLetterItem`, each carrying
`deadLetterId`, `outboxEventId`, `aggregateType`, `aggregateId` and `eventType`. The
filters are `limit`, `eventType`, `aggregateType`, `fromDeadLetteredAt` and
`toDeadLetteredAt`; passing `fromDeadLetteredAt` later than `toDeadLetteredAt` is
rejected with `400`.

**Action:** fix the cause before requeueing — a requeue against an unchanged cause
produces the same dead letters and a second set of retries. Group the items by
`eventType` and `aggregateType` first; a single failing consumer contract shows up as
one dominant `eventType`.

Requeue is a money-impact write and requires `RUNNING` mode. Two routes exist:

- Single event, `ROLE_OPS` or `ROLE_ADMIN`:
  `POST /api/reporting/outbox/dead-letters/{outboxEventId}/requeue`. Returns `200` with
  status `REQUEUED`, `404` with status `NOT_FOUND`, or `409` for any other status.
- Bulk, behind maker/checker approval: raise an approval with
  `POST /api/ops/approvals` carrying `operationType` `OUTBOX_BULK_REQUEUE` and an
  `operationPayload` containing `outboxEventIds`, have a different operator approve it
  with `POST /api/ops/approvals/{approvalId}/approve`, then execute with
  `POST /api/ops/executions/outbox-dead-letter-requeue-bulk` and a body of
  `{"approvalId": "<uuid>"}`. Creating needs permission `APPROVAL_CREATE`, deciding
  needs `APPROVAL_DECIDE`, executing needs `APPROVAL_EXECUTE`. The execution response
  reports `requestedCount`, `requeuedCount`, `notFoundCount` and `conflictCount`.

There is also an unapproved bulk route,
`POST /api/reporting/outbox/dead-letters/requeue-bulk`, which takes
`{"outboxEventIds":[...]}` directly and needs only `ROLE_OPS` or `ROLE_ADMIN`. It is
bounded by `outboxReportingService.maxBulkRequeueSize()`. Prefer the approval route for
anything an auditor would want to see two names against.

**Escalate when:** the dead letters are money events whose downstream effect is
customer-visible (a `LOAN_DEFAULTED` that never reached collections, a payment event
that never reached a statement feed). Requeueing restores delivery; it does not tell you
what decisions were made downstream in the meantime.

**Confirm cleared:** `corebank_outbox_dead_letters` returns to `0` and
`/api/reporting/outbox/dead-letters` returns an empty `items` list. Watch
`corebank_outbox_pending` afterwards — a requeue moves events back into the pending
pool, so a large requeue trips `OutboxBacklogGrowing` on purpose.

### ReconciliationBreaksOpen

**What fired:** `corebank_reconciliation_open_breaks > 0` for 15 minutes. Severity
`critical`.

**What it means:** the ledger and an external statement disagree. Until this is resolved
the books cannot be certified. The gauge counts `reconciliation_breaks` rows in `OPEN`
or `INVESTIGATING`.

**Check** — note the path correction above; this is `reporting`, not `ops`:

```bash
curl -s -u "$OPS" "$BASE/api/reporting/reconciliation/breaks?status=OPEN&limit=50"
curl -s -u "$OPS" "$BASE/api/reporting/reconciliation/breaks?severity=HIGH&limit=50"
curl -s -u "$OPS" "$BASE/api/reporting/reconciliation/external/breaks?limit=50"
```

Each `ReconciliationBreakView` carries `reconciliationBreakId`, `runId`, `breakType`,
`referenceType`, `referenceId`, `severity`, `status`, a free-form `details` map,
`openedAt` and `resolvedAt`. Filters on the internal endpoint are `runId`, `status`,
`severity` and `limit`; the external endpoint adds `statementRef` and `breakType`.

**Action:** classify before touching anything. `breakType` and the `details` map say
whether the ledger is missing an entry the statement has, or carries one the statement
does not, or the two disagree on an amount. Investigate against `ledger_journals` and
`ledger_postings` using the `referenceType`/`referenceId` pair.

Then observe the hard rule from [07-financial-invariants.md](07-financial-invariants.md)
and [19-runtime-failure-modes.md](19-runtime-failure-modes.md): **never repair ledger
truth by editing historical rows.** A break is resolved by a compensating posting made
through the normal money path, or by correcting the external side, never by an `UPDATE`
on a journal or a posting.

Re-running reconciliation to see whether a break persists needs a non-`RUNNING` mode —
`POST /api/ops/reconciliation/runs` calls `requireNonRunningForMaintenanceJob()` and
answers `409` otherwise. See [Stopping and draining](#stopping-and-draining).

**Escalate when:** any break's amount is material, when breaks appear across more than
one `referenceType` at once (which points at the reconciliation input rather than at a
transaction), or when the count grows between runs. Financial control and the
accountable finance owner are in scope for this alert in a way they are not for the
others.

**Confirm cleared:** `corebank_reconciliation_open_breaks` returns to `0` after the
compensating action and a fresh reconciliation run. A break that disappears without a
compensating entry and without a change on the external side has not been resolved —
find out why it stopped being reported.

### IdempotencyKeysStranded

**What fired:** `corebank_idempotency_stale > 0` for 5 minutes. Severity `warning`.

This rule previously fired on `corebank_idempotency_in_flight > 10` for 15 minutes,
which is a traffic measure: ten money commands in flight at once is a busy instance, not
a fault. It paged on load and stayed silent on the fault it was written for. The
threshold is `0` rather than a number chosen to suppress noise, because a claim older
than the takeover lease is unambiguous — its owner is gone.

**What it means:** a money command claims its idempotency key before executing and
resolves it afterwards. A claim whose `claimed_at` is older than the lease belongs to an
instance that stopped between those two points. PostgreSQL rolled the money back, so
nothing was lost or double-posted, but the customer's operation did not happen and
nobody has retried it. The lease default is `PT2M`
(`corebank.idempotency.claim-lease`, read by `IdempotencyService` and `BankingMetrics`;
not set in `application.yml`, so the `@Value` default applies).

**Check:**

```sql
SELECT idempotency_key, status, claimed_at, created_at, expires_at
FROM idempotency_keys
WHERE status = 'IN_PROGRESS'
  AND claimed_at < now() - interval '2 minutes'
ORDER BY claimed_at;
```

Then cross-check what actually committed, per the alert's own annotation:

```sql
SELECT journal_id, created_at
FROM ledger_journals
WHERE created_at BETWEEN '<claimed_at - 1 minute>' AND '<claimed_at + 5 minutes>'
ORDER BY created_at;
```

**Action:** in normal operation, none. The takeover is automatic — a retry with the same
key and the same payload hash takes over a claim past its lease, via a conditional
update so two concurrent retries cannot both win, and the takeover is logged as a
warning. A non-zero `corebank_idempotency_stale` means nobody has retried, so the
operator's job is to establish whether the client will.

Do this before contacting anyone: correlate each stranded key with `ledger_journals` to
establish what committed. This is the step the alert annotation calls for and it is not
optional — a stranded claim says the command did not complete, not that it did nothing.

**Do not delete stranded keys to clear the alert.** Deleting a claim makes the operation
replayable with no record that it was ever attempted, and the maintenance cleanup job
deliberately does not touch `IN_PROGRESS` rows for exactly this reason. Deletion by hand
was the recovery path before `V28__idempotency_claim_lease.sql` existed, and the lease
was added so that it would never be necessary again.

**Escalate when:** the count does not fall after clients have had time to retry, or when
a stranded key correlates with a committed journal. The second case is the one that
matters: it would mean a claim was left behind after the money moved, which contradicts
the design and should be investigated before any cleanup.

**Confirm cleared:** `corebank_idempotency_stale` returns to `0`. Expect up to 15
seconds of gauge refresh lag. `corebank_idempotency_in_flight` staying high on its own
is not a fault and should not be acted on.

### MoneyEndpointErrorRate

**What fired:** the ratio of `5xx` to all requests on
`/api/(payments|transfers|deposits|lending).*` exceeded `0.05` over a 5-minute window,
for 5 minutes. Severity `critical`.

The scope is deliberate: an error rate averaged over every route lets a broken transfer
path hide behind healthy dashboard traffic.

**What it means:** more than one in twenty money requests is failing with a server
error. Note what this does *not* include — the rule counts `5xx` only.

**Check:**

```bash
# Which money route, and which status.
curl -s -u "$OPS" "$BASE/actuator/prometheus" \
  | grep 'http_server_requests_seconds_count' \
  | grep -E 'uri="/api/(payments|transfers|deposits|lending)'
```

**Action — separate "failing" from "refusing" first.** This system refuses a great deal
on purpose, and a correct refusal is a `4xx`:

- a duplicate idempotency claim while the original is still running → `400` (see the
  known rough edge below)
- an idempotency key reused with a different payload → `IdempotencyConflictException`
- a money command while the runtime mode is not `RUNNING` → `409`
- insufficient available balance, failed validation, an unapproved maker/checker action
  → `4xx`

None of those are in this alert's numerator. A genuine `5xx` on a money endpoint means
either an unmapped exception or an infrastructure failure. Because there is no
`@ControllerAdvice`, any `CoreBankException` raised outside the four money controllers'
`try`/`catch` blocks surfaces as a `500`, so "unmapped exception" is a live possibility
rather than a theoretical one.

Check connection-pool saturation before assuming the database is down:
`hikaricp_connections_pending` on `/actuator/prometheus`. Pool starvation presents as
`500`s with connection-timeout messages — that is the failure recorded in
[A retry storm on one idempotency key](#a-retry-storm-on-one-idempotency-key), and it is
the single most likely cause of a sudden `5xx` spike on these routes.

With `datasource-micrometer-spring-boot` in place, JDBC `CONNECTION` and `QUERY` spans
exist, so a trace distinguishes "the API is slow", "this query is slow" and "we ran out
of connections" directly. Configuration is under the top-level `jdbc:` key in
`application.yml`; bind parameter values are deliberately excluded from spans.

**Escalate when:** the rate does not fall after the immediate cause is addressed, or
when `5xx`s continue on a route while the database is demonstrably healthy. Engineering
owns an unmapped exception; the platform owns a pool or database fault.

**Confirm cleared:** the ratio falls below `0.05` and stays there for longer than the
5-minute `for` window. Check that the underlying money operations succeeded rather than
merely stopping — a fall in error rate because callers gave up looks identical on this
metric.

**Known rough edge, unfixed:** a duplicate arriving while the original is still running
is answered `400`. The correct answer is `409`, and the distinction matters to a client
deciding whether to retry. Fixing it properly means giving the API typed errors rather
than patching one branch, because the mapping is a substring match on the exception
message across four controllers.

### MoneyEndpointLatency

**What fired:** the p95 of `http_server_requests_seconds_bucket` on
`/api/(payments|transfers).*` exceeded 2 seconds over a 5-minute window, for 10 minutes.
Severity `warning`.

**What it means:** these paths take row locks. Latency here usually means lock
contention or a slow database, and it turns into queued requests rather than errors — so
it will not show up as a failure rate, and `MoneyEndpointErrorRate` will stay quiet
while customers wait.

**Check — pool first, as the alert annotation says:**

```bash
curl -s -u "$OPS" "$BASE/actuator/prometheus" | grep -E '^hikaricp_connections'
```

`hikaricp_connections_pending` is the number to read. A money command holds two
connections at once — the business transaction, and the `REQUIRES_NEW` transaction that
records the idempotency outcome inside it — so pool exhaustion presents as latency long
before it presents as errors.

Then look at a trace. `CONNECTION` spans show time spent acquiring a connection, which
is the signature of starvation; `QUERY` spans show a genuinely slow statement. Before
`datasource-micrometer` was added a transfer produced one server span with the entire
database portion inside it as unattributed time, and this distinction could not be made
at all.

**Action:**
- If `hikaricp_connections_pending` is non-zero and `hikaricp_connections_active` is at
  `maximum-pool-size`, this is starvation. See
  [A retry storm on one idempotency key](#a-retry-storm-on-one-idempotency-key).
- If the pool is healthy, look for lock contention on the accounts involved: a hot
  account taking concurrent writes, or a long-running transaction holding a row lock.
  `hot_account_profiles` and `ledger_account_balance_slots` exist for the first case;
  `GET /api/reporting/hot-accounts/{ledgerAccountId}/slots` reports the configured and
  runtime-applied slot strategy.
- If neither, look at the database itself.

**Escalate when:** p95 stays above 2 seconds with a healthy pool and no identifiable
lock holder, or when it rises towards the router timeout. That last point is the real
deadline: the OpenShift Route and the nginx Ingress both allow 60 seconds, so a command
slower than that is cut off by the router while its transaction may still commit, and
the caller is told a transaction failed that may yet succeed.

**Confirm cleared:** p95 falls below 2 seconds for longer than the 10-minute `for`
window, and `hikaricp_connections_pending` returns to `0`.

## Scheduled and on-demand ops jobs

[11-ops-reliability-security.md](11-ops-reliability-security.md) recommends nine ops
jobs and documents none of them. This section maps each to what is actually implemented.
Two of the nine have no implementation at all and six have nothing that runs them as a
job, and saying so is the point of the table.

| Recommended job | Implementation | Invocation |
|---|---|---|
| create future partitions | `PartitionMaintenanceService` | `POST /api/ops/maintenance/partitions/ensure-future` |
| expire holds | **none** | — |
| accrue deposit interest | per-contract money command only | `POST /api/deposits/accrue` |
| mark delinquent loans | single-contract, approval-gated only | `POST /api/ops/executions/loan-default` |
| EOD/BOD state transitions | **none** | manual `UPDATE` on `system_configs` |
| outbox retry/drain | `OutboxEventPublisher` | automatic, `@Scheduled(fixedDelay = 5000)` |
| snapshot generation | `SnapshotService` exists but **has no caller** | — |
| stale idempotency cleanup | `IdempotencyMaintenanceService` | `POST /api/ops/maintenance/idempotency/cleanup` |
| reconciliation jobs | `ReconciliationService`, `ExternalReconciliationService` | `POST /api/ops/reconciliation/runs`, `POST /api/ops/reconciliation/external/runs` |

Two things apply to every HTTP-invoked job below. They need `ROLE_OPS` or `ROLE_ADMIN`
(approval execution needs the `APPROVAL_EXECUTE` permission instead). And under the
`showcase` profile, everything under `/api/ops/maintenance/**` and
`/api/ops/executions/**` is denied outright — so in the Kubernetes and Render
deployments as configured, these jobs cannot be invoked over HTTP at all. See
[the environment variable reference](#environment-variable-reference).

There is no scheduler for any of them. `@Scheduled` appears in exactly three places in
`src/main/java`: `BankingMetrics.refresh()`, `ReadModelMetricsBinder`, and
`OutboxEventPublisher.processPendingEvents()`. Every job below other than outbox drain
is manual.

### Create future partitions

- **Invocation:** `POST /api/ops/maintenance/partitions/ensure-future`, body optional:
  `{"fromMonth":"2026-03","monthsAhead":3}`. `monthsAhead` defaults to 3 and is capped
  at 12; an out-of-range value returns `400`.
- **Requires:** non-`RUNNING` mode. Returns `409` while the system is `RUNNING`.
- **Response:** `PartitionMaintenanceResult` — `runId`, `fromMonth`, `monthsAhead`,
  `createdCount`, `existingCount`, `errorCount`, `items[]` (each with `parentTable`,
  `partitionName`, range bounds and a `CREATED`/`EXISTING` status), `note`.
- **What it changes:** creates monthly range partitions on `ledger_journals_p`,
  `ledger_postings_p` and `audit_events_p`. Registers a `PARTITION_MAINTENANCE` batch
  run and writes an audit event.
- **Safe to repeat:** yes. A partition that already exists is reported `EXISTING` rather
  than failing.
- **Read this before scheduling it.** These three `_p` tables were created in
  `V3__phase1_hardening.sql` and **nothing writes to them**. The write path inserts into
  the unpartitioned `ledger_journals` and `ledger_postings` from `V2__base_schema.sql`
  (`LedgerCommandService`) and into `audit_events` (`AuditService`). So this job
  maintains partitions on tables that are empty, and the growth it was meant to control
  is happening on tables it does not touch. See
  [Operating thresholds and capacity](#operating-thresholds-and-capacity).

### Expire holds

No implementation. A hold is released by `POST /api/payments/void-hold` against one
payment order; nothing sweeps expired holds. An unreleased hold keeps reducing available
balance without affecting posted balance, indefinitely.

This is a gap, not a decision. It is the one absence in this list with a direct customer
effect — money a customer cannot spend, with no process that will ever give it back.

### Accrue deposit interest

`POST /api/deposits/accrue` is a money command against a single deposit contract, with
idempotency and audit like any other. There is no batch accrual job and no scheduler, so
a daily accrual across the book would have to be driven by an external caller iterating
contracts. That the endpoint exists does not make the job exist.

### Mark delinquent loans

`POST /api/ops/executions/loan-default` transitions **one** contract to defaulted, and
only through maker/checker: create an approval with `operationType` `LOAN_DEFAULT` and a
payload of `{"contractId": "<uuid>", "asOfDate": "<yyyy-MM-dd>"}`, have a second
operator approve it, then execute with `{"approvalId":"<uuid>"}`. Requires
`APPROVAL_EXECUTE` and `RUNNING` mode.

The response is a `LoanDefaultExecutionResponse` carrying `transitioned`,
`outstandingPrincipalMinor`, `overdueInstallmentCount` and the resulting `status`. When
`transitioned` is true the execution writes a `LOAN_DEFAULTED` audit event and appends a
`LOAN_DEFAULTED` outbox message. Re-executing a claimed approval is refused by
`claimApprovedExecution`, so it is not repeatable by design — that is the maker/checker
guarantee working, not a defect.

There is no job that *finds* delinquent contracts. Identifying them is currently manual.

### EOD/BOD state transitions

No implementation, as set out in [Stopping and draining](#stopping-and-draining).
`SystemModeService.setBusinessDate` and `setEodOpen` exist and are called by nothing.
The `eod_control` row in `system_configs` holds `is_open` and `business_date` and is
seeded `{"is_open":true,"business_date":null}` — a business date that has never been
set. Any EOD procedure today is a hand-written `UPDATE`.

### Outbox retry/drain

The only job that runs by itself. `OutboxEventPublisher.processPendingEvents()` is
`@Scheduled(fixedDelay = 5000)` and `@Transactional`, taking 10 events per pass. It
reclaims events whose claim is older than `RECLAIM_TIMEOUT_SECONDS` (300), retries up to
`MAX_RETRIES` (3) with `RETRY_BACKOFF_SECONDS` (30) between attempts, and waits
`PUBLISH_TIMEOUT_SECONDS` (10) on each publish.

It exists only when `corebank.kafka.enabled` is `true`. Under the `showcase` profile or
the Kubernetes ConfigMap it is absent. Nothing logs the fact that it is absent, which is
why `OutboxBacklogGrowing` firing forever is the first symptom an operator sees.

### Snapshot generation

`SnapshotService` writes to `account_balance_snapshots` (created in
`V11__daily_snapshots.sql`) and is read by `ReconciliationService`. It has **no callers
anywhere in `src/main/java`** — no controller, no scheduler, no other service. Snapshots
are therefore never generated, which means any reconciliation logic depending on a
snapshot is comparing against rows nobody writes.

Open question, and it should be answered before the next reconciliation run is trusted:
what does `ReconciliationService` do when the snapshot side of its `LEFT JOIN` is empty
for every account — does it report every account as a break, or as checked-and-matching?
This was not determined by reading alone and needs an integration test or a live run.

### Stale idempotency cleanup

- **Invocation:** `POST /api/ops/maintenance/idempotency/cleanup`, body optional:
  `{"limit":1000,"dryRun":true}`. `limit` defaults to 1000 and must be between 1 and
  10000. **`dryRun` defaults to `true`** when the field or the body is omitted, so a
  call with no body reports and deletes nothing.
- **Requires:** non-`RUNNING` mode. Returns `409` while the system is `RUNNING`.
- **Response:** `IdempotencyCleanupResult` — `runId`, `dryRun`, `limit`,
  `candidateCount`, `deletedCount`, `truncated`.
- **What it changes:** deletes `idempotency_keys` rows whose `status` is `SUCCEEDED` or
  `FAILED` and whose `expires_at` is more than 24 hours in the past. Registers an
  `IDEMPOTENCY_CLEANUP` batch run and writes an `IDEMPOTENCY_KEYS_CLEANED` audit event.
- **Safe to repeat:** yes. Run it with `dryRun` true first, compare `candidateCount`
  against expectation, then run with `dryRun` false.
- **It does not touch `IN_PROGRESS` rows, and must not.** Stranded claims are recovered
  by lease takeover, not by deletion. Do not reach for this job in response to
  `IdempotencyKeysStranded` — it will report `candidateCount` unchanged and clear
  nothing, which is correct behaviour.

### Reconciliation jobs

- **Invocation:** `POST /api/ops/reconciliation/runs` with
  `{"businessDate":"yyyy-MM-dd","limit":<n>}`. `businessDate` is required; a missing or
  unparseable value returns `400`. External settlement reconciliation is
  `POST /api/ops/reconciliation/external/runs` with `statementRef`, `provider`,
  `statementDate`, `processingLimit`, `dryRun` and `entries[]`; that endpoint requires a
  body and rejects an absent one with `400`.
- **Requires:** non-`RUNNING` mode for both. Returns `409` while the system is
  `RUNNING`.
- **Response:** `ReconciliationRunResult` — `runId`, `businessDate`, `checkedCount`,
  `breakCount`, `missingCount`, `mismatchCount`, `truncated`.
- **What it changes:** opens `reconciliation_breaks` rows for discrepancies found. It
  does not move money and does not modify the ledger.
- **Safe to repeat:** yes, and re-running is the normal way to confirm a break was
  resolved. Read the results back with
  `GET /api/reporting/reconciliation/breaks?runId=<runId>`.

### Batch run history

Every job that registers a batch run is queryable afterwards, which is the audit trail
for "did this run, and what did it do":

```bash
curl -s -u "$OPS" "$BASE/api/ops/batch-runs?batchType=IDEMPOTENCY_CLEANUP&limit=20"
curl -s -u "$OPS" "$BASE/api/ops/batch-runs/{runId}"
```

`/api/ops/batch-runs` is read-only — `GET` for the list (filters `batchType`, `status`,
`limit`) and `GET /{runId}` for one run, returning `404` if it does not exist. There is
no `POST` on this path; it reports batch runs, it does not start them. Jobs are started
through the endpoints above.

## Incident procedures

These three are the failures from
[19-runtime-failure-modes.md](19-runtime-failure-modes.md), induced on a running system
against a real PostgreSQL and rewritten here as operator procedures. The invariant
checked after every scenario was total money unchanged, no unbalanced journal, no
negative balance — **it held in all three, including the two that produced HTTP `500`s.**
What broke was availability and recoverability, never correctness.

### Abrupt termination during money commands

**Induced:** 60 concurrent transfers, `SIGKILL` to the JVM once the money path was
demonstrably busy.

**Measured, before the fix:** money correct — PostgreSQL rolled back every uncommitted
transaction — but four idempotency keys left at `IN_PROGRESS` forever. Those commands
had not happened and could never be retried: the same key was refused on every later
attempt, and the cleanup job deletes only `SUCCEEDED` and `FAILED` rows. Recovery meant
deleting rows by hand.

**Measured, after the fix:** the same four stranded keys, replayed with their original
payloads, completed successfully, with the takeover recorded as a warning. Money still
conserved, no unbalanced journals.

**Operator procedure after any unclean stop — a `SIGKILL`, an OOM kill, a node loss:**

1. Confirm the process is back and readiness passes.
2. Read `corebank_idempotency_stale`. Non-zero is expected immediately after an unclean
   stop and should fall as clients retry.
3. For each stranded key, correlate against `ledger_journals` to establish what
   committed, using the query in
   [IdempotencyKeysStranded](#idempotencykeysstranded).
4. Confirm the money invariant across the affected accounts before declaring recovery:
   total unchanged, no unbalanced journal, no negative balance.
5. Do not delete `IN_PROGRESS` rows. The lease exists so that hand deletion is never
   the recovery path again.

### A retry storm on one idempotency key

**Induced:** 14 concurrent requests sharing a single idempotency key against a freshly
started instance.

**Measured:** the instance stopped serving for 32 seconds. Ten requests returned HTTP
`500` with connection-pool timeouts. Exactly one journal was posted — money correct, but
the service was effectively down for the duration.

The cause was not lock contention, which was the first hypothesis and was wrong. Pool
metrics showed all connections leased and thirteen threads queueing, with no lock
timeouts at all. A money command holds two connections at once — the business
transaction, and the `REQUIRES_NEW` transaction that records the idempotency outcome
inside it — so once concurrent commands reach the pool size, every one holds a
connection while waiting for a second that nobody can release.

Proving it took one measurement: the identical burst against a larger pool.

| Pool size | Elapsed | Responses | Pool timeouts | Journals |
|---|---|---|---|---|
| 10 (default) | 32s | 1 × 200, 3 × 400, 10 × 500 | 10 | 1 |
| 40 | 1s | 1 × 200, 13 × 400 | 0 | 1 |

That table is the entire justification for `COREBANK_DB_POOL_SIZE: 20`. It is also the
reason the alert on money endpoints watches latency as well as errors: starvation
presents as queueing first.

**Operator procedure when `MoneyEndpointLatency` or `MoneyEndpointErrorRate` fires with
pool pressure:**

1. Read `hikaricp_connections_active`, `hikaricp_connections_idle` and
   `hikaricp_connections_pending`. Active at `maximum-pool-size` with `pending` non-zero
   is starvation.
2. Confirm from traces: `CONNECTION` spans dominating means acquisition, not query time.
3. Identify whether the traffic is a retry storm on one key. Duplicates on one key
   currently return `400`, so a burst of `400`s on a money route alongside the latency
   is the signature.
4. Raise `COREBANK_DB_POOL_SIZE` only together with PostgreSQL's `max_connections`.
   Three replicas at 20 already use 60 of the default 100; raising the pool without
   raising the database moves the failure rather than removing it.
5. Record the incident against the open engineering item. The bound keeps the failure
   out of reach; the fix is to stop holding two connections per command, and until that
   refactor this remains reachable under enough concurrency.

**What makes this worth knowing:** a retry storm on one key is exactly the traffic
idempotency exists to absorb, and a freshly started instance is exactly what a client
retry storm meets after a rollout.

### Concurrent duplicates of the same command

**Induced:** 8, then 12, identical concurrent requests with one key.

**Measured:** exactly one journal every time, so exactly-once held. One request in the
first run returned HTTP `500` rather than a clean rejection. The cause was a unique
violation caught and the row then read back inside the same transaction — but a unique
violation aborts the PostgreSQL transaction, so every later statement in it fails with
`25P02`. The loser of the claim race learned it had lost by receiving a server error,
and a client that retries on `5xx` would have kept hammering.

**Verified after the fix:** twelve concurrent duplicates, one journal, no `500`s. The
claim is now an `INSERT ... ON CONFLICT DO NOTHING` whose row count decides the winner.

**Operator procedure:** if `500`s appear on a money route alongside duplicate-key
traffic, check the logs for SQLSTATE `25P02`. Its presence means a transaction was
poisoned by a caught unique violation, which this fix was meant to eliminate — treat it
as a regression and escalate to engineering rather than as a load problem.

### Things that did not break

Recorded because a scenario that finds nothing is still a result. Twelve concurrent
transfers with **distinct** idempotency keys completed cleanly: the starvation above
needs commands piling into the same claim, not load alone.

## Deployment and rollback

The procedures are in [deploy/kubernetes/README.md](../deploy/kubernetes/README.md).
What follows is the operational summary plus the limit that document's rollback section
does not state.

### Deploy procedure (Kubernetes rolling update)

Prerequisites:
- the target image tag exists in `ghcr.io` and the scan passed in CI
- `kustomization.yaml` has been edited by hand to the new `newTag`

Template commands:

```bash
kubectl apply -k deploy/kubernetes
kubectl -n corebank rollout status deployment/corebank-api --timeout=300s
```

What makes it zero-downtime: `maxUnavailable: 0` with `maxSurge: 1` adds a pod before
retiring one, so capacity never dips; `minReadySeconds: 15` stops a pod that passes
readiness and then crashes from counting as a successful step; and the `preStop` sleep
lets kube-proxy remove a terminating pod from the Service before the application stops
accepting connections.

Edit `newTag` by hand rather than with `kustomize edit set image`: on v5.4.3 that
command reindents every list, adds a redundant `newName` and silently drops the explicit
`includeSelectors: false`, turning a one-line version bump into a thirteen-line diff.

### Rollback procedure (same schema version only)

Prerequisites:
- the previous revision is still within `revisionHistoryLimit: 5`
- **the rollback target runs against the same schema version** — see below

Template commands:

```bash
kubectl -n corebank rollout undo deployment/corebank-api
kubectl -n corebank rollout status deployment/corebank-api
```

A failed rollout does not take the service down: the new pod never passes readiness, and
`maxUnavailable: 0` means no healthy pod was retired to make room for it.

### The limit: rollback is not an undo across a schema change

Flyway runs inside the application at startup and there are no down-migrations in
`src/main/resources/db/migration/` — the set runs from `V1` to `V28` — 26 migrations,
with no `V5` or `V6` — forward only, and `V28__idempotency_claim_lease.sql` carries an
explicit "DO NOT MODIFY THIS FILE" banner in keeping with that.

So `kubectl rollout undo` returns the *application* to the previous image while the
*database* stays at the schema the new version migrated it to. When a release contains a
migration, rolling back is not an undo; it is running old code against a newer schema.
Whether that works depends entirely on whether the migration was backward compatible
with the previous release, and nothing in the repository or CI checks that.

Practical consequence: before rolling back, establish whether the release being rolled
back included a migration. `git log` over `src/main/resources/db/migration/` between the
two tags answers it. If it did, `rollout undo` is not a safe reflex, and the recovery
path is a forward fix.

With three replicas starting at once, PostgreSQL's advisory lock serialises Flyway: the
first migrates, the other two block until it commits and then find nothing to do.
Nothing is corrupted, but the second and third pods sit in startup for as long as the
migration takes — which is why the startup probe allows five minutes.

### Deliberately not here

- **No down-migrations.** Forward-only is the stated design, not an omission.
- **No canary or blue/green.** Traffic goes Ingress or Route → Service → pods. Rollout
  safety comes from `maxUnavailable: 0` and the probes, which is a weaker guarantee than
  progressive delivery and is documented as such.
- **No service mesh, no mTLS between workloads.**

### Not done yet

- **The OpenShift overlay has never been applied to a live cluster.** It is rendered and
  schema-validated in CI, and nothing more. Every claim about SCC admission and router
  behaviour is reasoning from documentation.
- **No telemetry leaves a Kubernetes deployment.** `COREBANK_OTLP_ENABLED` is `"false"`
  and OTLP export is switched off. A collector manifest does now exist —
  `deploy/observability/kubernetes/` is a separate kustomize root that deploys one,
  rendered and schema-validated in CI — but `deploy/kubernetes` does not include it and
  `COREBANK_OTLP_ENABLED` stays `"false"`, so nothing is exported until both change. The
  alerts in this runbook therefore have nothing evaluating them on that path.
- **Nothing scrapes the metrics endpoint in-cluster.** The pods carry
  `prometheus.io/*` annotations but there is no `ServiceMonitor` and no credentials
  Secret, and the endpoint requires authentication.

## Log fields and incident queries

Logs go to stdout. With `COREBANK_LOG_FORMAT=ecs` each line is one JSON document in
Elastic Common Schema; with the variable unset the console format applies and the
correlation pattern in `application.yml` prefixes each line with
`[application,traceId,spanId,correlationId]`.

### ECS field reference

These field names were verified by reading the compiled
`ElasticCommonSchemaStructuredLogFormatter` in `spring-boot-4.0.8.jar`, which is the
formatter this application's `logging.structured.format.console: ecs` selects. They were
not read off a log line, because the application has not been run here.

| Field | Source |
|---|---|
| `@timestamp` | event instant |
| `log.level` | log level |
| `log.logger` | logger name |
| `message` | formatted message |
| `process.pid` | `spring.application.pid` |
| `process.thread.name` | thread name |
| `service.name` | `spring.application.name` → `corebank-api` |
| `service.version` | `spring.application.version`, from the jar manifest |
| `service.environment` | `logging.structured.ecs.service.environment` → `COREBANK_ENVIRONMENT` |
| `service.node.name` | `logging.structured.ecs.service.node-name`, unset in this repo |
| `error.type`, `error.message`, `error.stack_trace` | present only when a throwable was logged |
| `tags` | SLF4J markers, when any are set |
| `ecs.version` | fixed at `8.11` |

`logging.structured.json.context.include` is `true`, so MDC entries are written as
additional top-level members alongside those. In this application the MDC carries
`traceId` and `spanId` from Micrometer Tracing, and `correlationId`.

`service.version` resolves to `unknown` when the application runs without a jar manifest
— from an IDE, or via `./mvnw spring-boot:run`. The `:unknown` default in
`application.yml` is required rather than decorative: an unresolvable placeholder fails
context startup outright. In a container image the value comes from the `APP_VERSION`
build argument, passed to Maven as `-Drevision`.

### Correlation id versus trace id

They are deliberately different things and an incident search usually wants both.

A **trace id** identifies one technical request path. Micrometer Tracing generates it,
it appears in logs as `traceId`, and it is what pivots a log line to its trace in Tempo.

A **correlation id** identifies a business operation. `CorrelationIdFilter` establishes
one for every request: taken from the `X-Correlation-Id` request header when the caller
supplies a value matching `^[A-Za-z0-9_.:-]{1,128}$`, and generated as a random UUID
otherwise. An inbound id that fails that pattern is replaced rather than propagated,
because an id that reaches a log file is untrusted input and a caller could otherwise
inject newlines and forge log entries. The id is echoed back on the response in the same
header, so a caller reporting a problem can quote the exact value to search for.

It is published as tracing baggage, not written straight to the MDC. Micrometer owns
that MDC key once `correlationId` is declared in
`management.tracing.baggage.correlation.fields`, and it rewrites the key when a span
scope opens, so a value put there directly is blanked out. Going through baggage also
carries the id across a thread hop or a service boundary. When no `Tracer` bean exists —
tracing switched off — the filter falls back to a plain MDC write with a `finally`
removal, because servlet threads are pooled and a leftover value would stamp the next
unrelated request.

The practical difference: a correlation id outlives the request. It is written into
audit rows and outbox metadata, so it links an HTTP call to the events it produced
downstream. A trace id does not appear in those tables.

One caveat: `management.tracing.baggage.correlation.fields` declares both
`correlationId` and `requestId`, but `CorrelationIdFilter` sets only `correlationId`.
Nothing in `src/main/java` calls `MDC.put` for `requestId`, so that field will be absent
from log lines unless something further in the request populates it. `requestId` does
appear as a column on audit rows, and on outbox rows as a field inside the serialised
event envelope in `event_data`, set per command rather than per request.

### What to grep during an incident

Start from whichever id the reporter has. If they have neither, start from the account
or contract identifier and work back through `audit_events`.

```bash
# A customer quoted the X-Correlation-Id from their response.
kubectl -n corebank logs -l app.kubernetes.io/name=corebank-api --tail=-1 \
  | grep '"correlationId":"<id>"'

# Everything in one trace, across replicas.
kubectl -n corebank logs -l app.kubernetes.io/name=corebank-api --tail=-1 \
  | grep '"traceId":"<id>"'

# Errors only, on one money route.
kubectl -n corebank logs -l app.kubernetes.io/name=corebank-api --tail=-1 \
  | grep '"log.level":"ERROR"'

# The idempotency lease takeover — logged as a warning when a stale claim is taken over.
kubectl -n corebank logs -l app.kubernetes.io/name=corebank-api --tail=-1 \
  | grep -i 'takeover'

# Transaction poisoned by a caught unique violation. Should not occur; see
# "Concurrent duplicates of the same command".
kubectl -n corebank logs -l app.kubernetes.io/name=corebank-api --tail=-1 | grep '25P02'
```

Then cross into the database, which is where the id is durable rather than retained for
as long as the log backend keeps it:

```sql
SELECT actor, action, resource_type, resource_id, correlation_id, request_id, created_at
FROM audit_events
WHERE correlation_id = '<correlation-id>'
ORDER BY created_at;
```

Log shipping is deliberately not part of this repository. Logs go to stdout in ECS JSON,
which is where a container runtime expects them; collecting them is the platform's job,
and a sidecar here would duplicate what Fluent Bit, Promtail or a managed agent already
does. The consequence for an operator is that the `kubectl logs` commands above reach
only what is still in the node's log files.

## Operating thresholds and capacity

Every number this system is configured against, in one place, with its source file and
whether it was measured or assumed. They are currently spread across
`application.yml`, `deploy/kubernetes/deployment.yaml`, `deploy/kubernetes/hpa.yaml`,
`deploy/kubernetes/configmap.yaml`, `deploy/observability/alerts.yml` and
`deploy/openshift/route.yaml`.

**Measured** means a number derived from an induced failure with a recorded outcome.
**Assumed** means a considered default that no measurement in this repository supports.

| Setting | Value | Source | Basis |
|---|---|---|---|
| `spring.datasource.hikari.maximum-pool-size` | `20` | `application.yml`, `COREBANK_DB_POOL_SIZE` | **measured** |
| Pod CPU / memory request | `250m` / `512Mi` | `deployment.yaml` | **assumed** |
| Pod CPU / memory limit | `1` / `1Gi` | `deployment.yaml` | **assumed** |
| Aggregate at 3 replicas | `750m` / `1.5Gi` requested, `3` CPU / `3Gi` capped | arithmetic over the above | **assumed** |
| Replicas | `3` | `deployment.yaml` | **assumed** |
| HPA range and target | `3`→`6` at `70%` CPU | `hpa.yaml` | **assumed** |
| HPA scale-down stabilisation | `300s` | `hpa.yaml` | **assumed** |
| PDB `minAvailable` | `2` | `pdb.yaml` | **assumed** |
| Startup probe | `/actuator/health/readiness`, period `5s`, failures `60` → 5 min budget | `deployment.yaml` | **assumed** (sized for Flyway under advisory lock) |
| Readiness probe | period `5s`, timeout `3s`, failures `3` | `deployment.yaml` | **assumed** |
| Liveness probe | period `10s`, timeout `3s`, failures `3` | `deployment.yaml` | **assumed** |
| `terminationGracePeriodSeconds` | `45` | `deployment.yaml` | **assumed** |
| `preStop` sleep | `5s` | `deployment.yaml` | **assumed** |
| Graceful shutdown phase | `30s` | `application.yml` | **assumed** |
| `minReadySeconds` | `15` | `deployment.yaml` | **assumed** |
| `revisionHistoryLimit` | `5` | `deployment.yaml` | **assumed** |
| nginx Ingress read/send timeout | `60s` | `ingress.yaml` | **assumed** |
| OpenShift Route timeout | `60s` | `route.yaml` (default is `30s`) | **assumed**, and never applied to a live cluster |
| Metric gauge refresh | `fixedDelay` 15000 ms, `initialDelay` 5000 ms | `BankingMetrics` | **assumed** |
| Prometheus scrape / evaluation interval | `15s` / `15s` | `prometheus.yml` | **assumed** |
| Outbox publish interval / batch | `5000` ms / `10` events | `OutboxEventPublisher` | **assumed** |
| Outbox retries / backoff / reclaim | `3` / `30s` / `300s` | `OutboxEventPublisher` | **assumed** |
| Idempotency claim lease | `PT2M` | `corebank.idempotency.claim-lease` default | **assumed** |
| Idempotency cleanup grace / limits | `24h` / default `1000`, max `10000` | `IdempotencyMaintenanceService` | **assumed** |
| Partition months ahead | default `3`, max `12` | `PartitionMaintenanceService` | **assumed** |
| Trace sample rate | `1.0` | `application.yml` | **assumed**, and wrong for production |
| JVM heap fraction | `70%` in the image, `60%` in Kubernetes | `Dockerfile`, `configmap.yaml` | **assumed** |
| `OutboxBacklogGrowing` | `corebank_outbox_pending > 100` for `10m` | `alerts.yml` | **assumed** |
| `OutboxDeadLetters` | `corebank_outbox_dead_letters > 0` for `5m` | `alerts.yml` | **assumed** |
| `ReconciliationBreaksOpen` | `corebank_reconciliation_open_breaks > 0` for `15m` | `alerts.yml` | **assumed** |
| `IdempotencyKeysStranded` | `corebank_idempotency_stale > 0` for `5m` | `alerts.yml` | **assumed** threshold, **measured** rationale |
| `MoneyEndpointErrorRate` | `5xx` ratio `> 0.05` for `5m` | `alerts.yml` | **assumed** |
| `MoneyEndpointLatency` | p95 `> 2s` for `10m` | `alerts.yml` | **assumed** |

### The pool size arithmetic

This is the one number with a measurement behind it, so the reasoning is worth having in
full.

A money command holds two connections at the same time: the business transaction, and
the `REQUIRES_NEW` transaction that records the idempotency outcome inside it. The pool
must therefore exceed twice the peak number of concurrent money commands per instance,
or the service starves itself — every in-flight command holds one connection and waits
for a second that nobody can release.

```text
pool 20 ÷ 2 connections per command   = ~10 concurrent money commands per instance
3 replicas × 20                       = 60 connections
PostgreSQL default max_connections    = 100
```

60 of 100 is why `20` is a deliberate number rather than a large one. Raising
`COREBANK_DB_POOL_SIZE` without raising `max_connections` on the database moves the
failure rather than removing it.

The real fix is to stop holding two connections per command. Until that refactor, this
bound keeps the failure out of reach rather than removing it, and both `application.yml`
and the Kubernetes ConfigMap say so next to the number.

### Partitioning: the write path does not use the partitioned tables

Verified by reading both migrations and the write path, because it changes what capacity
planning should watch.

`V3__phase1_hardening.sql` creates `ledger_journals_p`, `ledger_postings_p` and
`audit_events_p`, each `PARTITION BY RANGE (created_at)`, with monthly partitions for
`2026_01` and `2026_02` pre-created. `PartitionMaintenanceService` and
`PartitionArchiveReadinessService` both manage exactly those three parents.

The write path does not use them. `LedgerCommandService` issues
`INSERT INTO ledger_journals (...)` and `INSERT INTO ledger_postings (...)`, and
`AuditService` issues `INSERT INTO audit_events (...)` — the unpartitioned tables from
`V2__base_schema.sql`. `BankingMetrics` counts `ledger_journals`, so
`corebank_ledger_journals` reports growth on the unpartitioned table, which is the
correct thing for it to report and also the table no partition job helps.

Operational consequences:

- The partition maintenance job creates partitions on empty tables. Running it is
  harmless and currently pointless.
- The archive-candidate review in
  [27-backup-restore-and-partition-archive-runbook.md](27-backup-restore-and-partition-archive-runbook.md)
  will report candidates from the `_p` tables, which hold no production rows.
- The growth that actually needs a retention strategy — `ledger_journals`,
  `ledger_postings`, `audit_events` — has none. Watch `corebank_ledger_journals` for
  growth and plan for it outside the partition machinery.
- The failure mode "missing partition for event table" in
  [19-runtime-failure-modes.md](19-runtime-failure-modes.md) cannot currently occur,
  because no insert targets a partitioned table.

This is a gap, not a decision. Whether the intent was to cut the write path over to the
`_p` tables in a later migration is not recorded anywhere in the repository, and should
be established before either path is built on.

## Escalation

### Severity ladder

| Severity | Definition | Examples | Response |
|---|---|---|---|
| **SEV1** | money is incorrect, or the money path is unavailable | unbalanced journal, negative balance where domain rules forbid it, every replica failing readiness | immediate; engineering and the accountable finance owner together |
| **SEV2** | money is correct but a control has failed, or an external commitment is at risk | `OutboxDeadLetters`, `ReconciliationBreaksOpen`, `MoneyEndpointErrorRate` | same business day; ops leads, engineering on call |
| **SEV3** | degradation with a known, bounded effect | `MoneyEndpointLatency`, `IdempotencyKeysStranded`, `OutboxBacklogGrowing` on a Kafka-enabled deployment | next business day; ops |
| **SEV4** | a configured expectation is not met, with no customer effect | `OutboxBacklogGrowing` on a deployment with Kafka deliberately disabled | backlog |

Two promotion rules, both derived from what this system has actually done:

- Any suspicion that money is *incorrect* is SEV1 regardless of which alert raised it.
  Every induced failure preserved the money invariant, so a violation would be new
  behaviour and not a known mode.
- A SEV3 that does not clear after its documented action becomes SEV2. The documented
  action failing means the diagnosis was wrong.

### Where severity currently routes: nowhere

Each of the six rules in `alerts.yml` carries a `severity` label — `warning` or
`critical`. Those labels are evaluated by Prometheus and rendered in Grafana, and that
is the whole of their effect.

**Alertmanager is deliberately absent.** `deploy/observability/` runs an OpenTelemetry
Collector, Prometheus, Tempo and Grafana under docker compose, and no Alertmanager.
`prometheus.yml` loads the rule files and defines no `alerting:` block, so a firing
alert notifies nobody. Routing alerts to a human is a deployment concern with no single
right answer — which team, which channel, which paging policy, which escalation
timeout — and the repository declines to pick one.

The honest operational statement: **the severity ladder above is a convention for
humans reading this document, not a mechanism.** Until an Alertmanager exists with a
route per severity, a firing alert is only seen by somebody looking at Grafana. An
operations team adopting this system has to supply that routing before any of the
response procedures in [Alert response](#alert-response) can begin on time rather than
on discovery.

The two gaps in this document that would matter most in a real incident are that one and
the absence of a runtime-mode control in
[Stopping and draining](#stopping-and-draining). Both are gaps, not decisions.

## Operational notes

- Keep this runbook versioned with the code. Update it after any change to an ops
  endpoint, an alert rule, a probe timing or a threshold in
  [Operating thresholds and capacity](#operating-thresholds-and-capacity).
- The alert subsection headings in [Alert response](#alert-response) are load-bearing:
  the `runbook:` annotations in `deploy/observability/alerts.yml` link to them by
  anchor. Renaming a heading breaks the link from the alert an operator is holding.
- Record rehearsals and their outcomes in `PROGRESS.log`, the way the backup and restore
  rehearsals are recorded.
- Corrections this document identified and did not make, each needing a change in
  another file:
  1. `alerts.yml` — the `ReconciliationBreaksOpen` annotation names
     `GET /api/ops/reconciliation/breaks`, which does not exist. The path is
     `GET /api/reporting/reconciliation/breaks`.
  2. `docs/28-demo-script.md` and `docs/29-interview-prep.md` — both say
     `docker compose up -d postgres redis` and `mvn spring-boot:run`. See
     [Starting the service](#starting-the-service) for the resolved procedure.
  3. `docs/27-backup-restore-and-partition-archive-runbook.md` — its post-restore
     Flyway check expects `v24`. The migration set now ends at `V28`.
  4. `DemoSecurityConfig` — the `showcase` gate denies `/api/ops/security/**`, which
     matches no controller, and leaves `/api/ops/customers/**` ungated.
- Open questions this document could not resolve by reading:
  1. What `ReconciliationService` reports when `account_balance_snapshots` is empty,
     given that `SnapshotService` has no caller.
  2. Whether the write path was intended to move to the partitioned `_p` tables, and if
     so, under which migration.
  3. Whether the ECS field set verified from the formatter class matches what this
     application actually emits at runtime, which one log line would settle.
