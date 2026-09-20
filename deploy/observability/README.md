# Observability

The application emits metrics, traces and structured logs. It speaks OpenTelemetry's
wire protocol and nothing else, so the backend is a deployment decision rather than a
code decision: the same build feeds an OpenTelemetry Collector, Prometheus and Tempo
locally, or Dynatrace, Datadog or Splunk in an enterprise, by changing an endpoint.

```
CoreBank ──OTLP──▶ OpenTelemetry Collector ──▶ Tempo        (traces)
    │                        │
    │                        └──────────────▶ Prometheus   (pushed metrics)
    └──/actuator/prometheus ◀─────scrape───── Prometheus   (pulled metrics)
                                     │
                                     └──────▶ Grafana      (dashboard, alerts)
```

Both metric paths are wired on purpose. Scraping is what a Kubernetes cluster does;
pushing over OTLP is what a managed APM expects. Having both in front of you makes the
difference concrete instead of theoretical.

## Run it

```bash
docker compose -f deploy/observability/docker-compose.yml up -d

COREBANK_OTLP_ENABLED=true COREBANK_LOG_FORMAT=ecs ./mvnw spring-boot:run
```

| What | Where |
|---|---|
| Grafana | http://localhost:3000 |
| Prometheus | http://localhost:9091 |
| Collector OTLP | http://localhost:4318 |

The stack is not a dependency. With it down the application still records metrics and
still stamps a trace id on every log line; it just has nowhere to export to. That is
why `COREBANK_OTLP_ENABLED` is separate from `management.tracing.enabled` — turning
export on without a collector produces a stream of connection failures and no
telemetry, which is worse than not exporting at all.

## What is instrumented

**Metrics.** The Spring and JVM defaults (request rate, latency histograms, heap,
connection pool) plus six that describe the system as a bank rather than as a web
server:

| Metric | What a bad value means |
|---|---|
| `corebank_outbox_pending` | Events written inside money transactions but not published. Downstream systems are drifting out of date while the API still returns 200. |
| `corebank_outbox_dead_letters` | Events that exhausted their retries. Each is a money event a downstream system has permanently missed. |
| `corebank_reconciliation_open_breaks` | The ledger and an external statement disagree. |
| `corebank_idempotency_in_flight` | Commands claimed but not yet resolved. Normal and short-lived under load. |
| `corebank_idempotency_stale` | Claims past the takeover lease: commands whose instance stopped mid-flight. This is the one worth alerting on; in-flight on its own is just traffic. |
| `corebank_ledger_journals` | Total journal rows. A size, for growth and retention — not a rate. It is a global `COUNT(*)`, so every replica reports it identically. |
| `corebank_ledger_journals_posted_total` | Journals **committed** by that instance, counted after commit rather than after insert. This is the throughput signal, and the one that is safe to `sum(rate(...))` across replicas. |

These are refreshed on a schedule and served from memory. A gauge that runs SQL per
scrape hands anyone who can reach the metrics endpoint a way to load the database, and
the cost multiplies with every scraper.

**Traces.** Every HTTP request, and — since `datasource-micrometer` was added — every
JDBC connection acquisition and query. Until then this sentence was false: Spring's JDBC
support carries no Observation instrumentation, so a transfer produced one server span
with the entire database portion inside it as unattributed time. That was the opposite of
useful here, because the failure this system actually suffered was connection starvation.
A money command holds two connections at once, so under a retry storm each one holds one
while waiting for a second nobody can release. With `CONNECTION` spans that shows up as
time spent acquiring, which is the difference between "the API is slow", "this query is
slow", and "we ran out of connections".

Bind parameters are deliberately excluded from spans — in this application they are
account identifiers, amounts and customer references.

**Logs.** `COREBANK_LOG_FORMAT=ecs` switches the console to one JSON document per line
in Elastic Common Schema. Trace id, span id and correlation id are fields, so a log
line pivots to its trace and a trace pivots back to its logs.

## Correlation id

Every request gets one, taken from the `X-Correlation-Id` header when the caller
supplies a sane value and generated otherwise, and echoed back on the response so a
caller reporting a problem can quote the exact value to search for.

It is published as tracing baggage rather than written straight to the MDC. Micrometer
owns that MDC key once the field is declared in
`management.tracing.baggage.correlation.fields`, and it rewrites the key when a span
scope opens — a value put there directly gets blanked out. Going through baggage also
carries the id across a thread hop or a service boundary. A log line ends up looking
like this:

```
[corebank-api,3b1dbe91c36d414936cebe3daf444a5d,f7e0920febb58e38,obs-baggage-1]
 application       trace id                         span id          correlation id
```

The correlation id is deliberately separate from the trace id: a trace id identifies a
technical request path, while a correlation id can be pinned to a business operation
and is written into audit and outbox rows.

## The metrics endpoint requires credentials

`/actuator/prometheus` sits behind authentication, and the scrape job carries
credentials rather than the endpoint being opened up. Metrics disclose request paths,
error rates and business volumes; that is not something a banking deployment should
serve anonymously to make scraping simpler.

In Kubernetes there is a second layer: actuator listens on its own container port
(9091) which the Service and Ingress never publish, so it is reachable by the kubelet
and an in-cluster scraper and by nothing else. The application's security rules still
apply on that port, which was verified rather than assumed — the probe paths answer
anonymously there, everything else returns 401.

Only the two probe paths are anonymous, because the kubelet sends no credentials and a
401 on liveness restart-loops every pod.

## Alerts

`alerts.yml` holds six rules, and the list is short on purpose: an alert nobody acts
on trains people to ignore the ones that matter. Each rule carries a runbook
annotation naming the response, and the money rules are scoped to money endpoints,
because an error rate averaged over every route lets a broken transfer path hide behind
healthy dashboard traffic.

## Pointing this at Dynatrace

Uncomment the `otlphttp/dynatrace` exporter in `otel-collector.yaml`, set the endpoint
and API token, and add it to the pipelines. Nothing in the application changes — not a
dependency, not a line of configuration. That is the property OTLP buys, and it is the
reason to instrument with it rather than with a vendor SDK.

## Deliberately not here

- **Log shipping.** Logs go to stdout in ECS JSON, which is where a container runtime
  expects them. Collecting them is the platform's job; a sidecar here would only
  duplicate what Fluent Bit, Promtail or a managed agent already does.
- **Alertmanager.** The rules are defined and evaluated; routing them to a human is a
  deployment concern with no single right answer.
- **Sampling strategy.** The local stack samples everything, which is right for a lab
  and wrong for production. `COREBANK_TRACE_SAMPLE_RATE` is the knob.
