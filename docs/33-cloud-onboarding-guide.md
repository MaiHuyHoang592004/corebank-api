# 33. Cloud Onboarding Guide

Everything in this repository that could be written without an account has been written.
The manifests exist, the OpenShift overlay exists, an OpenTelemetry Collector for a
cluster exists, the application emits OTLP traces, metrics and ECS logs, and CI renders
and schema-validates all three kustomize roots on every push. None of it has run on a
cluster, and no telemetry has ever reached a vendor backend.

That is the boundary this document sits on. The remaining work needs a Kind cluster on
someone's machine, a Red Hat Developer Sandbox account, and a Dynatrace trial tenant —
three things a repository cannot provision for itself. This guide is for the one person
who has to do those parts.

It is written so that the outcome is evidence either way. A step that succeeds produces a
screenshot, a trace, or a command output worth keeping. A step that is refused produces
an error message, a named permission and a reason, which is also worth keeping — the
Sandbox is expected to refuse at least one of these, and a documented refusal is a better
artefact than a vague "didn't get to it". What is not acceptable is a step whose outcome
nobody wrote down.

## What is already done, and what is left to you

Done, and verifiable by reading the repository:

- `deploy/kubernetes/` — `kind-cluster.yaml`, `namespace.yaml`, `configmap.yaml`,
  `secret.example.yaml`, `postgres.yaml`, `deployment.yaml`, `service.yaml`,
  `ingress.yaml`, `hpa.yaml`, `pdb.yaml`, `kustomization.yaml`.
- `deploy/openshift/` — a kustomize overlay on the base that deletes the in-cluster
  PostgreSQL, repoints `SPRING_DATASOURCE_URL`, replaces the `Ingress` with a `Route`,
  and vendors the `Route` schema under `deploy/openshift/schemas/` so `kubeconform` can
  validate it in CI.
- `deploy/observability/` — the local docker compose stack (collector, Prometheus, Tempo,
  Grafana), `alerts.yml` and `recording-rules.yml`.
- `deploy/observability/kubernetes/` — `otel-collector-config.yaml`, `deployment.yaml`,
  `service.yaml`, `secret.example.yaml` and `kustomization.yaml`: a collector for a
  cluster whose single exporter is `otlphttp/dynatrace`. Rendered and schema-validated,
  never applied.
- The application: OTLP traces including JDBC `CONNECTION` and `QUERY` spans, seven
  `corebank_*` series, ECS JSON logs carrying `traceId`, `spanId` and `correlationId`,
  and a `service.version` resource attribute wired from the image build argument.

Left to you, because each one needs an account or a running cluster:

| Step | What it needs | Expected outcome |
|---|---|---|
| 1 | Docker and `kind` locally | Green: three replicas running, three exercises demonstrated |
| 2 | Red Hat Developer Sandbox | Partial: quota and the `HPA`/`PDB` settings will need patching |
| 3 | Dynatrace trial tenant | Green: a transfer trace with JDBC spans, tagged with `service.version` |
| 4 | Dynatrace trial plus cluster privileges | Likely refused on Sandbox — record the refusal |
| 5 | Sandbox, then Kind | Likely refused on Sandbox — fall back to Istio on Kind |
| 6 | Steps 1 and 3 complete | Green: an incident visible end to end in Dynatrace |

## Step 1 — Kind first, and why

Do not start on OpenShift. The reason is not caution for its own sake, it is that an
OpenShift failure on a manifest set that has never run anywhere is uninterpretable.

These manifests have been rendered and schema-validated, and nothing more. If the first
place they ever run is the Sandbox and a pod does not come up, the cause could be the
manifest itself, the `restricted-v2` SCC rejecting the pod, the namespace quota refusing
the resource requests, the `Route` not admitting, or the image not pulling — five
candidates, no way to separate them, and a debugging session that teaches nothing about
OpenShift. Get the same manifests green on Kind first and that ambiguity collapses: the
manifests are known-good, so an OpenShift failure is now a statement about the platform
difference, which is exactly the thing worth learning and writing down.

### Bring up the cluster

```bash
cd deploy/kubernetes

kind create cluster --name corebank --config kind-cluster.yaml
```

`kind-cluster.yaml` declares one control-plane node and three workers. The three workers
are not decoration: `deployment.yaml` carries a `podAntiAffinity` rule on
`kubernetes.io/hostname`, and on a single-node cluster that rule has nothing to spread
across. The control-plane node carries `node-labels: "ingress-ready=true"` and maps host
ports `80` and `443`, which is what the ingress-nginx kind manifest selects on.

```bash
kubectl apply -f https://raw.githubusercontent.com/kubernetes/ingress-nginx/main/deploy/static/provider/kind/deploy.yaml
kubectl -n ingress-nginx wait --for=condition=ready pod \
  --selector=app.kubernetes.io/component=controller --timeout=180s
```

Skip the ingress controller if you would rather use `kubectl port-forward`; nothing else
depends on it.

### Secrets, then the rest

```bash
cp secret.example.yaml secret.yaml
$EDITOR secret.yaml

kubectl apply -f namespace.yaml
kubectl apply -f secret.yaml

kubectl apply -k .
kubectl -n corebank rollout status deployment/corebank-api --timeout=300s
```

`secret.yaml` is deliberately absent from `kustomization.yaml` and is listed in
`.gitignore`, so it has to be applied separately and before the rollout. Set
`SPRING_DATASOURCE_PASSWORD` and `POSTGRES_PASSWORD` to the same value — `postgres.yaml`
initialises the database with the second and the application connects with the first, and
nothing checks that they agree.

Apply through kustomize, never `kubectl apply -f deployment.yaml`. `deployment.yaml`
carries the placeholder tag `ghcr.io/maihuyhoang592004/corebank-api:latest`;
`kustomization.yaml` pins `sha-a500aac382ac110b23948b844e8c581c7580cacc`, which is the
tag that is actually deployed. `latest` does not exist in the registry — CI applies it
only on the default branch and no build has landed there — so applying the placeholder
gives `ImagePullBackOff`.

The GHCR package is public, so no pull secret is needed. That was checked by fetching the
pinned tag's manifest anonymously: with an anonymous token it returns the digest CI's
push log recorded, and without a token it returns `401`.

Then either add `127.0.0.1 corebank.local` to `/etc/hosts` and open
`http://corebank.local/dashboard/`, or skip the Ingress:

```bash
kubectl -n corebank port-forward svc/corebank-api 8080:80
```

### What to expect to break, and what is not breakage

**Startup will look slow, and that is the design.** Flyway runs inside the application at
startup. With three replicas starting at once, PostgreSQL's advisory lock serialises them:
the first migrates, the other two block until it commits and then find nothing to do.
Nothing is corrupted, but the second and third pods sit in startup for as long as the
migration takes. The `startupProbe` allows for it — `periodSeconds: 5` with
`failureThreshold: 60`, so five minutes — and the `livenessProbe` does not begin until the
startup probe passes, which is what stops the kubelet killing pods mid-migration. If you
watch `kubectl -n corebank get pods -w` and see two pods sitting at `0/1` for a while,
that is this, not a fault.

**The HPA will report `<unknown>`.** Kind does not ship metrics-server. Without it the
HPA has no metric, reports `<unknown>` for CPU utilisation, and simply never acts — it
does not break the Deployment. The install command is in the comment at the top of
`hpa.yaml`:

```bash
kubectl apply -f https://github.com/kubernetes-sigs/metrics-server/releases/latest/download/components.yaml
kubectl -n kube-system patch deployment metrics-server --type=json \
  -p='[{"op":"add","path":"/spec/template/spec/containers/0/args/-","value":"--kubelet-insecure-tls"}]'
```

The `--kubelet-insecure-tls` patch is needed because kind's kubelet serving certificates
are not signed by a CA metrics-server trusts.

**Check the footprint against your Docker VM before blaming the manifests.** Three
replicas request `250m` CPU and `512Mi` each, and PostgreSQL requests `100m` and `256Mi` —
`850m` CPU and about `1.75Gi` of requests in total, with limits summing to `4` CPU and
`3.5Gi`. Whether that fits a default Docker Desktop VM has not been checked here. If pods
sit `Pending`, read `kubectl -n corebank describe pod` before changing anything: an
insufficient-CPU or insufficient-memory event names the problem directly.

**`/actuator` is not reachable through the Ingress, on purpose.** The ConfigMap sets
`MANAGEMENT_SERVER_PORT: "9091"` and `service.yaml` publishes only the `http` port
(`9090`), so actuator answers the kubelet and an in-cluster scraper and nobody else. To
read metrics during the exercises, port-forward `9091` directly. `/actuator/prometheus`
requires credentials; only `/actuator/health`, `/actuator/health/liveness` and
`/actuator/health/readiness` are anonymous, which is what stops a credential-less kubelet
restart-looping every pod.

### The exercises, and what each one proves

`deploy/kubernetes/README.md` lists four. Three of them run on a bare Kind cluster; the
fourth needs metrics-server installed first.

**Self-healing.** Delete a pod and watch the ReplicaSet create a replacement.

```bash
kubectl -n corebank get pods -w &
kubectl -n corebank delete pod "$(kubectl -n corebank get pod -l app.kubernetes.io/name=corebank-api -o jsonpath='{.items[0].metadata.name}')"
```

What it proves is narrower than it looks, and saying so is the point: nothing detects a
failure here in the sense of an alert firing. The controller reconciles actual state
toward desired state continuously, and a deleted pod is just a difference to close. This
demonstrates Kubernetes, not this application — which is why `docs/19` and the README
both exclude it from the list of failures that told anyone something.

**Zero-downtime rollout.** Keep a request in flight and watch for a non-`200`.

```bash
while true; do curl -s -o /dev/null -w '%{http_code}\n' http://corebank.local/dashboard/index.html; sleep 0.2; done &

kubectl -n corebank set image deployment/corebank-api app=ghcr.io/maihuyhoang592004/corebank-api:<new-sha>
kubectl -n corebank rollout status deployment/corebank-api
```

This proves two independent mechanisms. `maxUnavailable: 0` with `maxSurge: 1` adds a pod
and waits for it to pass readiness before retiring one, so capacity never dips. The
`preStop` sleep of five seconds matters as much: endpoint removal and `SIGTERM` race each
other, and without the pause a terminating pod stops accepting connections while
kube-proxy is still routing to it. Behind both, `spring.lifecycle.timeout-per-shutdown-phase`
is `30s` and `terminationGracePeriodSeconds` is `45`, so an in-flight money command
finishes rather than being killed while holding `SELECT ... FOR UPDATE` locks.

**Rollback.** Roll out something that cannot become ready, then undo it.

```bash
kubectl -n corebank set image deployment/corebank-api app=ghcr.io/maihuyhoang592004/corebank-api:does-not-exist
kubectl -n corebank rollout status deployment/corebank-api --timeout=90s   # fails

kubectl -n corebank rollout undo deployment/corebank-api
kubectl -n corebank rollout status deployment/corebank-api
```

What it proves is that a failed rollout is not an outage: the new pod never passes
readiness, and because `maxUnavailable: 0` means no healthy pod was retired to make room
for it, the service never lost capacity. `revisionHistoryLimit: 5` is what gives
`rollout undo` somewhere to go back to.

There is a limit worth stating out loud when demonstrating this, because it is the part
an interviewer will push on: `rollout undo` returns the *application* to the previous
image while the *database* stays at whatever schema the new version migrated it to.
Migrations under `src/main/resources/db/migration/` are forward-only — `V1` through `V28`,
no down-migrations — so across a release that contained a migration, rollback is not an
undo, it is old code against a newer schema. `docs/31-operations-runbook.md` covers this
under "The limit: rollback is not an undo across a schema change".

**Scaling**, once metrics-server is installed:

```bash
kubectl -n corebank scale deployment/corebank-api --replicas=5
kubectl -n corebank get hpa corebank-api -w
```

`hpa.yaml` targets 70% CPU utilisation between `minReplicas: 3` and `maxReplicas: 6`, with
`scaleDown.stabilizationWindowSeconds: 300` — a JVM that has just warmed up is expensive
to discard and money traffic is bursty, so scale-down is deliberately slow.

## Step 2 — OpenShift Developer Sandbox

The Developer Sandbox gives one namespace with a quota and no cluster-admin. That shapes
everything below.

### Deploy

```bash
oc login --token=<token> --server=<sandbox-api-server>
oc new-project corebank
```

On the Sandbox you may be given a pre-created project rather than being allowed to create
one. If `oc new-project` is refused, use the namespace you were given and expect every
`namespace: corebank` in the manifests to need overriding — `kustomize` can do it with
`oc apply -k` only if you edit the overlay's `namespace:` field, so record which of the
two you had to do.

```bash
oc apply -f deploy/kubernetes/secret.yaml

oc new-app postgresql-persistent \
  -p POSTGRESQL_DATABASE=corebank \
  -p POSTGRESQL_USER=corebank \
  -p POSTGRESQL_PASSWORD=<same value as SPRING_DATASOURCE_PASSWORD>

oc apply -k deploy/openshift
oc -n corebank rollout status deployment/corebank-api
oc get route corebank-api
```

The overlay deletes the base's PostgreSQL `StatefulSet` and `Service`, patches
`SPRING_DATASOURCE_URL` to `jdbc:postgresql://postgresql:5432/corebank` and
`COREBANK_ENVIRONMENT` to `openshift`, deletes the `Ingress`, and adds a `Route` with
`haproxy.router.openshift.io/timeout: 60s`, edge TLS and
`insecureEdgeTerminationPolicy: Redirect`. It patches the `Deployment` not at all — the
image already runs as a non-root user with group 0 given the owner's rights, and
`deployment.yaml` asks for `runAsNonRoot` without naming a UID, which is what
`restricted-v2` wants.

The GHCR package is public, so no pull secret is needed here either.

### Predicted failures, and the patch for each

These are predictions from reading the manifests, not observations. Each one names what to
check first and the smallest change that addresses it.

**Quota versus three replicas.** Three replicas request `750m` CPU and `1.5Gi` memory and
cap at `3` CPU and `3Gi`. Whether that fits your Sandbox's quota is untested, and the
quota differs between Sandbox tiers, so read it rather than assuming a number:

```bash
oc describe resourcequota
oc describe limitrange
```

If the quota is the binding constraint, the reduction is a replica count — but it fights
two other objects, below, so make all three changes together:

```bash
oc -n corebank patch hpa corebank-api --type=merge -p '{"spec":{"minReplicas":1,"maxReplicas":2}}'
oc -n corebank patch pdb corebank-api --type=merge -p '{"spec":{"minAvailable":1}}'
oc -n corebank scale deployment/corebank-api --replicas=1
```

**`hpa.yaml` has `minReplicas: 3`, and it will win.** Scaling the Deployment to 1 without
touching the HPA gets you one pod for as long as it takes the HPA controller to notice,
and then three again. If you see the replica count climb back after a scale-down, this is
why — it is not the quota releasing, it is the autoscaler doing its job. Patch the HPA
first, then the Deployment.

**`pdb.yaml` has `minAvailable: 2`, which blocks eviction once you are at one replica.**
With one pod and `minAvailable: 2`, `disruptionsAllowed` is `0`: `oc adm drain` and any
voluntary eviction hang rather than failing loudly, and on a shared Sandbox that is a
confusing way to lose an afternoon. Check it with:

```bash
oc -n corebank get pdb corebank-api -o jsonpath='{.status}{"\n"}'
```

**`postgresql-persistent` may not be in the catalog.** It is a Red Hat template, and the
Sandbox catalog varies. Check before assuming:

```bash
oc get templates -n openshift | grep -i postgres
```

If it is absent, the database has to come from somewhere else — another catalog entry, a
hand-written Deployment using an image built for arbitrary UIDs, or a managed instance.
Do not reach for `postgres:16-alpine`: the overlay deletes that `StatefulSet` precisely
because it pins `runAsUser: 999` and `fsGroup: 999` and the image genuinely cannot start
as an unknown UID. Relaxing the security context does not help; the image is what cannot
run, not the policy.

Getting the database wrong fails safe, which is worth knowing before you debug it: the
readiness probe gates on the database, so the pods stay out of the `Service` rather than
accepting money commands they cannot finish. Symptom is pods that never become ready, not
errors served to callers.

**A note on what will be reachable.** The ConfigMap sets `SPRING_PROFILES_ACTIVE: showcase`,
and under that profile `DemoSecurityConfig` applies `denyAll()` to `/api/ops/maintenance/**`,
`/api/ops/executions/**` and `/api/ops/security/**`. Those return `403` in any showcase
deployment, including this one. That is a flat denial rather than a token gate — nothing in
`src/main/java` reads `corebank.showcase.token`, so `COREBANK_SHOWCASE_TOKEN` unlocks
nothing. Do not spend time debugging it as a misconfiguration.

### What to capture and paste back

Capture these regardless of whether the deployment succeeded. They are the input to the
next round of work on the overlay.

```bash
oc describe resourcequota                                  > sandbox-quota.txt
oc -n corebank get pods -o wide                            > sandbox-pods.txt
oc -n corebank describe pod -l app.kubernetes.io/name=corebank-api > sandbox-pod-describe.txt
oc -n corebank get events --sort-by=.lastTimestamp         > sandbox-events.txt
oc -n corebank logs deployment/corebank-api --tail=200     > sandbox-app-logs.txt
oc get route corebank-api -o yaml                          > sandbox-route.yaml
oc -n corebank get hpa,pdb,deployment -o wide              > sandbox-workloads.txt
```

The two that matter most are `sandbox-quota.txt` and `sandbox-events.txt`. The quota turns
gap 4 in `deploy/kubernetes/README.md` — "resource footprint never checked against a
Developer Sandbox quota" — into a measured number, and the events are where an SCC
rejection or a quota refusal actually appears, as a `FailedCreate` on the ReplicaSet rather
than anything visible on the Deployment.

If the pods do come up, also capture `curl -k https://<route-host>/actuator/health` and the
`Route`'s generated host. The `Route` sets no host of its own and carries no certificate —
OpenShift generates the hostname and the router serves its default wildcard certificate,
which is why `-k` may be needed and why this is a lab arrangement rather than a named
domain.

## Step 3 — Dynatrace telemetry ingest

The aim of this step is narrow and worth stating precisely: prove that this application's
OTLP output arrives in a real vendor backend, with the release identity attached and the
database work visible inside the request. It is not a Kubernetes step — that is Step 4.

Run it against the local compose collector first, for the same reason Step 1 comes before
Step 2. The cluster collector manifests exist but have never been applied and the
collector binary has never been started with that configuration, so putting them in the
path on the first attempt means debugging two new things at once. Get telemetry arriving
in the tenant from a laptop, then move the same flow into the cluster — that part is at
the end of this section.

### Sign up and mint a token

Take a Dynatrace trial tenant. The token needs three ingest scopes:

- `openTelemetryTrace.ingest`
- `metrics.ingest`
- `logs.ingest`

Those three scope names come from Dynatrace's own token UI, not from anything in this
repository — they are the one part of this step that cannot be verified here. If the names
differ in the version of the console you get, record what you actually selected.

The endpoint takes the form `https://<env>.live.dynatrace.com/api/v2/otlp`, which is the
value written in the comment beside the exporter in `deploy/observability/otel-collector.yaml`.

### Turn the exporter on

In `deploy/observability/otel-collector.yaml` the Dynatrace exporter is present but
commented out:

```yaml
  # otlphttp/dynatrace:
  #   endpoint: ${env:DYNATRACE_OTLP_ENDPOINT}   # https://<env>.live.dynatrace.com/api/v2/otlp
  #   headers:
  #     Authorization: "Api-Token ${env:DYNATRACE_API_TOKEN}"
```

Uncomment it and add `otlphttp/dynatrace` to the `traces` and `metrics` pipelines under
`service.pipelines`. The existing exporters can stay — `otlp/tempo` for traces and
`prometheus` for metrics — and having both in front of you is useful, because it shows the
same telemetry arriving in a self-hosted stack and a managed APM from one unchanged
application.

`${env:VAR}` is required, not stylistic. On collector `0.115.1` the bare `${VAR}` form is
not expanded as an environment variable; this is recorded in the comments of
`deploy/observability/kubernetes/otel-collector-config.yaml`, where the same two values are
used. Getting it wrong produces an exporter whose endpoint is the literal string, and the
failure shows up as export errors rather than as a config parse error.

Export both variables into the collector's environment, then run the application with
export enabled:

```bash
export DYNATRACE_OTLP_ENDPOINT='https://<env>.live.dynatrace.com/api/v2/otlp'
export DYNATRACE_API_TOKEN='dt0c01....'

docker compose -f deploy/observability/docker-compose.yml up -d

COREBANK_OTLP_ENABLED=true COREBANK_LOG_FORMAT=ecs ./mvnw spring-boot:run
```

`COREBANK_OTLP_ENABLED` turns on export only; instrumentation is always on, which is why
every log line carries a trace id even with no collector running. Sampling defaults to
`1.0` via `COREBANK_TRACE_SAMPLE_RATE`, so nothing is dropped during a demo.

One detail that will matter if metrics look wrong: `management.otlp.metrics.export.aggregation-temporality`
is set to `delta` in `application.yml`, against Spring Boot's cumulative default, because
Dynatrace ingests OTLP metrics as deltas. That choice is made in the application rather than
with the collector's `cumulativetodelta` processor, because the process knows its own
cumulative baseline and a stateful collector cannot safely run more than one replica.

### What to verify

**A distributed trace of a transfer, with the database inside it.** Generate one:

```bash
BASE=http://localhost:9090
curl -s -u demo_ops:demo_ops -X POST "$BASE/api/demo/setup" | jq '.accountIds, .ledgerAccountIds'

curl -s -u demo_admin:demo_admin -X POST "$BASE/api/transfers/internal" \
  -H 'Content-Type: application/json' \
  -H 'X-Correlation-Id: onboarding-step3-1' \
  -d '{
        "idempotencyKey": "onboarding-step3-1",
        "sourceAccountId": "<from accountIds>",
        "destinationAccountId": "<from accountIds>",
        "amountMinor": 1000,
        "currency": "VND",
        "debitLedgerAccountId": "<from ledgerAccountIds>",
        "creditLedgerAccountId": "<from ledgerAccountIds>",
        "description": "onboarding step 3",
        "actor": "demo_admin"
      }'
```

In Dynatrace, open that trace. What you are checking for is three levels, not one:

1. the HTTP server span for `POST /api/transfers/internal`;
2. a JDBC `CONNECTION` span inside it;
3. `QUERY` spans inside that.

`jdbc.includes` is `CONNECTION,QUERY` in `application.yml`, from the
`net.ttddyy.observation:datasource-micrometer-spring-boot` dependency at version `2.2.1`.
`CONNECTION` is listed first deliberately: this system's real outage was connection
starvation, not slow SQL, and a `CONNECTION` span is what makes that visible as time spent
acquiring rather than as unattributed latency inside the server span. `FETCH` and `KEYS`
are omitted because they multiply span count and answer questions this system has never
needed to ask.

Before the dependency was added, this verification would have failed: Spring's JDBC support
carries no Observation instrumentation, so a transfer produced one server span with the
whole database portion inside it as unexplained time.

Bind parameters are excluded from spans — `jdbc.datasource-proxy.include-parameter-values`
is `false`, set explicitly rather than left to the library default, because in this
application those parameters are account identifiers, amounts and customer references. If
you see values in the spans, something has changed and it is a finding worth reporting.

**`service.version` on the service.** Check that the service in Dynatrace carries a
`service.version` resource attribute. It is set in `application.yml` from
`${spring.application.version:unknown}`, which Spring Boot derives from the jar manifest,
which `spring-boot-maven-plugin`'s `build-info` goal writes, which the `Dockerfile`'s
`ARG APP_VERSION` feeds through `-Drevision`, which CI sets to `${{ github.sha }}`. The
same value is what `/actuator/info` reports and what the `sha-<commit>` image tag names.

Running locally with `./mvnw spring-boot:run` there is no manifest on the application
package, so expect `unknown` — the `:unknown` default is required rather than decorative,
because an unresolvable placeholder fails context startup outright. To see a real version
you have to run the built image. That is the arrangement Step 6 depends on.

`service.version` is deliberately *not* in `management.metrics.tags`. Those are common tags
on every meter, so putting the version there would change the label set of every series on
every deploy, break `rate()` continuity application-wide, and double the active series for
the length of each rollout. Release identity belongs on the resource.

### Then move it into the cluster

`deploy/observability/kubernetes/` carries the collector as a separate kustomize root:
`otel-collector-config.yaml`, `deployment.yaml`, `service.yaml`, `secret.example.yaml` and
`kustomization.yaml`, with its own `README.md`. It is a forwarder with one exit — no
Tempo, no Prometheus, no Grafana — because a self-hosted stack is a second system to
operate and would not fit a Sandbox quota.

The namespace is `corebank`, not `observability`. `kustomization.yaml` sets it globally and
deliberately does not include a `Namespace` resource, because `deploy/kubernetes/namespace.yaml`
already owns that object.

```bash
$EDITOR deploy/observability/kubernetes/kustomization.yaml   # DYNATRACE_OTLP_ENDPOINT

cd deploy/observability/kubernetes
cp secret.example.yaml secret.yaml
$EDITOR secret.yaml
kubectl apply -f secret.yaml

kubectl apply -k .
kubectl -n corebank rollout status deployment/otel-collector --timeout=120s
```

The endpoint is a `configMapGenerator` literal and the token is a separately applied
`Secret` (`otel-collector-secrets`, key `DYNATRACE_API_TOKEN`, gitignored), so no token
ever appears in a kustomize render or a CI log. The generator's hash suffix is
load-bearing rather than cosmetic: the collector does not watch or reload its config file,
so editing a fixed-name `ConfigMap` would change the projected file and change nothing
else — the process keeps running what it parsed at startup. A content hash means new
content is a new name, a changed pod spec and a rollout, and the rollout is the reload.

Only once that rollout is `Available`, cut the application over — three values in
`deploy/kubernetes/configmap.yaml`:

```yaml
COREBANK_OTLP_ENABLED: "true"
COREBANK_OTLP_TRACES_ENDPOINT: http://otel-collector.corebank:4318/v1/traces
COREBANK_OTLP_METRICS_ENDPOINT: http://otel-collector.corebank:4318/v1/metrics
```

**Do not copy the hostnames from that ConfigMap's own comment.** It names
`otel-collector.observability:4318`, a collector in a separate namespace, and it is stale:
`deploy/openshift/kustomization.yaml` sets `namespace: corebank` globally and a Developer
Sandbox grants one project, so a second namespace is not something that path can create.
A wrong Service hostname resolves to nothing and every export fails silently from the
application's point of view. Correcting that comment is the first edit at cutover.

Editing the ConfigMap does not restart the application pods. `kubectl -n corebank rollout
restart deployment/corebank-api` does.

What is verified about these manifests is narrow, and worth knowing before you debug them:
they render byte-identically under `kustomize v5.4.3` and `kubectl v1.31.0`, pass
`kubeconform -strict` against Kubernetes 1.31.0 with 4 resources and 0 errors, and CI now
renders and validates this root and extends its floating-tag guard to cover it. **Nothing
here has been applied to a cluster and the collector binary has never been started with
this configuration.** The config travels inside a `ConfigMap` as an opaque string, so a
typo in the `otlphttp/dynatrace` exporter would render, validate, pass CI, and first
appear as a pod in `CrashLoopBackOff`. Three things to check on first contact, each an
open question rather than a known: whether `otel/opentelemetry-collector-contrib:0.115.1`
declares a numeric non-root `USER` so `runAsNonRoot: true` is satisfiable, whether
`readOnlyRootFilesystem: true` is workable for that image, and whether the `health_check`
extension serves `path: /` on `0.115.1` — the readiness and liveness probes both depend on
the last one.

## Step 4 — Dynatrace Kubernetes onboarding

This is a different claim from Step 3 and the distinction is the whole reason it is a
separate step.

Step 3 proves *"I can instrument an application into Dynatrace"* — OTLP out of a JVM, into a
collector, into a tenant, with correct release identity and useful span structure. That is
an application-instrumentation skill.

Step 4 proves *"I know Dynatrace on Kubernetes"* — the operator, its custom resource, the
privileges it demands, what it discovers on its own, and how that discovery decorates the
telemetry from Step 3 with cluster context. That is a platform skill, and it is the one an
APM implementation engagement at a bank actually consists of. Conflating them in a
conversation is easy to do and easy to catch.

### What this step is about

Everything in this section is Dynatrace product behaviour and is stated from its
documentation, not verified against a file in this repository. Treat it as the shape of the
work, and let the actual attempt correct it.

- **Dynatrace Operator and the `DynaKube` custom resource.** The operator is installed into
  its own namespace and reconciles a `DynaKube` object that names the tenant, the tokens and
  the deployment mode (a node-level agent set versus application-only injection). Which mode
  you can use is exactly what the Sandbox's privileges decide.
- **RBAC and cluster privileges.** The operator needs `CustomResourceDefinition` creation,
  cluster-scoped read across namespaces, and — for node-level monitoring — privileged pods
  on every node. On OpenShift that additionally means an SCC that permits what
  `restricted-v2` does not.
- **Namespace monitoring scope.** Which namespaces get instrumented is a selector on the
  `DynaKube`, and on a shared cluster the interesting question is whether you are permitted
  to select anything beyond your own.
- **Kubernetes metadata enrichment.** The payoff: spans and metrics arrive already carrying
  namespace, workload, pod and node, so the Step 3 trace can be filtered by workload and a
  latency regression can be attributed to a rollout rather than to a time of day.

### Expect a refusal, and record it properly

The Developer Sandbox is a constrained tenancy on OpenShift Dedicated. You are not
cluster-admin, you cannot create cluster-scoped resources, and privileged DaemonSets are not
going to be admitted. **A refusal here is the expected outcome, and it is a legitimate
deliverable.** The failure would be attempting it, getting an error, and writing down
"didn't work".

What makes a refusal into evidence is that it is specific: the exact command, the exact
error, the exact permission or API group that was denied, and the reason that restriction
exists on this platform. That is the same discipline as the rest of this repository — a
documented negative result is a result.

Template for recording it, one per refused operation:

```text
## Attempt: <what you tried, e.g. install Dynatrace Operator via Helm into dynatrace ns>

Date:        <YYYY-MM-DD>
Platform:    Red Hat OpenShift Developer Sandbox
Identity:    `oc whoami` -> <output>
Command:
    <the exact command, verbatim>

Error, verbatim:
    <paste the full error, not a paraphrase>

What it wanted:
    <the specific RBAC verb/resource/API group, or the SCC, or the cluster-scoped
     object — e.g. "create on customresourcedefinitions.apiextensions.k8s.io at
     cluster scope">

Evidence gathered:
    oc auth can-i create customresourcedefinitions          -> <yes/no>
    oc auth can-i create clusterroles                        -> <yes/no>
    oc auth can-i use scc/privileged                         -> <yes/no>
    oc auth can-i create daemonsets -n <ns>                  -> <yes/no>

Why the platform restricts it:
    <one or two sentences: OpenShift Dedicated is a managed offering; the Sandbox
     grants a single project with no cluster-scoped authority, because the cluster
     is shared with other tenants and a node-level agent sees all of them.>

What would be needed instead:
    <e.g. cluster-admin on a self-managed cluster, or application-only injection
     if that mode turns out to need no cluster-scoped objects — say which, and
     whether you were able to test it.>
```

Run the `oc auth can-i` checks even if the install fails early. They convert "it was
refused" into "here is precisely what this platform does and does not grant", which is the
difference between an anecdote and a finding.

If application-only injection *does* turn out to be permitted within a single namespace,
that is a more interesting result than a clean failure — capture what it enriched and what
it did not, because it bounds the claim you can make afterwards.

## Step 5 — Service mesh

Same shape as Step 4: attempt the platform-native thing first, record what happens, then
fall back to an environment where the concepts can actually be demonstrated.

### Attempt it on the Sandbox

Try OpenShift Service Mesh. It is delivered as an operator with cluster-scoped custom
resources, so the likely outcome is the same class of refusal as Step 4. Record it with the
same template — the command, the verbatim error, the permission it wanted, the reason.

```bash
oc auth can-i create subscriptions.operators.coreos.com -n openshift-operators
oc auth can-i create servicemeshcontrolplanes.maistra.io
oc get csv -n openshift-operators 2>&1 | head
```

Whatever those return is the artefact for this step on OpenShift.

### Fall back to Istio on Kind

The Kind cluster from Step 1 is already running the same manifests, so it is the right place
to demonstrate the traffic-management concepts. Two things are worth doing and neither needs
new application code:

- **A canary split.** Deploy a second `Deployment` at a different image tag, put both behind
  one `Service` or one `DestinationRule` subset pair, and split traffic by weight. This is
  the guarantee `maxUnavailable: 0` does not give: the rollout safety in this repository
  comes from probes and surge, which protects capacity but never routes a controlled
  fraction of real traffic to a new version.
- **mTLS between workloads.** Turn on strict mode and show the application-to-PostgreSQL and
  ingress-to-application paths encrypted without touching the application. Today there is no
  mTLS between workloads at all — traffic goes `Ingress` or `Route` → `Service` → pods.

### The claim to make afterwards, exactly

Use this wording, and do not extend it:

> OpenShift Service Mesh 3 is Istio-based. I validated the traffic-management concepts
> locally with Istio and separately investigated the OpenShift operator and deployment model.

Two clauses, two different strengths, joined by "separately" on purpose. The first is a
thing you did. The second is a thing you read about and attempted. Merging them into "I
have done OpenShift Service Mesh" is the overstatement this wording exists to prevent, and
it is the kind of claim that does not survive one follow-up question from someone who has
run it.

## Step 6 — The end-to-end incident demo

This is the acceptance test for the whole exercise, and the thing worth showing rather than
describing. Nine steps. It needs Step 1 (a cluster) and Step 3 (a Dynatrace tenant
receiving telemetry) complete, including the cutover at the end of Step 3 —
`COREBANK_OTLP_ENABLED` is `"false"` in `deploy/kubernetes/configmap.yaml` until you change
it, so an uncut cluster exports nothing. If the in-cluster collector will not come up, fall
back to running the built image against the local compose collector and accept that the
Kubernetes context in Dynatrace will be missing; say which arrangement you used.

**1. Deploy release `sha-A`.** Edit `newTag` in `deploy/kubernetes/kustomization.yaml` by
hand to the sha you want, then `kubectl apply -k deploy/kubernetes` and
`kubectl -n corebank rollout status deployment/corebank-api --timeout=300s`. Edit it by
hand rather than with `kustomize edit set image`: on v5.4.3 that command reindents every
list, adds a redundant `newName` and silently drops the explicit `includeSelectors: false`,
turning a one-line version bump into a thirteen-line diff nobody reads.

**2. Dynatrace shows `service.version = sha-A`.** This is the step that makes the rest
attributable. Cross-check it against `/actuator/info` on the running pod and against the
image tag — all three come from the same value, so disagreement means the build argument
did not flow, not that Dynatrace is wrong.

**3. Run transfers and record the baseline.** Use distinct idempotency keys. Twelve
concurrent transfers with distinct keys completed cleanly in the measurements recorded in
`docs/19-runtime-failure-modes.md`, so this should be uneventful — that is the point of a
baseline.

A caveat you must not skip here, because it changes where the number comes from: **the
repository's own p95 query does not work.** `http_server_requests_seconds_bucket` does not
exist. Micrometer publishes a Prometheus summary for a `Timer` unless a distribution
histogram is explicitly requested, and there is no
`management.metrics.distribution.percentiles-histogram` key anywhere in
`src/main/resources/application.yml`. The `MoneyEndpointLatency` alert in
`deploy/observability/alerts.yml` and the latency panel on the Grafana overview dashboard
are both built on that non-existent series, so neither has ever been able to fire or plot.
`docs/32-service-levels.md` records this as a gap, with `money:http_request_duration_seconds:mean5m`
recorded from `_sum` and `_count` as a weaker stand-in. So take the p95 for this demo from
Dynatrace's own span-derived latency view, and say which source you used. Whether that is
an acceptable substitute for a Prometheus-side SLI is an open question, not a settled one.

**4. Inject database and connection pressure.** Use the failure this system actually
suffered, because it is measured and it reproduces:

```bash
# Cold instance, pool at the old default of 10.
kubectl -n corebank set env deployment/corebank-api COREBANK_DB_POOL_SIZE=10
kubectl -n corebank rollout status deployment/corebank-api

# 14 concurrent requests sharing ONE idempotency key.
for i in $(seq 1 14); do
  curl -s -o /dev/null -w '%{http_code}\n' -u demo_admin:demo_admin \
    -X POST "$BASE/api/transfers/internal" \
    -H 'Content-Type: application/json' \
    -H 'X-Correlation-Id: incident-demo-1' \
    -d '{"idempotencyKey":"incident-demo-1", ... }' &
done; wait
```

What was measured, on a real PostgreSQL: at pool `10` this produced **32 seconds** of
starvation, one `200`, three `400`s and ten `500`s with connection-pool timeouts, and
exactly one journal posted. The identical burst at pool `40` finished in one second with no
errors and, again, exactly one journal. The cause was not lock contention — that was the
first hypothesis and it was wrong. The pool metrics showed every connection leased and
thirteen threads queueing with no lock timeouts at all, because a money command holds two
connections at once: the business transaction plus the `REQUIRES_NEW` transaction that
records the idempotency outcome inside it.

The deployed default is `COREBANK_DB_POOL_SIZE: "20"`, which is why you have to lower it to
reproduce. Put it back afterwards.

**5. In Dynatrace: latency rises, and the trace says why.** Open the transfer trace and
look at the JDBC `CONNECTION` span. The time should be in connection *acquisition*, not in
query execution. That distinction — "the API is slow" versus "this query is slow" versus
"we ran out of connections" — is the entire reason the `CONNECTION` span is instrumented,
and this step is where you show it rather than assert it.

**6. Logs carry the same `traceId` and `correlationId`.** With `COREBANK_LOG_FORMAT=ecs`
each log line is one Elastic Common Schema JSON document, and `traceId`, `spanId` and
`correlationId` are fields rather than text inside the message. Pivot from the trace to its
logs and back. The correlation id is taken from the `X-Correlation-Id` header when the
caller supplies a sane value, generated otherwise, and echoed back on the response — which
is what lets a customer quote a value you can search for. It is published as tracing baggage
rather than written straight to the MDC, because Micrometer owns that MDC key once the
field is declared in `management.tracing.baggage.correlation.fields` and rewrites it when a
span scope opens.

The distinction to state while showing it: a `traceId` identifies a technical request path,
a `correlationId` can be pinned to a business operation and is written into audit and outbox
rows.

**7. Banking metrics confirm the money invariant held.** This is the step that separates
this demo from a generic APM demo, and it is the one to spend time on.

```bash
kubectl -n corebank port-forward deployment/corebank-api 9091:9091
curl -s -u demo_ops:demo_ops http://localhost:9091/actuator/prometheus | grep -E '^corebank_'
```

What to read, and how:

- `corebank_ledger_journals_posted_total` — a counter, incremented from an `afterCommit`
  synchronisation in `LedgerCommandService`, so it counts journals *committed* by that
  process. This is the throughput signal and the one that is safe to `sum(rate(...))` across
  replicas. Across the burst it should advance by exactly one.
- `corebank_ledger_journals` — a gauge, and a **size**: total `ledger_journals` rows from a
  global `COUNT(*)`, which every replica reports identically. Do not `rate()` it and do not
  `sum()` it across replicas. It answers growth and retention questions, not throughput
  ones.
- `corebank_idempotency_stale` — claims past the takeover lease. Any non-zero value is a
  fault, which is why `IdempotencyKeysStranded` fires on `> 0` for 5m. It used to fire on
  `corebank_idempotency_in_flight > 10` for 15m, which is a traffic measure: ten money
  commands in flight at once is a busy instance, so the rule paged on load and stayed silent
  on the fault it was written for.
- `corebank_outbox_pending` and `corebank_outbox_dead_letters` — events written inside money
  transactions but not published, and events that exhausted their retries.
- `corebank_reconciliation_open_breaks` — the ledger and an external statement disagreeing.

Two properties of these gauges will bite you if you read them immediately after a restart:
the five gauges above are refreshed by `BankingMetrics.refresh()` on a `fixedDelay` of
15000 ms with an `initialDelay` of 5000 ms and served from memory, so a gauge is up to 15
seconds stale and reads `0` for roughly the first five seconds of a process's life. The
counter is neither: `LedgerCommandService` increments it from an `afterCommit` hook, so it
is current the instant a journal commits. They are served from memory on purpose — a gauge
that runs SQL per scrape hands anyone who can reach the metrics endpoint a way to load the
database, and the cost multiplies with every scraper.

The invariant to state out loud: across every induced failure recorded in `docs/19`,
including the two that returned `500`s, total money was unchanged, no journal was
unbalanced and no balance went negative. What failed was availability and recoverability,
never correctness. This step is where that claim gets demonstrated rather than repeated.

**8. Deploy `sha-B`, or roll back.** Either bump `newTag` and apply, or
`kubectl -n corebank rollout undo deployment/corebank-api`. Restore
`COREBANK_DB_POOL_SIZE` to `20` as part of this. Before rolling back across a real release,
check whether it contained a migration — `git log` over `src/main/resources/db/migration/`
between the two tags answers it — because forward-only migrations mean rollback is old code
against a newer schema.

**9. Verify recovery.** Latency back to baseline in Dynatrace, `service.version` now showing
`sha-B` (or `sha-A` again after an undo), all three probes green, and no reconciliation
break opened:

```bash
curl -s -u demo_ops:demo_ops "$BASE/api/reporting/reconciliation/breaks?status=OPEN&limit=50"
```

Note the path: the read endpoint is `GET /api/reporting/reconciliation/breaks` on
`ReportingController`. The `runbook:` annotation on the `ReconciliationBreaksOpen` rule in
`deploy/observability/alerts.yml` points at `/api/ops/reconciliation/breaks`, but
`OpsReconciliationController` exposes only `POST /runs` and `POST /external/runs` — that
annotation is wrong and is worth fixing while you are here.

Also confirm `corebank_idempotency_stale` is back to `0`. If it is not, the burst left
claims whose owner is gone, and `docs/31-operations-runbook.md#idempotencykeysstranded` is
the procedure — cross-check against `ledger_journals` to establish what actually committed
before clearing any key.

## What to capture

Keep the artefact, not the memory of it. Each of these is something that can be shown
later; none of them can be reconstructed afterwards.

**Step 1 — Kind.**
- `kubectl -n corebank get pods -o wide` with three replicas spread across the three workers
  (proves the anti-affinity rule did something).
- The terminal from the zero-downtime rollout showing an unbroken column of `200`s.
- The failed-rollout output followed by `rollout undo` succeeding, with the service never
  having lost capacity.
- Timing of the first rollout: how long the second and third pods sat in startup waiting on
  the Flyway advisory lock. That number is not recorded anywhere yet.

**Step 2 — OpenShift Sandbox.**
- The seven files from the capture block above, `sandbox-quota.txt` and `sandbox-events.txt`
  first.
- Every patch you had to apply, as the exact command, plus whether the HPA fought you.
- Whether `postgresql-persistent` was in the catalog, and what you used if it was not.

**Step 3 — Dynatrace ingest.**
- A screenshot of the transfer trace showing the three levels: HTTP span, `CONNECTION` span,
  `QUERY` spans.
- The service detail page showing `service.version`.
- Confirmation that no bind parameters appear in any span.
- The collector's own log at `warn` level during a successful export run.
- From the cluster cutover: `kubectl -n corebank get pods -l app.kubernetes.io/name=otel-collector`,
  the collector's logs, and the answers to the three open questions about the image —
  whether `runAsNonRoot` was satisfiable, whether `readOnlyRootFilesystem` held, and
  whether `health_check` answered on `/`. Those three are currently unknown, and one
  rollout settles all of them.

**Step 4 — Dynatrace on Kubernetes.**
- A completed refusal record per the template, including all four `oc auth can-i` results.
- If anything did install, the `DynaKube` YAML as applied and what enrichment appeared.

**Step 5 — Service mesh.**
- The OpenShift refusal, same template.
- From Kind: the canary weight configuration and a request distribution that matches it, and
  the mTLS status output.

**Step 6 — Incident demo.**
- The baseline latency reading, with the source named (Dynatrace span-derived, not the
  broken Prometheus query).
- The burst output: fourteen status codes, and the elapsed time.
- The trace showing time in connection acquisition.
- One log line, in full ECS JSON, showing `traceId` and `correlationId` matching the trace.
- The `corebank_*` scrape from before and after the burst, showing
  `corebank_ledger_journals_posted_total` advanced by exactly one.
- The recovery scrape showing `corebank_idempotency_stale` back at `0`.

## Open questions

These could not be settled by reading the repository and should be answered by the attempt
rather than guessed at now.

- Does the Developer Sandbox quota admit three replicas at `750m`/`1.5Gi`, or one? The
  arithmetic is known; the quota is not.
- Is `postgresql-persistent` present in the Sandbox catalog?
- Do the three Dynatrace token scope names match what the current console offers?
- Does application-only injection (Step 4) work within a single namespace without
  cluster-scoped objects, or is the whole operator out of reach on the Sandbox?
- Is Dynatrace's span-derived response-time percentile an acceptable SLI source given that
  `http_server_requests_seconds_bucket` does not exist on the Prometheus side, or does
  `management.metrics.distribution.percentiles-histogram` need enabling first — and at what
  cardinality cost?
- The `logs` pipeline in `deploy/observability/kubernetes/otel-collector-config.yaml` has
  never been observed carrying data; the application sets no `management.otlp.logging` key
  and ships logs as ECS JSON on stdout. Does anything send OTLP logs at all?
- Does `otel/opentelemetry-collector-contrib:0.115.1` declare a numeric non-root `USER`? If
  not, `runAsNonRoot: true` cannot be satisfied and the fix — naming a `runAsUser` —
  is exactly what `restricted-v2` rejects.
- Is `readOnlyRootFilesystem: true` workable for that image, and does the `health_check`
  extension serve `path: /` on `0.115.1`? Both probes depend on the second.

## Deliberately not here

- **A Sandbox-specific kustomize overlay.** The patches in Step 2 are given as `oc patch`
  commands rather than as a committed overlay, because the values depend on a quota nobody
  has read yet. Writing the overlay before the measurement would be guessing in YAML.
- **Automating any of this.** Every step here exists to be done once, by a person, with the
  outcome written down. A script would hide the errors that are the deliverable.
- **Production secret handling.** `secret.example.yaml` produces a Kubernetes `Secret`,
  which is base64, not encryption. A real deployment sources these from a secret manager,
  and saying so is a decision rather than an omission.

## Not done yet

These are gaps. They are listed separately from the section above because none of them is
a choice.

1. **No telemetry leaves a Kubernetes deployment yet.** The collector manifests in
   `deploy/observability/kubernetes/` exist and validate, but they have never been applied
   and `COREBANK_OTLP_ENABLED` is still `"false"` in `deploy/kubernetes/configmap.yaml`.
   That ordering is deliberate — export with nothing answering is worse than no export —
   but until the cutover in Step 3 it is still the largest gap between what the application
   can emit and what the deployment collects. Three smaller holes sit inside it: the
   collector image is pinned to the tag `0.115.1` rather than a digest, there is no
   `NetworkPolicy` so any pod in the cluster can post telemetry to the receiver, and
   nothing alerts on the collector's own `otelcol_exporter_send_failed_*` counters — so a
   wrong token, the failure this path most needs to detect, is the one it is blind to.
2. **Nothing scrapes `/actuator/prometheus` in-cluster.** The pods carry `prometheus.io/*`
   annotations, but no `ServiceMonitor` exists and no credentials `Secret` exists, and the
   endpoint requires authentication. The alerts in `deploy/observability/alerts.yml` have
   nothing evaluating them on the Kubernetes path.
3. **`http_server_requests_seconds_bucket` does not exist**, so the `MoneyEndpointLatency`
   alert, the Grafana latency panel and the latency SLI in `docs/32-service-levels.md` are
   all built on an empty series. One configuration key fixes it, plus a deliberate decision
   about cardinality.
4. **The OpenShift overlay has never been applied to a live cluster.** Step 2 is the first
   time it will be. Every claim in `deploy/kubernetes/README.md` about SCC admission and
   router behaviour is reasoning from documentation until then.
5. **Customer-secret endpoints return `503` on every deployment path.**
   `corebank.security.master-key-b64` is unset everywhere under `deploy/`, and
   `CustomerSecretCryptoService` answers `SERVICE_UNAVAILABLE` without it.
6. **The showcase gate is a flat denial, not a token gate.** Nothing in `src/main/java`
   reads `corebank.showcase.token`, so `COREBANK_SHOWCASE_TOKEN` unlocks nothing and
   `/api/ops/maintenance/**`, `/api/ops/executions/**` and `/api/ops/security/**` return
   `403` in any showcase deployment. The matcher `/api/ops/security/**` also matches no
   controller — the customer-secret endpoints are at `/api/ops/customers/**`.
