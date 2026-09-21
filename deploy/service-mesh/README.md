# Service mesh: canary, mTLS and rollback with upstream Istio

Two versions of the **same** CoreBank application behind one Service, an Istio sidecar in
front of each pod, weighted routing between the versions, mutual TLS between the pods, and a
one-command rollback to 100% v1.

**Status.** Verified on Kind with **upstream Istio 1.31.0** (Kubernetes v1.36.4). Not run on
OpenShift: the Red Hat OpenShift Service Mesh operator cannot be installed with the access the
Developer Sandbox gives (recorded in `docs/evidence/service-mesh-verification.md`), so nothing
here is OpenShift Service Mesh experience. The APIs below exist in both, but that was not
tested.

## What is here

```
kustomization.yaml         the whole experiment: ./v1 + ./v2 + the three Istio objects, namespace corebank-mesh
v1/kustomization.yaml      the base Deployment as v1, plus everything the two versions share
v2/kustomization.yaml      the base Deployment again as v2, different image pin, shared objects deleted
destination-rule.yaml      subsets v1 / v2 by the `version` label; ISTIO_MUTUAL
virtual-service.yaml       90 / 10, 60 s timeout, no hidden retries
peer-authentication.yaml   STRICT mTLS for the namespace
rollback-to-v1.yaml        100 / 0; applied on purpose, not part of the kustomization
```

The application manifests are not copied: both versions are the base's `deployment.yaml`
rendered through kustomize, so probes, security context and resources stay defined once.

## Why these APIs

Istio 1.31's documentation covers weighted routing two ways: its own
`networking.istio.io/v1` (`VirtualService` + `DestinationRule`) and the Kubernetes Gateway API
(`HTTPRoute` with weighted `backendRefs`), and says it intends to make Gateway API the default
over time. This directory uses the Istio APIs:

| Object | apiVersion | Served by Istio 1.31 (checked on the cluster's CRDs) |
|---|---|---|
| `VirtualService`, `DestinationRule` | `networking.istio.io/v1` | served and storage version |
| `PeerAuthentication` | `security.istio.io/v1` | served and storage version |

Reasons: they are GA and documented for 1.31, they need no extra CRDs on the cluster (Gateway API
would add its own), and one Service with subsets is the smaller change to this application than
one Service per version. Gateway API is a supported alternative, not a rejected one. This choice
is about traffic *inside* the mesh and is independent of the decision not to migrate the external
Ingress to Gateway API.

## Deploying it

Istio has to be installed first (the `minimal` profile is enough: `istiod`, no gateways):

```bash
istioctl install -y --set profile=minimal \
  --set meshConfig.accessLogFile=/dev/stdout \
  --set meshConfig.defaultConfig.holdApplicationUntilProxyStarts=true
```

`holdApplicationUntilProxyStarts` matters here: the application opens its database connection
and runs Flyway at startup, and with a sidecar in the pod that traffic goes through the proxy,
which must be ready first. The access log is what shows which subset served each request.

Then, on a Kind cluster set up as in `deploy/kubernetes/README.md`:

```bash
kubectl create namespace corebank-mesh
# the same local Secret as the base, in the new namespace
sed 's/^\(\s*\)namespace: corebank$/\1namespace: corebank-mesh/' deploy/kubernetes/secret.yaml | kubectl apply -f -
kubectl kustomize deploy/service-mesh | kubectl apply -f -
kubectl -n corebank-mesh rollout status deployment/corebank-api-v1
kubectl -n corebank-mesh rollout status deployment/corebank-api-v2
```

The database is new and empty; seed it with `POST /api/demo/setup` as `demo_ops`, from a pod
that has a sidecar.

Rollback and going forward again:

```bash
kubectl -n corebank-mesh apply -f deploy/service-mesh/rollback-to-v1.yaml     # 100% v1
kubectl -n corebank-mesh apply -f deploy/service-mesh/virtual-service.yaml    # 90 / 10
```

## Decisions worth knowing about

- **A separate namespace.** The experiment never touches `corebank`, where the Kubernetes and
  Dynatrace evidence was recorded.
- **Both versions share one database.** That is what a canary of this application looks like, and
  it is safe only while the two images have the same Flyway history. The pins in `../kubernetes`
  (v1) and `v2/kustomization.yaml` (v2) name releases with no migration between them.
- **No retries.** Istio retries connection failures and `503`s twice by default. For a money
  command that would hide failures from every client, trace and counter. `retries.attempts: 0`
  makes failures visible; a client can retry on purpose with the same idempotency key.
- **The database is outside the mesh.** Annotated `sidecar.istio.io/inject: "false"`: JDBC is
  opaque TCP and a proxy in front of the ledger's only database is a new failure mode. The
  application's sidecar still originates plain TCP to it, so **the application-to-database hop
  is not encrypted by the mesh.**
- **No HPA.** The fixed replica counts (2 + 2) are what make a weighted split readable. The HPA
  in the base targets the un-versioned Deployment by name.
- **The PodDisruptionBudget covers both versions** (`minAvailable: 2` over four pods).
- **The Deployment selectors gained `version`.** A selector is immutable, so this overlay cannot
  be applied over a namespace that already holds the base's `corebank-api` Deployment.
- **kustomize remembers original names.** A patch in the parent aimed at `corebank-api` also
  matches v2's renamed Deployment, which is why each version renames itself in its own directory.

## What is not covered

- **No Prometheus, Kiali or tracing backend.** The telemetry checked is what the sidecars emit
  themselves: the access log and `istio_requests_total`, read directly from the proxies. Nothing
  here sends mesh telemetry to Dynatrace.
- **No ingress gateway.** Traffic enters the mesh from a client pod that has a sidecar.
  Getting external traffic to the mesh (an Istio gateway, or the OpenShift Route) is not done.
- **Sidecar mode only.** Istio's ambient mode was not tried.
- **CI only renders the base and OpenShift overlays.** The Istio objects of this overlay were checked
  by applying them to a real API server on Kind, not by a schema validator in CI.
- **The split is approximate.** With weights 90/10 the sidecars sent about 8.7% of requests to v2
  over ~4,900 requests, a shortfall that was consistent across runs and is not explained. With 50/50 it
  was 49.8%. See the evidence document.
