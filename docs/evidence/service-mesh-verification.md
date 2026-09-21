# Service Mesh Verification

**Upstream Istio runtime verified on Kind.** **Red Hat OpenShift Service Mesh was not run**: it cannot be installed with the
access the Developer Sandbox gives, and this document records the exact denials. Nothing below is OpenShift Service
Mesh experience.

Two versions of the same CoreBank application ran behind one Service on a local Kind cluster with upstream Istio
1.31.0: weighted 90/10 routing, mutual TLS, telemetry read from the sidecars, and a rollback to 100% v1 under load.
Times are UTC. The raw recordings (per-request results, the client sidecar's access logs, per-pod counter
snapshots) are kept locally and are not committed; the numbers here are derived from them.

This is a **single-host lab result**. It shows that the committed overlay (`deploy/service-mesh/`) works with Istio
as designed. It is not a production measurement and no figure here is an SLO.

## Summary

| Claim | Status | Basis |
|---|---|---|
| Red Hat OpenShift Service Mesh installed on the Developer Sandbox | **no, not possible with this access** | subscription, operator group and cluster-scoped creates all denied (below) |
| Upstream Istio 1.31.0 installed on Kind (Kubernetes v1.36.4) | **verified** | `istioctl install` 15 s, `istiod` Running, control plane 1.31.0 |
| v1 and v2 of the same application behind one Service, each with a sidecar | **verified** | 4 application pods `2/2`, subsets matched by the `version` label |
| Weighted routing follows the configured weights | **verified, with a caveat** | 50/50 → 49.8 %; 90/10 → 8.7 % to v2, a consistent shortfall that is not explained |
| Three independent counters agree on where each request went | **verified** | client sidecar access log = application counters = destination sidecar counters |
| Mutual TLS between the pods | **verified** | every server-side request `mutual_tls`; a client without a sidecar is reset; `istioctl` reports STRICT |
| Telemetry path | **partly** | access log and `istio_requests_total` read from the sidecars; no Prometheus, no traces, nothing sent to Dynatrace |
| Rollback to 100% v1 under load | **verified** | 1,064 requests after the change: all v1, 0 to v2, 0 errors |
| Money stayed correct through all of it | **verified** | 7,000 transfers, 7,000 journals, invariants hold |
| Ambient mode, ingress gateway, external traffic into the mesh, OpenShift | **not tested** | |

## OpenShift Service Mesh on the Developer Sandbox

The user is project-scoped on a shared cluster. The catalogue is visible; installing from it is not possible.

**Visible.** `oc get packagemanifests` lists `servicemeshoperator`, `servicemeshoperator3` and `kiali-ossm`
(Red Hat Operators) and `sailoperator` (Community Operators). Both Red Hat service mesh packages declare only the
`AllNamespaces` install mode:

| Package | `stable` channel | OwnNamespace / SingleNamespace / MultiNamespace / AllNamespaces |
|---|---|---|
| `servicemeshoperator` | `servicemeshoperator.v2.6.17` | False / False / False / **True** |
| `servicemeshoperator3` | `servicemeshoperator3.v3.4.2` | False / False / False / **True** |

**Denied**, each observed (`oc auth can-i`, or the API server's own answer):

| Action | Result |
|---|---|
| `create` / `list` `subscriptions.operators.coreos.com` in `openshift-operators` | **no** / **no** |
| Server-side dry-run of a `Subscription` for `servicemeshoperator3` in `openshift-operators` | `Forbidden: subscriptions.operators.coreos.com "servicemeshoperator3" is forbidden: User "<user>" cannot get resource "subscriptions" in API group "operators.coreos.com" in the namespace "openshift-operators"` |
| `create operatorgroups.operators.coreos.com` | **no** |
| `create customresourcedefinitions`, `clusterroles`, `mutatingwebhookconfigurations` | **no** each |
| `oc get crd` | `Forbidden: … cannot list resource "customresourcedefinitions" in API group "apiextensions.k8s.io" at the cluster scope` |
| `oc get packagemanifests -n openshift-marketplace` | `Forbidden: … cannot list resource "packagemanifests" … in the namespace "openshift-marketplace"` |

**No mesh already present.** `oc api-resources` serves no `networking.istio.io`, `security.istio.io`,
`maistra.io` or `sailoperator.io` API group, so there is no administrator-installed mesh to join.

**What was not tried, and why.** A `Subscription` object in the user's own project is accepted by a server-side
dry-run, and it was **not created**. Both operators support only `AllNamespaces`, which is installed into
`openshift-operators`, and installing one also needs an `OperatorGroup` and cluster-scoped objects (CRDs, cluster
roles, webhooks) that this user cannot create. That is reasoning from the package metadata and the observed
permissions, not an observed failure. No permission was bypassed or worked around.

**Consequence.** The traffic-management experiment was run on upstream Istio on Kind. It proves Istio behaviour, not
OpenShift Service Mesh's operator-managed installation or lifecycle.

## Environment

| | |
|---|---|
| Cluster | Kind v0.33.0, Kubernetes v1.36.4, 1 control-plane + 3 workers (the cluster used for `kubernetes-runtime-verification.md`) |
| Git SHA | application images built from `a500aac382ac…` (v1) and `2746b2b7deef531b1a3c4f238d5f310e0e2910c3` (v2); the manifests are `deploy/service-mesh/` as committed in `faaf813` |
| Istio | 1.31.0 (released 2026-08-31), `istioctl` `istioctl-1.31.0-win-amd64.zip` from the official `istio/istio` GitHub release, SHA-256 verified against the published `.sha256` |
| Kubernetes support | the 1.31 support table lists Kubernetes 1.36 as tested; the install and every experiment worked on it |
| Install | `istioctl install --set profile=minimal --set meshConfig.accessLogFile=/dev/stdout --set meshConfig.defaultConfig.holdApplicationUntilProxyStarts=true`; 15 s; only `istiod` (no gateways); `istioctl x precheck` clean beforehand |
| Namespace | `corebank-mesh` (`istio-injection=enabled`), separate from `corebank` |
| v1 | `sha-a500aac382ac…` (the base's pin), 2 replicas |
| v2 | `sha-2746b2b7deef…`, 2 replicas; the two images have no Flyway migration between them |
| Database | the base's PostgreSQL 16 StatefulSet, not injected, shared by both versions |
| Client | in-cluster Jobs in `lab-clients-mesh` (with a sidecar), 4 workers, 0.1 s interval, ~10 requests/s |

## API choice

| Object | apiVersion | On the cluster's CRDs |
|---|---|---|
| `VirtualService`, `DestinationRule` | `networking.istio.io/v1` | served and storage version |
| `PeerAuthentication` | `security.istio.io/v1` | served and storage version |
| `Telemetry` | `telemetry.istio.io/v1` | served, but `v1alpha1` is the storage version. **Not used here**: the overlay defines no `Telemetry` object, and the telemetry below was read from the sidecars themselves |

Istio 1.31's documentation offers weighted routing both through these and through Gateway API `HTTPRoute`, and says it
intends to make Gateway API the default in time. The Istio APIs were chosen because they are GA and documented for
1.31, need no extra CRDs, and one Service with subsets is the smaller change than one Service per version. Gateway API
is a supported alternative, not a rejected one.

## Deployment

`kubectl kustomize deploy/service-mesh | kubectl apply -f -`, with the local Secret applied first.

- Both Deployments rolled out **76 s** after apply; all application pods `2/2`.
- **Every application pod restarted once at start-up** (4 of 4, exit code 1). The previous log shows Flyway failing to
  connect: `UnknownHostException: corebank-postgres`, because the headless Service has no endpoint until PostgreSQL is
  Ready. This is the cold-start behaviour already recorded in `kubernetes-runtime-verification.md`, not a mesh effect;
  `holdApplicationUntilProxyStarts` was set and no proxy-related failure was seen. No further restarts in 24 minutes.
- Sidecar cost at idle: `istio-proxy` 29 – 30 Mi and ~2m CPU per pod, `istiod` 43 Mi. Under load it was not measured
  separately.
- The demo data was seeded with `POST /api/demo/setup` from a pod with a sidecar (`200`).

## Weighted routing

The client sidecar's access log names the subset each request was sent to (`outbound|80|v2|corebank-api…`). Two
counters that do not depend on it cross-check it: each application's own
`http_server_requests_seconds_count{uri="/api/transfers/internal", status="200"}`, and Istio's `istio_requests_total`
(reporter `destination`) read from each application pod's sidecar.

| Run | Weights | Requests | To v1 | To v2 | v2 share |
|---|---|---|---|---|---|
| A | 90 / 10 | 1,000 | 918 | 82 | 8.2 % |
| B | 90 / 10 | 2,000 | 1,832 | 168 | 8.4 % |
| C | 90 / 10 | 1,000 | 901 | 99 | 9.9 % |
| D (50/50 applied) | 50 / 50 | 1,000 | 502 | 498 | 49.8 % |
| E, before the rollback | 90 / 10 | 936 | 854 | 82 | 8.8 % |

- **The three sources agree exactly** in runs A, B and E: A: 918 / 82 in the access log and in the application
  counters (454 + 464 on the two v1 pods, 37 + 45 on the v2 pods), and 919 / 82 at the destination sidecars (the extra
  one is the seed request); B: 1,832 / 168 in all three; E: 1,918 / 82 for the whole run in all three.
- **50/50 was exact** (498 vs 500 expected, ±16 at one standard deviation), so weights are honoured and a weight change
  reaches the sidecars: it was applied with `kubectl apply` and the next run followed it.
- **90/10 was consistently a little low.** Across the four 90/10 samples (4,936 requests) v2 received **431 (8.73 %)**
  where 494 ± 21 was expected. Each run alone is within ~2.4σ, but all four were below 10 %, and in aggregate that is
  about −3σ.

  **Interpretation.** 8.73 % is inside the band a canary is normally operated with (roughly 8 – 12 % for a 10 % target
  at this sample size), and it is not a routing fault: every request landed where three independent counters say it
  did, and the 50/50 run was exact, so the weights are honoured and reach the sidecars. What the aggregate does say is
  that the deviation is systematic rather than noise. The likely mechanism was **not tested**: Envoy distributes
  weighted traffic per connection, and this client is four workers in a closed loop, so a few long-lived connections
  can bias the split in a way that averages out at 50/50 but shows up at 90/10. The honest reading is that the weights
  are directionally correct and the split is approximate; a canary that must receive exactly 10 % of *requests* should
  measure it rather than assume it. The weights were **not** adjusted to make the number look closer to 10 %.
- Client-observed latency was similar in the runs where it was recorded (A, B, E: p95 78 – 86 ms, ~10 requests/s), and every
  request was `200`; this is a different
  topology from the non-mesh Kind runs (4 pods here, no HPA) and is **not** a measurement of mesh overhead.
- Run C was meant to be the 50/50 run: the first attempt to change the weights failed on quoting (PowerShell removed
  the JSON quotes from `kubectl patch`), so it ran at 90/10; the weights were then applied with `kubectl apply` for
  run D.

## Mutual TLS

`PeerAuthentication` mode `STRICT` for the namespace, and `ISTIO_MUTUAL` in the `DestinationRule`.

| Check | Result |
|---|---|
| Server-side `istio_requests_total` over every request of runs A, B and E | `connection_security_policy="mutual_tls"` on all of them; source and destination principals are SPIFFE identities (`spiffe://cluster.local/ns/lab-clients-mesh/sa/default` → `…/ns/corebank-mesh/sa/default`) |
| The same request (`GET /api/transfers/internal`) from a pod **without** a sidecar (`lab-clients`) | `curl: (56) Recv failure: Connection reset by peer`, HTTP `000` |
| The same request from a pod **with** a sidecar | `405`: the application answered (the URL is a `POST` endpoint) |
| `istioctl x describe pod` | `Effective PeerAuthentication: Workload mTLS mode: STRICT`, applied by `default.corebank-mesh`; DestinationRule TLS mode `ISTIO_MUTUAL` |

**The application-to-database hop is not covered.** The database is annotated out of injection, so the sidecar sends
plain TCP to it. That was a decision (see the README), not an omission, and it means traffic to the ledger's database
is not encrypted by the mesh.

## Telemetry path

What was exercised: each sidecar's access log, and `istio_requests_total` read from the proxy (`pilot-agent request GET
stats/prometheus`). What was not: no Prometheus scrape, no Kiali, no traces, and **nothing was sent to Dynatrace**. The
mesh's data plane emits the telemetry; a backend for it was not part of this run.

## Rollback to 100% v1

Run E: 2,000 transfers with the committed 90/10 route; at 10:46:05.406 `kubectl apply -f
deploy/service-mesh/rollback-to-v1.yaml` (returned in 0.15 s) while the load continued.

| | |
|---|---|
| Requests started before the change | 936, of which 82 to v2 |
| The last request sent to v2 | started 10:46:04.332, **1.07 s before** the change |
| Requests started at or after the change | 1,064: **1,064 to v1, 0 to v2**, 0 non-`200` |
| Requests started ≥ 2 s after the change | 1,044, 0 to v2 |
| Whole run | 2,000 × `200`, 0 transport errors, 2,000 distinct journals, p95 81 ms |
| Server-side | v1 pods 968 + 950 = 1,918, v2 pods 34 + 48 = 82, matching the client log |

The propagation delay to the sidecar is below what this test can resolve: with ~10 requests/s the next request started
about 100 ms after the change and already went to v1. In-flight requests to v2 were not observed failing. The v2 pods
were left running after the rollback.

## Financial invariants

Read-only checks in the mesh namespace's database after all runs (`financial-invariants.sql`): 4 accounts, posted total =
available total = 260,000,000 minor units, 0 negative balances, 0 unbalanced journals, 0 duplicate correlation ids,
**7,000 committed journals = 7,000 `SUCCEEDED` idempotency claims**, 0 stale claims. That is exactly the
1,000 + 2,000 + 1,000 + 1,000 + 2,000 requests sent, so a mix of two versions on one database, and a mid-run route
change, produced neither a lost nor a doubled transfer.

## Limitations

- **Upstream Istio on Kind, not OpenShift Service Mesh.** The operator, its lifecycle and Red Hat's packaging were not
  run. The APIs used exist in both, but that was not tested.
- **One host, one cluster, four pods**, a closed-loop client at ~10 requests/s. No failure injection was done in the
  mesh (no fault-injection, no outlier detection, no authorization policy, no v2 defect).
- **The 90/10 split ran about 9/91** (8.73 % over 4,936 requests) for a reason that was not found.
- **The two versions share one database**; that is safe only while their Flyway histories match.
- **No ingress gateway and no external traffic.** Traffic entered the mesh from a client pod with a sidecar.
- **Sidecar mode only**, no ambient mode.
- **Telemetry stopped at the sidecars.** No metrics backend, no tracing, no Dynatrace.
- **The application-to-database connection is outside the mesh and unencrypted by it.**
- **The Istio objects get no schema validation.** No catalogue `kubeconform` ships with covers them. CI skips
  those kinds and runs `deploy/service-mesh/check-mesh-routing.sh`, which asserts that the objects agree with
  each other; it was tested against six mutated renders (a route to an undefined subset, a subset matching no
  pod, weights summing to 105, both Deployments on one version, the database injected) and fails on each. That
  is narrower than a schema check, and only a cluster proves Istio accepts the objects.
