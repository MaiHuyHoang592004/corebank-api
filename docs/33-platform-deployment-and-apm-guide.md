# 33. Platform Deployment and APM Guide

## Purpose

This document describes how CoreBank is intended to move from a locally verified
application into a platform-managed workload with Kubernetes/OpenShift deployment and
vendor APM telemetry.

It is deliberately split into two kinds of statements:

- **repository-verified** — the code, configuration or manifest exists and can be
  inspected in this repository;
- **runtime-verified** — the behaviour has actually been observed on a running system.

A manifest that renders successfully is not the same thing as a deployment that has
run successfully. This guide keeps that boundary explicit.

## Current verification status

| Area | Repository evidence | Current status |
|---|---|---|
| Application container | `Dockerfile`, CI image build and Trivy gate | **Repository-verified** |
| Kubernetes base | `deploy/kubernetes/` | **Runtime-verified on Kind** ([evidence](evidence/kubernetes-runtime-verification.md)); the Ingress object is **not** verified |
| Local observability | `deploy/observability/` | **Configuration present; application instrumentation is repository-verified** |
| OpenShift overlay | `deploy/openshift/` | **Runtime-verified on one Red Hat Developer Sandbox** after two fixes ([evidence](evidence/openshift-runtime-verification.md)) |
| Cluster OTel Collector | `deploy/observability/kubernetes/` | **Runtime-verified** on Kind and on the Sandbox |
| Dynatrace OTLP ingest | Collector exporter + application OTLP configuration | **Verified on Kind on a trial tenant**, including an induced incident ([APM](evidence/dynatrace-apm-verification.md), [RCA](evidence/dynatrace-incident-rca.md)); **and on OpenShift** (attributes, JDBC spans and counts read back; [OpenShift evidence](evidence/openshift-runtime-verification.md)) |
| Dynatrace Kubernetes Operator / DynaKube | Not deployed by this repository | **Not run**: the Sandbox has no `dynatrace.com` CRD and refuses the cluster-scoped creates |
| Service Mesh | `deploy/service-mesh/` (upstream Istio) | **Istio 1.31 verified on Kind**; OpenShift Service Mesh **not run** ([evidence](evidence/service-mesh-verification.md)) |

The runtime failure experiments in
[19-runtime-failure-modes.md](19-runtime-failure-modes.md) are separate from the
platform status above. Those experiments were run against a real PostgreSQL-backed
application and produced measured results; they are not evidence about the platform
rows.

Every platform row above is a **lab observation**: one host or one free shared cluster, a
trial tenant, synthetic traffic at about 10 requests/s. None of it is a production
measurement, none is an SLO, and it is not production experience with OpenShift or
Dynatrace. The end-to-end write-up is
[the platform POC](poc/corebank-dynatrace-platform-poc.md).

---

## Target deployment shape

```text
                           ┌──────────────────────┐
                           │       Client         │
                           └──────────┬───────────┘
                                      │ HTTPS
                       ┌──────────────▼──────────────┐
                       │ Ingress / OpenShift Route  │
                       └──────────────┬──────────────┘
                                      │
                            ┌─────────▼─────────┐
                            │ Kubernetes Service│
                            └─────────┬─────────┘
                                      │
                  ┌───────────────────┼───────────────────┐
                  │                   │                   │
           ┌──────▼──────┐     ┌──────▼──────┐     ┌──────▼──────┐
           │ CoreBank pod│     │ CoreBank pod│     │ CoreBank pod│
           └──────┬──────┘     └──────┬──────┘     └──────┬──────┘
                  │                   │                   │
                  └─────────────┬─────┴─────────────┬─────┘
                                │                   │
                         ┌──────▼──────┐      ┌─────▼─────┐
                         │ PostgreSQL  │      │ Redis/Kafka│
                         │ money truth │      │ non-truth  │
                         └─────────────┘      └───────────┘

CoreBank pods
   │
   ├── OTLP traces
   ├── OTLP metrics
   └── ECS JSON logs on stdout
          │
          ▼
OpenTelemetry Collector
   │
   ├── traces ────────────────┐
   ├── metrics ───────────────┼──► Dynatrace
   └── OTLP logs, if present ─┘

Optional later:
Dynatrace Operator / DynaKube
   └── Kubernetes platform metadata and cluster-local ingest
```

The application remains vendor-neutral. It speaks OTLP; the collector owns the
backend-specific exporter.

---

## 1. Kubernetes baseline

### Why Kubernetes comes before OpenShift

The base manifests should be proved on a plain Kubernetes cluster before debugging
OpenShift-specific behaviour.

If the first deployment attempt is OpenShift and a pod fails to start, the failure may
come from any of these layers:

- the base Kubernetes manifest;
- image pull or image runtime behaviour;
- resource quota;
- OpenShift Security Context Constraints;
- OpenShift Route configuration;
- the database deployment choice.

Running the same base on Kind first removes one variable: once the workload is known to
run on Kubernetes, a later OpenShift failure is evidence about the platform difference
rather than an ambiguous first boot.

### Repository shape

`deploy/kubernetes/` contains:

```text
namespace.yaml
configmap.yaml
secret.example.yaml
postgres.yaml
deployment.yaml
service.yaml
ingress.yaml
hpa.yaml
pdb.yaml
kustomization.yaml
kind-cluster.yaml
```

The Deployment carries:

- three replicas;
- startup, readiness and liveness probes;
- resource requests and limits;
- graceful shutdown;
- rolling-update constraints;
- a disruption budget;
- pod anti-affinity;
- a non-root security posture.

The management port is separated from the public application port. Kubernetes probes
can reach actuator on the pod while the public Service and Ingress expose only the
application port.

### Bring up Kind

```bash
kind create cluster --name corebank --config deploy/kubernetes/kind-cluster.yaml

cp deploy/kubernetes/secret.example.yaml deploy/kubernetes/secret.yaml
# edit deploy/kubernetes/secret.yaml before applying it

kubectl apply -f deploy/kubernetes/namespace.yaml
kubectl apply -f deploy/kubernetes/secret.yaml
kubectl apply -k deploy/kubernetes

kubectl -n corebank rollout status deployment/corebank-api --timeout=300s
kubectl -n corebank get pods -o wide
```

Apply the kustomize root rather than `deployment.yaml` directly. The base Deployment
contains a placeholder image tag; the kustomization is the deployment unit that pins
the intended image.

### Verification gates

A Kind deployment is considered runtime-verified only after all of these have been
observed:

1. all application replicas become `Ready`;
2. the readiness endpoint leaves a pod out of traffic when the database is unavailable;
3. deleting one pod causes the ReplicaSet to restore the desired replica count;
4. a healthy rolling update completes without dropping available capacity;
5. an image that cannot become Ready does not replace the healthy replicas;
6. `kubectl rollout undo` returns the Deployment to the prior application revision;
7. HPA behaviour is observed after metrics-server is installed.

These gates prove platform behaviour. They do not replace the financial failure tests in
[19-runtime-failure-modes.md](19-runtime-failure-modes.md).

**Result.** All seven gates were observed on Kind on 2026-09-21
([evidence](evidence/kubernetes-runtime-verification.md)), with these findings: readiness
fails by timeout when the database is unavailable, so requests in the detection window wait
about 30 s for a `500` (gate 2); every pod restarts once on a cold start (gate 1); the HPA
scales out under routine traffic and during rollouts (gate 7); and the PostgreSQL connection
limit was below what six replicas hold, which is fixed.

### Rollback boundary

Application rollback and database rollback are different operations.

Flyway migrations in this repository are forward-only. If a release changes the schema,
`kubectl rollout undo` can restore the old image while leaving the newer schema in
place. A safe release therefore requires backward-compatible migration sequencing, not
the assumption that Kubernetes can undo the database.

The operational procedure is documented in
[31-operations-runbook.md](31-operations-runbook.md).

---

## 2. OpenShift deployment

### What changes from the Kubernetes base

`deploy/openshift/` is a kustomize overlay rather than a second application
definition.

It changes the platform-specific parts:

- removes the Kubernetes Ingress;
- creates an OpenShift `Route`;
- changes the environment identity to `openshift`;
- points the application at an external/catalog-provided PostgreSQL Service instead of
  the base in-cluster PostgreSQL manifest;
- retains the application Deployment and Service where plain Kubernetes semantics are
  sufficient.

This keeps one application deployment model and isolates platform differences in the
overlay.

### Security Context Constraints

OpenShift's default `restricted-v2` SCC uses a `MustRunAsRange` strategy and can
assign a project-specific UID at admission time. For that reason a portable application
image should not require a fixed numeric `runAsUser`.

CoreBank's application Deployment requests non-root execution without pinning a UID.
That is intentional.

Before blaming an SCC, inspect the actual admission result:

```bash
oc get pod -n <project> -o yaml
oc describe pod -n <project> <pod>
oc auth can-i use scc/restricted-v2
```

Do not weaken the workload to `anyuid` simply to make an image boot. If an image cannot
run under an assigned UID, fix the image or choose an OpenShift-compatible image.

### Route

The overlay provides an edge-terminated HTTPS Route with insecure traffic redirected to
HTTPS.

Verify the admitted route instead of assuming a hostname:

```bash
oc get route
oc get route corebank-api -o yaml
```

The Route is the public application surface. Actuator remains on the separate management
port and is not intentionally exposed through the Route.

### Resource and quota checks

OpenShift quotas vary by cluster and tenancy. The repository does not assume a fixed
Developer Sandbox quota.

Inspect first:

```bash
oc describe resourcequota
oc describe limitrange
oc get deployment,hpa,pdb
```

If the environment cannot support three application replicas, HPA, Deployment and PDB
must be adjusted as one coherent capacity decision. Changing only the Deployment replica
count is insufficient because the HPA can immediately scale it back up and a PDB can
make voluntary disruption impossible at the reduced size.

### OpenShift verification gates

OpenShift is considered runtime-verified only when:

1. the application pods are admitted under the cluster's normal security policy;
2. every pod reaches `Ready`;
3. the Route serves the application over HTTPS;
4. the database connection works without weakening the pod security model;
5. quota-related changes are recorded explicitly rather than hidden in manual cluster
   state;
6. rollout and rollback behaviour is rechecked on OpenShift;
7. the final manifests used for the environment are reproducible from versioned files.

**Result.** Gates 1 to 4 and 6 were observed on a Red Hat Developer Sandbox (OpenShift
4.21) on 2026-09-21 ([evidence](evidence/openshift-runtime-verification.md)). Gate 5 held:
the peak `requests.cpu` was 1560m of a 3 CPU quota and nothing had to be changed. Gate 7 is
**partly** met: the two fixes are in versioned files, but the project name is substituted at
render time and the database is provisioned by commands, not by a manifest. The overlay as
first committed was refused on the Sandbox (the Namespace object and the hard-coded
namespace), and the catalog database's 100-connection limit left the sixth HPA replica
crash-looping; both are fixed. The honest status is **runtime-verified on one project-scoped
Sandbox**, not on a cluster where operators and policies differ.

---

## 3. Application observability

### Signals produced by CoreBank

The application is instrumented with Micrometer and OpenTelemetry.

Current repository configuration provides:

- HTTP server observations;
- JDBC `CONNECTION` spans;
- JDBC `QUERY` spans;
- `service.version` as an OpenTelemetry resource attribute;
- `deployment.environment`;
- `service.namespace=corebank`;
- application correlation IDs propagated into logs;
- banking-specific metrics for outbox, reconciliation, idempotency and ledger activity;
- Prometheus histogram buckets for HTTP request latency.

JDBC bind parameter capture is deliberately disabled. Account IDs, customer references
and monetary values should not be copied into an APM backend just to make traces more
convenient.

### Release identity

`service.version` is derived from the application build version.

The purpose is operational rather than decorative: an APM investigation should be able
to answer whether a latency or error regression began with a specific release.

Do not add the release version as a common Prometheus label on every metric. Doing so
would create a new time series for every label combination on every deployment and
break continuity across rollouts. Release identity belongs on the telemetry resource.

---

## 4. OpenTelemetry Collector

### Local collector

`deploy/observability/` provides a local stack:

```text
CoreBank
   ├── OTLP traces ──► Collector ──► Tempo
   └── OTLP metrics ─► Collector ──► Prometheus
                                      │
                                      └──► Grafana
```

This stack is for local inspection and does not define the production backend.

### Cluster collector

`deploy/observability/kubernetes/` defines a stateless collector forwarder:

```text
CoreBank pods
     │ OTLP/HTTP
     ▼
otel-collector.corebank:4318
     │
     ▼
Dynatrace OTLP API
```

The collector has:

- OTLP HTTP and gRPC receivers;
- memory limiting before batching;
- batch processing;
- an OTLP/HTTP Dynatrace exporter;
- retry on export failure;
- a health endpoint for the Collector process.

The retry queue is in memory. A Collector restart can therefore lose queued telemetry.
That is acceptable here because telemetry is diagnostic data, not financial truth.

### Logs are different from traces and metrics

The cluster Collector contains a logs pipeline so an OTLP log payload has a valid path.
The current application configuration, however, explicitly exports traces and metrics
over OTLP and writes ECS JSON logs to stdout.

Therefore:

- **traces:** OTLP path exists;
- **metrics:** OTLP path exists;
- **logs:** structured stdout exists; application OTLP log export is not currently
  configured.

Do not claim that application logs have been observed in Dynatrace through OTLP until a
log collection path is actually configured and verified.

---

## 5. Dynatrace OTLP integration

### Current integration boundary

The repository uses the standard Dynatrace OTLP endpoint through an OpenTelemetry
Collector. No Dynatrace SDK is required in application code.

For a Dynatrace SaaS environment the Collector exporter uses the environment OTLP base
endpoint:

```text
https://<environment-id>.live.dynatrace.com/api/v2/otlp
```

The OTLP exporter appends the signal path such as `/v1/traces` or `/v1/metrics`.

The Collector sends:

```http
Authorization: Api-Token <token>
```

The token is injected from a Kubernetes Secret and must never be committed.

### Token scopes

For a classic Dynatrace access token:

- traces require `openTelemetryTrace.ingest`;
- metrics require `metrics.ingest`;
- OTLP logs require `logs.ingest`.

The current cluster Collector defines all three pipelines. If all pipelines remain
enabled, provision all three ingest scopes. If logs are deliberately excluded, the token
does not need a log-ingest scope for the traces/metrics-only path.

Use an ingest-only token. Do not reuse a token with configuration or read permissions.

### Metric temporality

Dynatrace maps OTLP Counter metrics when they use **delta** temporality; cumulative
Counter input is not mapped as a Dynatrace Counter.

CoreBank therefore configures:

```yaml
management:
  otlp:
    metrics:
      export:
        aggregation-temporality: delta
```

This decision belongs in the application exporter because the process knows its own
counter baseline. Converting cumulative values in the Collector would make the Collector
stateful per time series.

### Enable the cluster telemetry path

Apply the collector secret separately:

```bash
cp deploy/observability/kubernetes/secret.example.yaml \
   deploy/observability/kubernetes/secret.yaml

# set DYNATRACE_API_TOKEN in the local copy
kubectl apply -f deploy/observability/kubernetes/secret.yaml
```

Set the Dynatrace base endpoint in the collector kustomization, then deploy:

```bash
kubectl apply -k deploy/observability/kubernetes
kubectl -n corebank rollout status deployment/otel-collector
```

Point the application at the in-cluster collector:

```text
COREBANK_OTLP_ENABLED=true
COREBANK_OTLP_TRACES_ENDPOINT=http://otel-collector.corebank:4318/v1/traces
COREBANK_OTLP_METRICS_ENDPOINT=http://otel-collector.corebank:4318/v1/metrics
```

The same shape applies on OpenShift with `oc`.

### Dynatrace verification gates

The Dynatrace integration is considered runtime-verified only when a real tenant shows:

1. service `corebank-api` receiving telemetry;
2. `service.version` matching the deployed build;
3. an HTTP transfer span;
4. nested JDBC `CONNECTION` and `QUERY` spans;
5. banking metrics arriving with the intended environment/resource identity;
6. a deliberately induced database/connection-pool degradation visible in the trace;
7. the observed root cause matching application/database evidence rather than an
   unsupported guess.

A screenshot alone is weak evidence. The stronger evidence is a reproducible incident
where the trace changes in the way the injected failure predicts.

**Result.** Gates 1 to 7 were observed on Kind against a trial tenant
([APM](evidence/dynatrace-apm-verification.md),
[RCA](evidence/dynatrace-incident-rca.md)). The induced fault was a 45 s row lock, not
pool exhaustion: the trace showed one 45.02 s `QUERY` span with `acquired` at the start of
the `CONNECTION` span, which is what separated lock contention from pool wait and slow SQL.
On OpenShift (a Developer Sandbox project) gates 1 to 5 were also observed: 3,899 transfer
spans with `deployment.environment=openshift` and the commit SHA, JDBC `connection` and
`query` spans, and `corebank.ledger.journals.posted` = 3,899, the number of journals the
database committed in that window; gates 6 and 7 were not repeated there. Not observed
anywhere: OTLP logs, Davis problems, alerting.

---

## 6. Dynatrace on Kubernetes

Direct OTLP ingest proves application APM. It does **not** by itself prove Kubernetes
platform monitoring.

Dynatrace's Kubernetes integration can additionally use the Dynatrace Operator and a
`DynaKube` custom resource. Current Dynatrace versions can expose cluster-local
telemetry ingest endpoints and enrich telemetry with Kubernetes context.

That is a separate integration from the Collector configuration already in this
repository.

### Current repository status

CoreBank does **not** currently include:

- Dynatrace Operator installation manifests;
- a `DynaKube` resource;
- ActiveGate configuration;
- OneAgent configuration.

Do not imply otherwise.

### Why it is separate

Operator installation and node-level/platform monitoring can require permissions that an
application namespace owner does not have.

The correct workflow is:

```text
application OTLP to Dynatrace
        │
        ├── can be validated with application-level permissions
        │
        ▼
Dynatrace Kubernetes onboarding
        │
        └── depends on cluster policy / operator permissions
```

If a restricted OpenShift environment refuses the Operator or required cluster-scoped
resources, record the exact RBAC denial. Do not weaken cluster security simply to make a
demo green.

**Observed on the Developer Sandbox.** The Dynatrace Operator (`dynatrace-operator`, certified
catalogue, `v1.10.2`) declares only the `AllNamespaces` install mode. The cluster serves no
`dynatrace.com` API group, so a `DynaKube` is refused with `no matches for kind "DynaKube"`,
and `oc auth can-i` answers *no* to creating CRDs, cluster roles and SCCs. DynaKube was
therefore **not run**, and nothing was worked around.

---

## 7. Service Mesh

### Current status

There is no Service Mesh in the base deployment. `deploy/service-mesh/` holds an overlay
for **upstream Istio** (two versions of the same application, weighted routing, STRICT
mutual TLS, a rollback file) that was verified on Kind with Istio 1.31.0
([evidence](evidence/service-mesh-verification.md)). **Red Hat OpenShift Service Mesh was
not run**: both its operators declare only the `AllNamespaces` install mode and the
Developer Sandbox refuses a Subscription in `openshift-operators`, an OperatorGroup and
the cluster-scoped creates. Only "Istio runtime verified on Kind" can be said.

That is intentional. The application is a modular monolith and does not need to be
split into artificial microservices merely to justify a mesh.

### OpenShift Service Mesh relationship

Red Hat OpenShift Service Mesh 3 is based on a Red Hat distribution of Istio and uses an
Operator based on the Sail Operator project.

The platform exposes Istio networking/security resources such as:

- `VirtualService`;
- `DestinationRule`;
- `AuthorizationPolicy`.

OpenShift Service Mesh has its own supported installation and lifecycle model. It should
not be described as simply "upstream Istio plus vendor support".

### Minimal validation target

If Service Mesh is added, the first useful experiment is progressive delivery, not a
microservice rewrite.

Use two application revisions:

```text
Route / Gateway
      │
      ▼
CoreBank Service
      │
      ├── 90% ──► revision A
      └── 10% ──► revision B
```

Then verify:

1. both versions are healthy before traffic splitting;
2. traffic distribution matches the configured weights over a meaningful request
   sample;
3. mTLS policy does not break database or telemetry paths;
4. rollback means restoring traffic to the known-good revision;
5. telemetry includes enough release identity to compare the revisions.

If the OpenShift environment does not permit Service Mesh Operator installation, the
traffic-management concept may be validated with upstream Istio on Kind. That proves
Istio behaviour, **not** OpenShift Service Mesh runtime experience. The distinction
should remain explicit.

**Result of that experiment**, against the five checks above:

1. Both versions were healthy before traffic was split: **met**.
2. Distribution matched the weights: **partly**. 50/50 gave 49.8 %; 90/10 delivered 8.7 % to
   v2 over 4,936 requests, a consistent shortfall that is not explained.
3. mTLS did not break the database or telemetry paths: **not established by design**. The
   database was left outside the mesh (its hop is unencrypted) and mesh telemetry was not
   sent anywhere.
4. Rollback restored traffic to the known-good revision: **met**; 1,064 requests after the
   change went to v1, none to v2, none failed.
5. Telemetry carried enough release identity to compare the revisions: **partly**. The
   sidecars' `destination_version` and the application's own counters separated them;
   Dynatrace was not part of that run.

---

## 8. End-to-end APM incident

The highest-value platform demonstration is one incident that crosses the entire stack.

### Baseline

Deploy a known release and generate a stable transfer workload.

Observe:

- request rate;
- p95 latency;
- error rate;
- JDBC connection acquisition;
- JDBC query time;
- `corebank_ledger_journals_posted_total`;
- reconciliation/idempotency safety signals.

### Inject one controlled failure

A useful example is database connection pressure because this repository has already
measured connection starvation as a real application failure mode.

The experiment should be bounded and reversible.

### Diagnose through telemetry

Expected investigation path:

```text
money endpoint latency rises
        │
        ▼
HTTP server trace
        │
        ▼
JDBC CONNECTION span expands
        │
        ├── query time normal      → connection acquisition/pool pressure
        │
        └── query time also high   → investigate database work/locking
```

The trace is evidence only when it agrees with database/application observations.

### Verify financial safety after recovery

After removing the injected pressure:

- request latency returns toward baseline;
- the deployed version is known;
- no unbalanced journal exists;
- no duplicate journal was created for a retried command;
- no unexpected reconciliation break remains;
- stale idempotency claims are understood and recoverable.

The objective is not merely to show a red dashboard turning green. It is to show that
availability degraded while financial correctness remained explainable and verifiable.

---

## 9. Evidence record

For every platform verification, record:

```text
Date:
Environment:
Application image / service.version:
Platform version:
Change applied:
Expected result:
Observed result:
Commands / queries used:
Failure, if any:
Root cause:
Remediation:
Remaining limitation:
```

The record should contain facts that can be reproduced. Avoid statements such as
"production-ready", "OpenShift-tested" or "Dynatrace-integrated" until the corresponding
runtime gate above has actually passed.

Records made so far, all lab observations:

- [Kubernetes runtime verification](evidence/kubernetes-runtime-verification.md)
- [Dynatrace APM verification](evidence/dynatrace-apm-verification.md)
- [Dynatrace incident RCA](evidence/dynatrace-incident-rca.md)
- [OpenShift runtime verification](evidence/openshift-runtime-verification.md)
- [Service mesh verification](evidence/service-mesh-verification.md)
- [Platform POC](poc/corebank-dynatrace-platform-poc.md)

---

## 10. Repository status labels

Use these terms consistently in documentation:

| Label | Meaning |
|---|---|
| **Implemented** | Code/configuration exists in the repository. |
| **CI-validated** | Static rendering/schema/tool validation passed in CI. |
| **Runtime-verified** | Behaviour was observed on a running environment. |
| **Measured** | A numerical result came from an executed experiment. |
| **Proposed** | A target or design has not yet been validated by runtime evidence. |
| **Not implemented** | No repository implementation exists yet. |

This vocabulary prevents configuration from being mistaken for operational experience.

---

## 11. Security rules for the platform path

- Never commit a Dynatrace token or generated Kubernetes Secret.
- Kubernetes Secret `data` is encoding, not encryption.
- Use least-privilege ingest tokens for telemetry.
- Do not capture JDBC bind values in traces.
- Do not expose the Collector OTLP receiver through a public Route/Ingress.
- Do not expose actuator management endpoints through the public application Route.
- Do not weaken OpenShift SCCs just to make an incompatible image start.
- Do not treat telemetry availability as a prerequisite for money correctness.
- Do not put financial repair procedures behind ad-hoc SQL updates; use supported
  application/operations paths.

---

## 12. External platform references

The repository remains the source of truth for CoreBank-specific configuration. Product
behaviour that belongs to Dynatrace or Red Hat should be checked against vendor
documentation before changing the integration.

- Dynatrace — Kubernetes telemetry ingest endpoints:  
  https://docs.dynatrace.com/docs/ingest-from/setup-on-k8s/extend-observability-k8s/telemetry-ingest
- Dynatrace — OTLP metrics ingest and temporality:  
  https://docs.dynatrace.com/docs/ingest-from/opentelemetry/otlp-api/ingest-otlp-metrics/about-metrics-ingest
- Dynatrace — Kubernetes data with the OpenTelemetry Collector:  
  https://docs.dynatrace.com/docs/ingest-from/opentelemetry/collector/use-cases/kubernetes
- Red Hat — OpenShift Security Context Constraints:  
  https://docs.redhat.com/en/documentation/openshift_container_platform/4.22/html/authentication_and_authorization/managing-pod-security-policies
- Red Hat — OpenShift Service Mesh 3 installation:  
  https://docs.redhat.com/en/documentation/red_hat_openshift_service_mesh/3.0/html/installing/ossm-installing-service-mesh
- Red Hat — OpenShift Service Mesh 3 release notes:  
  https://docs.redhat.com/en/documentation/red_hat_openshift_service_mesh/3.0/html/release_notes/ossm-release-notes
