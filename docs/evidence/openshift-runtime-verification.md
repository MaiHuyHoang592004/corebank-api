# OpenShift Runtime Verification

CoreBank was deployed to a Red Hat Developer Sandbox project with the `deploy/openshift` overlay and
exercised through its public Route. Everything below was executed and observed; nothing is inferred from
configuration. Times are UTC. The raw recordings (pod / ReplicaSet / EndpointSlice captures, per-request
results, HPA and database samples) are kept locally and are not committed; the numbers here are derived from
them. The project name, the account name and the Route host are deliberately written as placeholders.

This is a **lab result on a free, shared Sandbox**. It shows that the committed overlay, with two fixes
found here, runs on OpenShift. It is not a production measurement and no figure here is an SLO. Client-side
latencies include the network path from a workstation to the cluster and say little about the application
(see [Route and HTTPS](#route-and-https)).

## Summary

| Claim | Status | Basis |
|---|---|---|
| The overlay as committed applies to a Sandbox project | **no** | `Forbidden` on the Namespace object and on the hard-coded `namespace: corebank` (fixed, see Fixes) |
| The fixed overlay is admitted under `restricted-v2` with an arbitrary UID, no `anyuid`, no SCC change | **verified** | pod annotation `openshift.io/scc=restricted-v2`, runtime UID from the project range |
| PostgreSQL from the catalog template under an arbitrary UID | **verified** | PostgreSQL 15.8, `restricted-v2`, PVC bound |
| Startup, Flyway, readiness / liveness gating on the database | **verified** | 26/26 migrations, pods Ready, database restart takes them out of the Service and back |
| HTTPS Route, HTTP redirect, actuator not published | **verified** | 200 / 302 / 404 |
| Real money flow through the Route | **verified** | thousands of transfers, invariants hold |
| Pod replacement, rolling update, failed rollout and rollback keep the Route serving | **verified** | 450 + 1500 + 800 requests, 0 non-200 |
| HPA scale-out to 6 replicas on the Sandbox quota | **verified after a fix** | first attempt left the 6th pod in `CrashLoopBackOff` (database connection ceiling) |
| Persistence across a database pod replacement | **verified** | 2000 journals written before the restart are present after it |
| Dynatrace on OpenShift, Service Mesh, DynaKube | **not in this document** | see `dynatrace-apm-verification.md` and `service-mesh-verification.md` |

## Environment

| | |
|---|---|
| Platform | Red Hat OpenShift Developer Sandbox (shared cluster, ROSA on AWS) |
| OpenShift / Kubernetes | server 4.21.30 / v1.34.9 |
| Client | `oc` 4.22.13 (binary checksum verified against the official `sha256sum.txt`), bundled Kustomize v5.7.1 |
| Login | `oc login --web` (browser OAuth with PKCE, completed by the account owner in the browser). `oc` stored the token in its own kubeconfig as it always does; the automation never read, printed, copied or logged it, and no token was placed in a file, a command line or this repository |
| Permissions | project-scoped user; no cluster-admin was needed or used |
| Traffic source | a workstation on the public internet, through the Route only |

## Date

2026-09-21, from the first resource created (09:19 UTC) to the last event recorded (10:15 UTC).

## Git SHA

- Application image: built from `2746b2b7deef531b1a3c4f238d5f310e0e2910c3` (CI green).
- Repository state applied: branch `hoang/corebank-platform-verification-15a3d5` at `ff61c22`, the overlay
  rendered with the image tag substituted at render time (the tracked `kustomization.yaml` was not edited to
  run the tests). The two fix commits described under [Fixes](#fixes) followed.

## OpenShift version

Server 4.21.30 (Kubernetes v1.34.9), as reported by `oc version`. The Sandbox cluster is shared and is upgraded
by Red Hat; this result belongs to that version on that date.

## Project

One pre-existing project, `<sandbox-project>`, was used. A project named `corebank` was **not** created
(the Sandbox user cannot create projects), which is what exposed the first deviation below.

Permissions observed rather than assumed: `oc auth can-i` answers *yes* for creating Deployments, Routes, HPAs,
PDBs, Secrets, PVCs and RoleBindings inside the project. Reading or creating a namespace is refused (the
`Forbidden` error below), and `oc auth can-i create subscriptions.operators.coreos.com -n openshift-operators`
answers *no*. No cluster-admin was needed or used.

## Quota

| Quota | Hard | Used at the end (6 replicas + collector + database) |
|---|---|---|
| `requests.cpu` (`compute-deploy`) | 3 | 1560m |
| `requests.memory` | 30Gi | 3712Mi |
| `limits.cpu` / `limits.memory` | 30 / 30Gi | 7500m / 7Gi |
| PVCs / `requests.storage` | 10 / 80Gi | 1 / 1Gi |
| `compute-build` | 3 CPU, 14Gi | 0 |

LimitRange for containers: default request 10m CPU / 64Mi, default limit 1 CPU / 1000Mi. The Deployment sets its
own requests and limits, and the database template sets only a 512Mi memory limit.

**Arithmetic done before applying.** At the HPA maximum (6 replicas) plus one surge pod the application requests
7 × 250m = 1750m CPU. The collector and the database add about 60m (measured: 1560m used with 6 replicas
running). That is about 1.81 of the 3 CPU quota, and memory is far below 30Gi, so it fits: no replica-count, HPA
or PDB patch and no Sandbox-specific overlay was needed. The peak measured during scale-out was 1510m – 1560m
(6 replicas), and no quota-exceeded event occurred. A seventh pod during a rollout at the HPA maximum was not
exercised; its fit is the arithmetic above.

## Application image

| | |
|---|---|
| Repository pin in `kustomization.yaml` | `sha-a500aac382ac110b23948b844e8c581c7580cacc` (used for the first apply, the Route checks and the pod-replacement run) |
| Image used afterwards | `ghcr.io/…/corebank-api:sha-2746b2b7deef531b1a3c4f238d5f310e0e2910c3`, digest `sha256:30fea6fafd50…` (the pin cannot produce JDBC spans; see `dynatrace-apm-verification.md`) |
| Registry | public GHCR, pulled by the nodes without a pull secret (466 MB, 5.3 s) |

## SCC and admission

- Every application pod is admitted by `restricted-v2` (annotation `openshift.io/scc`). So are the database and
  the collector. Across the whole project no running pod used another SCC. No `anyuid`, no SCC created or
  bound, no `RoleBinding` to an SCC.
- The Deployment needed **no patch**: it sets `runAsNonRoot` without naming a UID, `readOnlyRootFilesystem`,
  `allowPrivilegeEscalation: false` and drops all capabilities, which is what `restricted-v2` requires.
- What did **not** pass admission was the repository's own overlay (the Namespace object), for a permission
  reason, not an SCC reason. See [Observed failures](#observed-failures).

## Runtime UID

Application pod: `uid=1003610000 gid=0 groups=0,1003610000` (`oc exec … id`), a UID from the range the project was
assigned. No manifest names it: the Deployment sets only `runAsNonRoot`, and the SCC admission filled
`runAsUser` into the pod spec. The database and the collector run as the same UID. The collector image's own user is 10001; OpenShift overrode it, and it still
ran with `readOnlyRootFilesystem` and the `/tmp` `emptyDir`.

## Database provisioning

- The overlay deletes the in-cluster StatefulSet, so the database comes from the Developer Catalog template
  `postgresql-persistent`, whose image is built for arbitrary UIDs. Result: PostgreSQL **15.8**, DeploymentConfig
  `postgresql`, Service `postgresql`, Secret `postgresql`, PVC `postgresql` (`gp3`, 1Gi, `Bound`).
- **The template's default is PostgreSQL 10** (`10-el8`, end of life). `POSTGRESQL_VERSION=15-el9` is the newest
  tag in the Sandbox catalog (its tags run from `10` to `15-el9`). The application is tested against 16, so
  this is one major version older than the tested one; the Flyway migrations and the transfer path ran on 15.8
  without a difference observed.
- Database name, user and password match the local, git-ignored Secret: checked by comparing the values for
  equality without printing them, and by the application connecting. The password is not in the repository.
- The template creates a `DeploymentConfig`, deprecated since OpenShift 4.14 (`oc` warns on every call). It
  works; it is another reason to prefer a managed database.
- `max_connections` is 100 in this image and the template has no parameter for it. That was a defect; see
  [Observed failures](#observed-failures) and [Fixes](#fixes).

## Startup

- First deployment, empty node caches: 3/3 Ready at +48.9 s from apply (includes the image pull), 0 restarts.
- Flyway on the fresh database: **26 of 26 migrations applied, all `success`** (latest version 28; the version
  numbers are not contiguous). Later pods log "Successfully validated 26 migrations" and nothing to apply.
- `Started CorebankApiApplication in 18.6 – 20.8 s` on the pods inspected at the end; a pod scheduled onto a
  node with the image cached was Ready 26 s after scheduling.
- Readiness and liveness use the private management port 9091 and gate on the database: the database restart
  described under [Fixes](#fixes) took all three pods out of the Service (0/3 ready) and they came back by
  themselves once it returned.

## Route and HTTPS

| Check | Result |
|---|---|
| Route | edge TLS, `insecureEdgeTerminationPolicy: Redirect`, `haproxy.router.openshift.io/timeout: 60s`, target port `http`, admitted |
| `https://<route-host>/` | `200`, certificate verified (issuer Let's Encrypt, wildcard for the cluster's application domain, valid to 2026-11-12) |
| `http://<route-host>/` | `302` to the `https://` URL |
| Actuator through the Route, authenticated as an admin user | `/actuator/health`, `/actuator/prometheus`, `/actuator/info` → **404**; the management port 9091 is not exposed by the Route or the Service, and is reachable only inside the cluster |
| Dashboard | served through the Route |
| Money flow | `POST /api/transfers/internal` through the Route: 200 with a journal id; exact-body replays return the same journal; thousands of transfers below |

**Latency caveat.** The traffic generator runs 4 workers in a closed loop through the public internet to a
US-hosted cluster: 4.4 – 5.0 requests/s with p95 ≈ 0.85 – 1.0 s. The application's own server span, read in
Dynatrace for the same requests, was 100 – 140 ms. The difference is TLS, the router and the network path, not
the application, and none of these client latencies is an application figure. They are comparable to each other
(same path) and to nothing else.

**In a browser.** The Route was opened in the desktop app's built-in browser pane (no login involved). `/` loaded over
HTTPS in a secure context with the title "CoreBank — Fintech Backend Portfolio"; its "Open Live Dashboard" link goes to
`/dashboard/`, which loaded as "FinLedger Lab — Fintech Backend Live Demo". The four requests the browser made
(`/`, `/dashboard/index.html`, `styles.css`, `app.js`) were all `200`, and the console had no errors. The dashboard's
API calls need the showcase credentials and were **not** exercised in the browser (no password was typed into a page);
the money flow was driven with an HTTP client, above.

## Pod replacement

`oc delete pod <one of 3>` while 450 transfers ran through the Route (4 workers).

| | |
|---|---|
| Replacement Ready, all 3 pods Ready without the victim | **+29.7 s** |
| Requests | 450, **all 200**, 0 transport errors, 450 distinct journals |
| Ready endpoints at the lowest sample | 2 of 3 (the PDB `minAvailable: 2` was respected) |
| Restarts | 0 |

Identities and timeline (captures about every 5 s, so each time is the first capture that showed the event):

| | Pod | UID (first 8) | Seen |
|---|---|---|---|
| Deleted | `corebank-api-856fd5c9d-9c2jc` | `90f32a2e` | `oc delete pod` issued 09:37:23.5; `Terminating` in the first capture after (+5.2 s); gone by +8.3 s |
| Replacement | `corebank-api-856fd5c9d-89x94` | `8cbfb99a` | created in the same capture (+5.2 s); first `Ready` at +29.7 s |
| Untouched | `…-m2crd`, `…-hd76k` | `3dca9e3b`, `c77adfa4` | `Ready` throughout |

All three pods ran the same image ID (`sha256:30fea6fafd50…`) before and after.

The slowest requests (≈3.2 s, 2.9 s) started at about +29 – 31 s, when the replacement joined and its JVM took its
first requests. The recorder sampled about every 5 s on this platform, so "lowest sample" is a coarse bound.

## Rolling update

A change of image tag on the Deployment (repository pin → `sha-2746b2b…`) while 1500 transfers ran.

| | |
|---|---|
| Duration, apply → all 3 new pods Ready and old ones gone | **133.4 s** |
| Available replicas at the lowest sample | **3** (`maxUnavailable: 0`, `maxSurge: 1`, `minReadySeconds: 15`) |
| Requests | 1500, **all 200**, 0 transport errors, 1500 distinct journals |
| Pods listed at most | 5 (3 old/new plus one surge plus a terminating one) |
| ReplicaSets | before: `corebank-api-65cdc7754b`, tag `sha-a500aac…`, 4 desired / 4 ready (the HPA had scaled to 4 before the update began), image ID `sha256:fd7537176097…`; after: `corebank-api-856fd5c9d`, tag `sha-2746b2b…`, 3 / 3, image ID `sha256:30fea6fafd50…`, and the old ReplicaSet at 0 |

## Rollback

A Deployment image set to a tag that does not exist (`sha-0000…0`), applied under 800 transfers, then
`oc rollout undo`.

| | |
|---|---|
| Failure signature | the new pod `ErrImagePull` → `ImagePullBackOff`; `oc rollout status` timed out with "1 out of 3 new replicas have been updated"; the 3 old pods stayed Ready |
| `oc rollout undo` | returned in 2.0 s |
| Back to 3/3 Ready on the previous ReplicaSet | 4.6 s after the undo. The undo was issued 85 s after the faulty apply, a deliberate wait to capture the failure; 89.6 s in total |
| Requests | 800, **all 200**, 0 transport errors; minimum available replicas 3 |

The rollout controller never removed a healthy pod, because `maxUnavailable: 0` blocks it until the new pod is
Ready. That is the mechanism that kept the Route serving, and the rollback only had to delete the bad pod.

## HPA, PDB and quota behaviour

HPA: min 3, max 6, 70 % CPU of a 250m request, 300 s scale-down stabilisation; PDB `minAvailable: 2`.

- Scale-out under 2000 requests at concurrency 20 (≈22 req/s through the Route): desired replicas reached 6
  about 33 s after the load started, and 6 pods were Ready about 20 s later. CPU at peak was 213 % – 243 % of
  the request.
- Quota peak `requests.cpu` **1510m – 1560m of 3** (6 replicas plus the collector); no quota-exceeded or `FailedCreate` event.
- Scale-in happens on its own after the 300 s stabilisation window, as configured.
- Sandbox-specific: nothing had to be lowered to fit. This differs from a small local cluster in one way that
  mattered: the quota is per project, so the collector and the database share it.

**The first scale-out did not fully work.** See the next section: it reached 6 desired and 6 pods, but the
sixth stayed not Ready because of the database, and the same test was repeated after the fix.

## Observed failures

### 1. The overlay is refused as written (permissions, not SCC)

`kubectl kustomize deploy/openshift | oc apply --dry-run=server -f -`, unchanged overlay:

```
Error from server (Forbidden): … namespaces "corebank" is forbidden: User "<user>" cannot get resource
"namespaces" in API group "" in the namespace "corebank"
```

Every other object is refused for the same reason while the overlay says `namespace: corebank` and the project
has another name. With only the project name substituted, everything except the `Namespace` object is
accepted, and the `Namespace` object is still `Forbidden`. It carries one label and nothing OpenShift needs.

### 2. The 6th replica crash-loops: the catalog database allows 100 connections

The first HPA test reached 6 desired and 6 existing pods, but only 5 were ever Ready (every sample over 150 s).
The sixth was in `CrashLoopBackOff`, 5 restarts, exit code 1. Its previous log:

```
Exception encountered during context initialization … flywayInitializer …
FATAL: remaining connection slots are reserved for non-replication superuser connections
Application run failed
```

Database state at that moment: `max_connections = 100`, `superuser_reserved_connections = 3`, **98 client
backends (97 of them the application's, 97 idle)**, because each Hikari pool holds its 20 connections while idle:
5 pods × 20 = 100 already left no room for a sixth. It is the ceiling that the Kind StatefulSet had and that
`7ef9970` fixed there; the OpenShift overlay deletes that StatefulSet, so the fix never reached this path, and
`check-connection-budget.sh` can only print the requirement (170) for a render with no database.

The other five pods served all 2000 requests of that run with no error; the defect was masked. It was found
because the database itself refused an administrator's connection ("remaining connection slots are reserved"),
after the HPA had already scaled back and hidden the pod.

### 3. Smaller deviations

- The documented `oc new-app postgresql-persistent` defaults to PostgreSQL 10 (end of life).
- The template still creates a deprecated `DeploymentConfig`.
- `oc new-project corebank` does not apply on the Sandbox.
- An unrelated slip of the runner: the first collector Secret apply failed on non-ASCII characters in a comment
  of the local file (a PowerShell 5.1 encoding issue), leaving the collector pod in `CreateContainerConfigError`
  ("secret … not found") until the Secret existed. No effect on the application.

## Fixes

Two commits, kept separate from the evidence, each citing the runtime cause:

1. `550fb66` **fix(openshift): let the overlay apply into an existing Sandbox project.** Deletes the `Namespace`
   object in the overlay; documents rendering with the project name substituted
   (`sed … namespace: corebank → $(oc project -q)`) rather than editing the overlay per cluster; pins
   `POSTGRESQL_VERSION=15-el9` in the documented `oc new-app`. Verified: the fixed overlay with the project
   substituted is accepted by a server-side dry-run and applied.
2. `457d1c6` **docs(openshift): raise the catalog PostgreSQL max_connections to 200.**
   `oc set env dc/postgresql POSTGRESQL_MAX_CONNECTIONS=200`, before the application is deployed, with the
   arithmetic (8 pods × 20 + 10 = 170) and the observed failure.

**Verification of fix 2.** Applied while 700 transfers ran through the Route:

| | |
|---|---|
| `max_connections` | 100 → 200 |
| Database pod replaced (Recreate strategy) | old pod down, new pod Pending at +34 s, Running at +48 s, Ready at +58.5 s |
| Application pods | Ready 3/3 → 0/3 (+51.5 s) → 3/3 (+61.9 s); 0 restarts |
| Client | 699 × `200`, 1 × `503` (the router with no ready endpoint), 0 transport errors; 699 distinct journals |
| Requests during the outage | four requests **started** at +32 s were held 26.1 – 27.1 s and then completed with `200`; the closed-loop workers were blocked until +58.6 s. The Hikari `connectionTimeout` is 30 s, so the margin was about 3 s: a slightly longer outage would have produced `500`s |
| Data | 2000 journals written before the replacement (09:39 – 09:40) are all present after it; the PVC `postgresql` (`gp3`, 1Gi) was re-attached |

The database outage window (≈26 s) is inferred from the stalled requests and the database pod's phases; the
recorder sampled every 2 – 15 s, so the exact edges are not known. A related observation: the pods stayed
Ready for about 16 – 19 s after the database became unreachable (until +51.5 s) because the readiness check needs
consecutive failures; requests sent in that window waited for the database rather than failing fast.

**Repeat of the HPA test after the fix**, 2000 requests at concurrency 20:

| | Before (first run) | After |
|---|---|---|
| Pods Ready at peak | 5 of 6 | **6 of 6** |
| Restarts / `CrashLoopBackOff` | 5 restarts, 1 pod crash-looping | **0 / 0** |
| Database client connections | 98 of 100 | **121 of 200** |
| Requests | 2000 × `200` | 2000 × `200`, 0 transport errors, 2000 distinct journals |
| Rate / p95 (client) | 22.4 req/s / 968 ms | 21.95 req/s / 1079 ms |

Database memory at 121 clients: 304Mi of the template's 512Mi limit. The full 170 was not exercised, so the
512Mi limit at 170 connections is an extrapolation.

## Financial invariants

Read-only checks after all runs (`financial-invariants.sql`, in a `READ ONLY` transaction): 4 accounts, posted
total = available total = 260,000,000 minor units, 0 negative balances, **0 unbalanced journals**, 0 duplicate
correlation ids, 9,150 committed journals = 9,150 `SUCCEEDED` idempotency claims, 0 stale `IN_PROGRESS` claims.
This includes the runs interrupted by pod deletion, rollout, rollback and the database replacement.

## Remaining limitations

- **One free, shared cluster, one project, one region.** Quotas, the router and the node pool are the Sandbox's;
  the Sandbox may also hibernate workloads, which was not observed here.
- **Client latency is not application latency** (see [Route and HTTPS](#route-and-https)).
- **The browser check covered the static pages only** (the landing page and the dashboard shell). The authenticated
  API calls, the certificate chain, the redirect and the 404 on actuator paths were exercised with an HTTP client.
- **PostgreSQL 15.8**, one major version older than the tested 16, from a deprecated `DeploymentConfig`, single
  replica, no backups, no failover. This is a lab database.
- **The 512Mi database limit at 170 connections** was not exercised (304Mi at 121).
- **The repository pin still cannot produce JDBC spans**; the runs after the first used `sha-2746b2b…`, applied at
  render time. Bumping the pin is a release decision that was not made here.
- **Recorder granularity**: on OpenShift each capture of pods and EndpointSlices took about 5 s, coarser than on
  Kind (1.3 s). The "lowest observed" figures are lower bounds on granularity, not proofs of the minimum.
- **No custom domain or certificate**, no NetworkPolicy, no egress restrictions, no image signing.
- **Not exercised**: node loss, upgrades of OpenShift itself, cluster-autoscaler behaviour, a Sandbox
  hibernation cycle, `oc adm` operations (not permitted).
- OpenShift Service Mesh, the Dynatrace Operator (DynaKube) and the OpenTelemetry path on OpenShift are covered,
  or explicitly not covered, in their own documents.
