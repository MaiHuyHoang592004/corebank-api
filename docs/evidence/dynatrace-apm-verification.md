# Dynatrace APM Verification

CoreBank running on Kind was connected to a Dynatrace trial tenant over OTLP, and the result was read back
in the Dynatrace UI. Times are UTC unless a UI screenshot time is quoted (the UI showed UTC+7). The tenant
identifier, the account e-mail and the ingest token are deliberately not recorded here.

```
CoreBank pods (3-6) --OTLP/HTTP 4318--> otel-collector (1 replica) --OTLP/HTTP + Api-Token--> Dynatrace
```

This is a **lab result on a trial tenant**. It shows that the committed collector configuration works and that
the telemetry the documentation promises actually arrives. It is not a production APM deployment.

## Summary

| Claim | Status | Basis |
|---|---|---|
| Collector starts with the committed manifests and config | **verified** | pod `1/1 Ready` in 9.8 s, 0 restarts, `health_check` 200 |
| Traces reach Dynatrace | **verified** | 600 requests, then 1000+, visible in Distributed Tracing |
| Service `corebank-api` with `service.namespace=corebank`, `service.version` = commit SHA | **verified** | attribute panel, value equals `2746b2b7deef531b1a3c4f238d5f310e0e2910c3` |
| HTTP server trace | **verified** | `server` span `http post /api/transfers/internal` |
| JDBC `CONNECTION` span with acquisition and commit events | **verified** | `client` span `connection`, span events `acquired` and `commit` |
| JDBC `QUERY` span | **verified** | `client` span `query`, SQL text, `HikariPool-1`, `org.postgresql.Driver` |
| Banking metrics | **verified (series exist, one value dropped)** | 10 `corebank.*` series; see the NaN finding |
| `corebank.ledger.journals.posted` is correct | **verified** | the 1-minute buckets sum to 2,700, the number of journals those runs committed |
| Hikari metrics | **series exist; values not read in Dynatrace** | `hikaricp.connections.*` listed, values corroborated from the pods instead |
| OTLP **logs** | **not observed** | trace "Logs" tab: `0 records`; no log payload seen at the collector |
| Alerting / Davis problems / Smartscape views | **not tested** | |

## Environment

| | |
|---|---|
| Application image | `sha-2746b2b7deef…` (`sha256:30fea6fa…`), see finding 1 |
| Collector image | `otel/opentelemetry-collector-contrib:0.115.1`, `sha256:d2da12c4336a…`, user `uid 10001` |
| Kubernetes | Kind v0.33.0, Kubernetes v1.36.4 (see `kubernetes-runtime-verification.md`) |
| Backend | Dynatrace trial tenant, OTLP endpoint `https://<environment-id>.live.dynatrace.com/api/v2/otlp` |
| How it was applied | the tracked manifests were rendered and only the tenant endpoint and the OTLP switch were substituted in the render; the tracked `kustomization.yaml` and `configmap.yaml` were **not** edited |

The application-side cutover is the three values `deploy/observability/kubernetes/README.md` describes
(`COREBANK_OTLP_ENABLED=true` plus the traces and metrics endpoints at `otel-collector.corebank:4318`), applied
in the rendered ConfigMap together with the image change, in one rollout (121 s).

## Credential

- One access token was created for this test with **only** `openTelemetryTrace.ingest` and `metrics.ingest`
  (UI names "Ingest OpenTelemetry traces" and "Ingest metrics"), expiring seven days out. `logs.ingest` was not
  granted, because the application does not emit OTLP logs.
- The scope names on the tenant match the README's, which had marked them unverified.
- The token lives only in the git-ignored `deploy/observability/kubernetes/secret.yaml`, applied as a Kubernetes
  Secret. It was copied from the UI through the clipboard straight into that file and never printed; the
  rendered kustomize output contains no `Secret`.
- It should be revoked when the lab is torn down.

## Collector: what the README left open

| README open question | Observed |
|---|---|
| `runAsNonRoot` with this image | satisfied: the image runs as `uid 10001` |
| `readOnlyRootFilesystem` | works (with the `/tmp` `emptyDir`); ran for the whole session |
| `health_check` serves `/` | yes: `200 {"status":"Server available", …}` |
| Does the app emit OTLP logs | **not observed**: only `traces` and `metrics` payloads reached the collector; the trace "Logs" tab is empty |
| Token scope names | confirmed against the tenant (above) |

At level `warn` the only startup lines are the three expected `0.0.0.0` bind warnings.

## What Dynatrace shows for one transfer

Distributed Tracing → Explorer, service `corebank-api`, endpoint `http post /api/transfers/internal`:

```
server   http post /api/transfers/internal            80.62 ms
  internal  security filterchain before (authenticate usernamepassword, authorize request)
  internal  secured request
    client   connection   14.54 ms   events: acquired @ span start, commit @ span end
    client   connection   (second connection: the idempotency claim)
    client   query x N    e.g. 675.66 µs   SELECT idempotency_key, request_hash, status, response_…
```

- Resource attributes on the spans: `service.name=corebank-api`, `service.namespace=corebank`,
  `service.version=2746b2b7deef531b1a3c4f238d5f310e0e2910c3`, `deployment.environment=kubernetes`,
  telemetry SDK `opentelemetry` / `java` / `1.55.0`.
- `query` spans carry the SQL statement, `jdbc.datasource.pool = HikariPool-1`, driver `org.postgresql.Driver` and
  datasource name `corebank`.
- The trace's Logs tab shows `0 records`, so no log is correlated to the trace.

**The `CONNECTION` span, read correctly.** Its `acquired` event sits at the *start* of the span and its `commit`
event at the *end*, so the span covers acquisition through commit and close. Its total duration is therefore not
connection-pool wait; pool wait is the position of `acquired` relative to the span start. The lock-contention
incident (`dynatrace-incident-rca.md`) shows why this matters: a 45 s `CONNECTION` span there contains a 45 s
blocked `QUERY`, with `acquired` at the very beginning.

## Metrics

A DQL `metrics` query listed 20 series matching `corebank*` or `hikari*`:

- `corebank.ledger.journals.posted` (the throughput counter), `corebank.ledger.journals`,
  `corebank.idempotency.in_flight`, `corebank.idempotency.stale`, `corebank.outbox.pending`,
  `corebank.outbox.dead_letters`, `corebank.reconciliation.open_breaks`, `corebank.read_model.feed.count`,
  `corebank.read_model.outbox.pending.count`, `corebank.read_model.summary.count`;
- `hikaricp.connections`, `.acquire`, `.active`, `.creation`, `.idle`, `.max`, `.min` and further pool series.

`corebank.ledger.journals.posted` was queried at its finest resolution, one minute, over the incident run:
`[175, 494, 489, 685, 488, 369]` then zeros. Those sum to **2,700 = 700 warm-up + 2,000 test transfers**, exactly
the journals those runs committed, so the counter is accurate. Its per-minute alignment with the wall clock is
approximate (the application exports on a 60 s step and Dynatrace stored it at one-minute resolution), so it is
not fine enough to locate a 45 s stall; the incident is read from traces and from the cluster-side recording.

## Findings

1. **The image pinned in `kustomization.yaml` cannot produce JDBC spans.** `sha-a500aac…` predates the
   `datasource-micrometer` dependency and the `jdbc:` observation block (commit `ee67612`). The APM claims were
   verified on `sha-2746b2b…`. Bumping the pin is a release decision and was not made here.
2. **Dynatrace drops `corebank.read_model.projection.lag.seconds`.** The collector logged
   `Partial success … Metric value dropped. key: 'corebank.read_model.projection.lag.seconds' … 'Value was NaN' …
   Reason: VALUE_INVALID` roughly every 20 s. The gauge is `NaN` while Kafka and the read-model projection are
   off. Everything else was accepted. The series is absent in Dynatrace.
3. **A wrong token is loud in the collector log and silent everywhere else.** With a placeholder token both
   pipelines returned `HTTP 401 Token Authentication failed`, which the exporter treats as a *permanent* error:
   `Exporting failed. Dropping data.`, with no retry and no queue. Traffic was unaffected. Nothing alerts on it
   (README "not done yet" item 3), which is now an observed failure mode rather than a predicted one.
4. **`/actuator/prometheus` returning 401 does not affect this path.** Metrics reach Dynatrace by OTLP push, so
   nothing scrapes that endpoint. The Deployment's `prometheus.io/scrape` annotation still advertises a path that
   requires credentials; that is a documentation inconsistency, not an APM defect.
5. **Metric resolution is one minute** in this tenant, even when `interval:10s` is requested.

## Not verified, and limits

- OTLP logs: none arrived. This is "not observed in this run", not proof the application cannot emit them.
- Hikari values in Dynatrace: the series exist, but their values were not read there. The incident RCA corroborates
  Hikari from the pods' own management endpoints instead.
- Service-level views, Davis problems, alerting and Smartscape were not exercised.
- Read-back was done by looking at the UI, and the tab was intermittently hidden by the operating system, which
  paused rendering. Values quoted above were read from screenshots, not exported.
- One notebook, "Untitled notebook", was created in the tenant to run the DQL queries.
- The collector runs a single replica with an in-memory queue and no NetworkPolicy, as the README says.
