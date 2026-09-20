# Collector on Kubernetes

An OpenTelemetry Collector for a cluster, forwarding everything the application emits
to Dynatrace over OTLP/HTTP. It is the missing half of `deploy/observability/`: that
directory brings a collector, Prometheus, Tempo and Grafana up under docker compose,
and until now none of it had a Kubernetes manifest, so `COREBANK_OTLP_ENABLED` has
been `"false"` on every deployment path.

This is not the compose stack moved into a cluster, and that is the main decision
recorded here. The lab fans telemetry out to Tempo, to a Prometheus exposition port and
to the `debug` exporter, because all three run next to it on a laptop. Running that
shape in a cluster means operating, securing, backing up and paying for a second
system, and it does not fit an OpenShift Developer Sandbox quota. What is here instead
is a forwarder with exactly one exit: the application keeps speaking OTLP and nothing
else, and the backend is one `endpoint` and one token.

```
deploy/observability/kubernetes/
├── otel-collector-config.yaml   collector config — generated into a hashed ConfigMap
├── deployment.yaml              1 replica, restricted-v2-safe security context
├── service.yaml                 ClusterIP, 4317/4318, no Route
├── secret.example.yaml          template — copy to secret.yaml, never commit it
└── kustomization.yaml           namespace, configMapGenerator, labels
```

```
CoreBank pods ──OTLP/HTTP 4318──▶ otel-collector ──otlphttp──▶ Dynatrace
                                        │
                                  13133 health ◀── kubelet probes
```

## Bring it up

The `corebank` namespace has to exist first; `deploy/kubernetes/namespace.yaml`
creates it, and this directory deliberately does not, because two manifests owning one
Namespace object is a conflict waiting for whichever is applied second.

```bash
# The Dynatrace ingest URL is not a secret and lives in kustomization.yaml.
$EDITOR deploy/observability/kubernetes/kustomization.yaml   # DYNATRACE_OTLP_ENDPOINT

# The token is. It is applied separately, so it never appears in a kustomize render.
cd deploy/observability/kubernetes
cp secret.example.yaml secret.yaml
$EDITOR secret.yaml
kubectl apply -f secret.yaml

kubectl apply -k .
kubectl -n corebank rollout status deployment/otel-collector --timeout=120s
```

On OpenShift the same two files apply with `oc` instead. Nothing in this directory is
OpenShift-specific; it is written so that one set of manifests is valid on both, which
is why the pod's security context names no UID.

## Turning the application's export on

Nothing is exported yet. `COREBANK_OTLP_ENABLED` in
`deploy/kubernetes/configmap.yaml` is still `"false"` and has deliberately not been
touched: the collector lands first, and flipping the switch before a collector answers
produces a steady stream of export failures and no telemetry, which is worse than not
exporting. That ordering is the same argument the ConfigMap itself makes.

Once the rollout above is `Available`, the cutover is three values in
`deploy/kubernetes/configmap.yaml`:

```yaml
COREBANK_OTLP_ENABLED: "true"
COREBANK_OTLP_TRACES_ENDPOINT: http://otel-collector.corebank:4318/v1/traces
COREBANK_OTLP_METRICS_ENDPOINT: http://otel-collector.corebank:4318/v1/metrics
```

Those are not the hostnames the ConfigMap's own comment suggests, and the difference
matters. It names `otel-collector.observability:4318`, a collector in a separate
`observability` namespace. `deploy/openshift/kustomization.yaml` sets
`namespace: corebank` globally and a Developer Sandbox grants one project, so a second
namespace is not something that path can create. This directory therefore deploys into
`corebank`, and the ConfigMap's comment is now stale. It was left stale on purpose
rather than edited alongside a collector that has never run — but it is the first thing
to correct at cutover, because a wrong Service hostname resolves to nothing and every
export fails silently from the application's point of view.

Changing that ConfigMap does not restart the application pods. `kubectl rollout restart
deployment/corebank-api` does.

## Three things the manifests are shaped around

**The security context names nothing.** There is no `fsGroup`, no `runAsUser` and no
`runAsGroup`, and their absence is the feature. OpenShift's `restricted-v2` SCC assigns
each namespace a UID range and runs every container as a UID from it; a pod that asks
for any of those three is rejected at admission. That is the exact failure that stops
`deploy/kubernetes/postgres.yaml` applying on OpenShift and the reason
`deploy/openshift/` deletes it rather than patching it. `fsGroup` is the one that looks
necessary and is not: it exists to make a mounted volume group-readable, and the only
volumes here are a ConfigMap projection — world-readable by default — and an
`emptyDir`. What is set is the part that is a constraint rather than an identity:
`runAsNonRoot: true`, `seccompProfile: RuntimeDefault`, `allowPrivilegeEscalation:
false`, `capabilities: drop: ["ALL"]`, `readOnlyRootFilesystem: true`.

**The memory limit is not optional.** `memory_limiter` is configured with
`limit_percentage`, and that percentage is read against the container's cgroup memory
limit. With no `resources.limits.memory` the collector resolves it against the node's
total memory, computes a ceiling the pod can never reach, never refuses anything, and
is OOM-killed by the kubelet instead — losing a whole batch rather than shedding the
tail of one. The limit is `512Mi`, so the soft ceiling is roughly `384Mi` with a 15%
spike allowance.

**The ConfigMap is generated, not written.** The collector does not watch its config
file and does not reload it. Editing a plain, fixed-name ConfigMap changes the
projected file inside the running container and changes nothing else: the process keeps
running the config it parsed at startup, `kubectl get configmap` confirms the new
content, and the behaviour is still the old one. `configMapGenerator` appends a content
hash to the name, so new content is a new ConfigMap name, a changed pod spec and a
rollout. The rollout is the reload.

## What is checked, and what is not

The manifests render and validate with the same tool versions CI uses, and CI will now
run the same checks — `.github/workflows/ci.yml` gained a `Render and validate the
collector` step, and its floating-tag guard was extended to cover this render as well.
That workflow has not itself been run; the commands below were executed locally, by
hand, with the versions named.

| Check | Tool | Result |
|---|---|---|
| Renders | `kustomize v5.4.3` and `kubectl v1.31.0` (kustomize `v5.4.2`) | byte-identical output from both |
| Schema | `kubeconform v0.6.7 -strict -kubernetes-version 1.31.0` | 4 resources, 0 invalid, 0 errors |
| Hash is load-bearing | edit `send_batch_size` in the config, re-render | `otel-collector-config-kk5h24gkgm` → `otel-collector-config-gggg8k2dk5` |
| No floating tag | CI's own `grep -nE '^\s*image: .*:(latest\|main\|master)$'` | clean |
| Config is well-formed YAML | `yaml.safe_load` on `otel-collector-config.yaml` | parses; `traces`, `metrics` and `logs` each run `[memory_limiter, batch]` into `otlphttp/dynatrace` |

That is the whole of the evidence, and it is narrower than it looks.
**Nothing here has been applied to a cluster, and the collector binary has never been
started with this configuration.** No container runtime was available in the
environment these manifests were written in, so `otelcol-contrib validate` was not run.
`kubeconform` validates the Kubernetes objects; the collector config travels inside a
ConfigMap as an opaque string, so a typo in `otlphttp/dynatrace` would render, validate
and pass CI, and would first be visible as a pod in `CrashLoopBackOff`.

Open questions, each of which is a thing to check on first contact with a cluster
rather than a thing that is known:

- Whether `runAsNonRoot: true` is satisfied by `otel/opentelemetry-collector-contrib:0.115.1`.
  It requires the image to declare a numeric non-root `USER`; if it does not, the
  kubelet refuses to start the container and the fix is a `runAsUser` that
  `restricted-v2` would then reject. This was not verified against the image.
- Whether `readOnlyRootFilesystem: true` is workable for this image. No exporter or
  extension configured here writes to disk, and a `/tmp` `emptyDir` is mounted as
  insurance, but that is reasoning rather than an observation.
- Whether `health_check` serves `path: /` on `0.115.1`. It is set explicitly rather
  than relying on a default, but the response has not been seen.
- Whether the application emits OTLP **logs** at all. `application.yml` configures
  `management.opentelemetry.tracing.export.otlp.endpoint` and
  `management.otlp.metrics.export.url` explicitly and sets no `management.otlp.logging`
  key, and the deployed configuration ships logs as ECS JSON on stdout. The `logs`
  pipeline exists so a log payload is not refused, not because one is known to arrive.
- The exact Dynatrace token scopes. `openTelemetryTrace.ingest`, `metrics.ingest` and
  `logs.ingest` are the ones the pipelines imply; the authoritative names come from the
  tenant's token page.

## Not done yet

These are gaps, not decisions. The section below this one lists what is absent on
purpose.

1. **The image is pinned to a tag, not a digest.** `0.115.1` matches the collector in
   `deploy/observability/docker-compose.yml`, so the lab and the cluster run the same
   version, and CI rejects a floating tag. A tag is still a name upstream could in
   principle move; a digest is the only pin that cannot be.
2. **No `NetworkPolicy`.** The Service is `ClusterIP`, so the receiver is not reachable
   from outside the cluster, but any pod in the cluster can post telemetry to it. A
   policy admitting only the application's pods is the next layer and is not here.
3. **No alert on export failure.** `alerts.yml` has six rules about the application and
   none about the collector. The collector's own `otelcol_exporter_send_failed_*`
   counters are the signal that the token is wrong or the tenant is rejecting data, and
   nothing currently scrapes or alerts on them — which means the failure mode this
   directory most needs to detect is the one it is blind to.
4. **`COREBANK_OTLP_ENABLED` is still `"false"`.** Deliberate ordering, as above, but
   until it is flipped this collector receives nothing and the gap between what the
   application can emit and what the deployment collects is unchanged.
5. **One replica, no PDB.** A collector restart or a node drain loses whatever is
   queued in memory. Nothing money depends on, but it is a real hole in telemetry
   continuity, and unlike the application there is no `PodDisruptionBudget` here.

## Deliberately not here

- **A `Route` or `Ingress`.** An OTLP receiver does not authenticate its callers.
  Published externally it would accept spans, metrics and logs from anyone who found
  the hostname and forward all of it to Dynatrace under this tenant's token — billed to
  the tenant and mixed into the same service names as the real telemetry. The only
  client is the application, in the same namespace.
- **Prometheus, Tempo and Grafana.** A self-hosted stack is a second system to operate
  and secure. The compose stack in the parent directory exists to make the mechanism
  visible locally; in a cluster the managed APM is the answer.
- **`resource_to_telemetry_conversion`.** It exists to copy resource attributes onto
  metrics as Prometheus labels, because the exposition format has nowhere else to put
  them. OTLP carries them natively, so `service.version`, `service.namespace` and
  `deployment.environment` arrive intact without it.
- **A file-backed sending queue.** `file_storage` would mean a volume, a writable
  filesystem and a stateful pod. Telemetry is not the ledger; dropping a queue on
  restart is an acceptable loss in a way that dropping a journal never is.
- **`cumulativetodelta`.** The application already exports delta temporality
  (`management.otlp.metrics.export.aggregation-temporality`), so the collector has no
  per-series state to keep and can be scaled or restarted freely. Doing the conversion
  here instead would make the collector stateful and cap it at one replica for a real
  reason rather than a quota one.
