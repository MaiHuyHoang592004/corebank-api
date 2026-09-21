# Kubernetes Runtime Verification

Everything below was executed on a local Kind cluster and observed; nothing is inferred from
configuration. Times are UTC. Raw recordings (pod / ReplicaSet / EndpointSlice captures every
~1.3 s, per-request results, probe samples, tunnel supervisor log) are kept locally and are not
committed; the numbers here are derived from them.

This is a **single-host lab** result. It shows that the committed manifests behave as designed on
Kubernetes. It is not a production measurement, and no figure here is an SLO.

## Environment

| | |
|---|---|
| Date | Baseline runs 2026-09-21, 06:32 – 07:42 UTC. An earlier exploratory pass on 2026-09-20 is described under [Exploratory pass](#exploratory-pass-and-the-ingress-decision). |
| Git SHA under test | `2746b2b7deef531b1a3c4f238d5f310e0e2910c3` (CI green: compile/unit, manifests, full Testcontainers suite, image build) plus fix commit `7ef9970` (DB connection budget) |
| Docker | Engine 29.7.2, Docker Desktop 4.86.0, WSL2 kernel 6.18.33.2, 16 CPU, 15.5 GiB |
| Kind | v0.33.0, binary SHA-256 verified against the official `.sha256sum` |
| Kubernetes | v1.36.4 (`kindest/node:v1.36.4`, containerd 2.3.4, Debian 13) |
| Topology | `deploy/kubernetes/kind-cluster.yaml` unchanged: 1 control-plane + 3 workers, all `Ready` |
| Ingress controller | **none installed** (see the ingress finding) |
| metrics-server | v0.9.0 with `--kubelet-insecure-tls` (Kind-only, see HPA) |
| Application image, repo pin | `sha-a500aac382ac…` = `sha256:fd7537176097…` |
| Application image, update target | `sha-2746b2b7deef…` = `sha256:30fea6fafd50…` (current `main`); no Flyway migration differs between the two |
| Database | in-cluster PostgreSQL 16 StatefulSet, 1 Gi PVC, as committed (`max_connections=200` after `7ef9970`) |

The baseline was applied to a **fresh** namespace: `corebank` was deleted (PVC included), then namespace,
the git-ignored local Secret and `kubectl apply -k deploy/kubernetes` were applied exactly as committed.

## Traffic paths and what each can prove

There is no ingress controller, so requests reach the application two ways. They are reported
**separately in every experiment**, because they can prove different things.

| Source | How | Can it support a Service-level availability claim? |
|---|---|---|
| **TUNNEL** | `kubectl port-forward svc/corebank-api 8080:80`, target `http://localhost:8080`, run under a supervisor that logs every start and exit | **No.** `kubectl` resolves the Service to **one pod** at start and stays on it |
| **SERVICE** | a bounded in-cluster Job (same `traffic.py`) calling `http://corebank-api.corebank.svc.cluster.local` | **Yes.** It exercises kube-proxy and the EndpointSlice ready set |

Both send authenticated `POST /api/transfers/internal` (1 minor unit), never an actuator path. The
Service publishes port 80 → container port 9090; the management port 9091 is private and was checked
separately (Kubernetes probe state and a pod-level port-forward).

**Evidence that the tunnel is pinned.** 61 transfers through `localhost:8080` were all served by one pod (its
per-pod request counter went 1 → 61 while the other two stayed at 0). 90 transfers from the in-cluster client
spread +30 / +31 / +29. The tunnel also died several times without being asked to: after the HPA scaled in its pod
(tunnels that had lived 495 s and 298 s), and at each of the three pod retirements in rolling-update run B.
Clock skew between host and cluster was 0.04 s, so timelines are aligned.

Every "Result" below therefore says which source it rests on.

## Startup

**Expected.** `kubectl apply -k deploy/kubernetes` brings up PostgreSQL and three application replicas,
spread over nodes, all Ready.

**Observed.**

- Fresh apply (images cached on the nodes): PostgreSQL Ready at +10.8 s, deployment 3/3 available at
  +50.8 s. On the very first apply with empty node caches: +48 s and +96 s.
- Replicas landed on three different workers; running digest equals the registry digest.
- `Service` 80/TCP → EndpointSlice on port 9090 with the three pod IPs. The `Ingress` object was accepted by
  the API server with an empty ADDRESS and served nothing.
- A real transfer through `localhost:8080` returned `200` with a journal id; the database showed exactly one
  minor unit moved. Liveness and readiness were `UP` on the management port.
- **Every app pod restarted once during cold start, in both cold starts.** Previous-container log:
  `UnknownHostException: corebank-postgres`. The PostgreSQL Service is headless, so its DNS name has no
  records until the pod is Ready; Flyway could not connect, the JVM exited 1 and the kubelet restarted it.
- Soak: after ~8.7 h idle the cluster was untouched: 4 nodes `Ready`, 0 app restarts.

**Result: PASS.** Cold start self-heals with one restart per replica: a startup-ordering characteristic, not a
correctness fault. A fresh database has no accounts; the app's own `POST /api/demo/setup` seeds them.

## Pod replacement

**Expected.** Deleting one pod is reconciled by the ReplicaSet; traffic is withdrawn from the dying pod and
served by the rest. This shows Kubernetes reconciliation, not financial correctness.

**Method.** Delete the pod the tunnel is pinned to (`9xgw8`, confirmed by per-pod counters just before), with both
sources running.

| Event | Time after delete request |
|---|---|
| Victim `Terminating`, out of the ready EndpointSlice set | +0.3 s |
| Replacement created and scheduled | +0 s |
| Tunnel dropped (pinned pod's container stopped after its 5 s `preStop`) | +4.5 s |
| Victim object gone | +6.7 s |
| Replacement `Ready` (API condition) and in the EndpointSlice | **+15 s** |

Ready endpoints never fell below 2; replacement restarts 0.

| Source | Result |
|---|---|
| SERVICE | **800/800 `200`**, 0 transport errors |
| TUNNEL | 399/400 `200`; 1 `RemoteDisconnected` at +5.3 s, when its pinned pod stopped. The supervisor restored it in ~1 s |

The one tunnel failure committed nothing (journal count equals the number of `200`s). It is a property of
`kubectl port-forward`, not of the platform.

**Result: PASS** (SERVICE 800/800). Books afterwards: totals unchanged, 0 unbalanced journals, 0 duplicate correlation ids.

## Readiness vs liveness

**Expected.** Database unavailable → readiness unhealthy → pods removed from traffic → liveness stays healthy
→ no pod restarts.

**Method.** `StatefulSet` scaled to 0 for 54 s (PVC retained), then back to 1; ~700 tunnel and 1400 Service
transfers flowing; both probes sampled through the management port every tick.

**Observed (Kubernetes state).**

| Signal | Result |
|---|---|
| EndpointSlice | all 3 pods removed at **+18.9 s**; restored at +71.2 s |
| Liveness | `200` in every sample, max 7 ms |
| Restarts | 0, 0, 0; no `Killing` of app pods |
| Readiness mechanism | one fast explicit **503** at +0.2 s (existing connections closed), then the endpoint **hangs**: Hikari has no connection and blocks for its full 30 s `connectionTimeout`, so the kubelet's 3 s probe timeout fails it (`Readiness probe failed: … context deadline exceeded`, all three pods together) |

**Observed (HTTP).**

| Window (s after scale-down) | SERVICE | TUNNEL |
|---|---|---|
| 0 – 19, DB gone, pods still Ready | 5 × **500 after ~30.1 s** | 2 × 500 after ~30 s, 1 client timeout (only 3 requests could start: both workers were parked) |
| 19 – 54, 0 ready endpoints | 34 × connection failure (`URLError`); the Service had nothing to send to | 1 × 500, 1 × `200` (a request that waited ~25 s and was served when the DB returned). **The tunnel kept sending to its unready pod**, because a port-forward has no readiness gating |
| 54 – 72, DB restoring | 14 × connection failure, 6 × `200` | 19 × `200` |
| after 72 | 1264/1264 `200` | 636/636 `200` |

There is no ingress, so "traffic withdrawn" appears at the client as connection failure, not HTTP 503. Median
latency of those failures was 1.0 s and the maximum 45 s (client timeout); I did not isolate why they were not
instantaneous.

**Idempotency after the outage.** Two claims were left behind: `pf-dbdown-svc-76` **abandoned `IN_PROGRESS`**
(claimed 0.18 s after the DB began shutting down, so its outcome could never be written; 0 journals) and
`pf-dbdown-tun-43` `FAILED`. Retrying each with its **exact original body**: the abandoned claim, after the 2-minute
lease, was reclaimed (the app logged `Reclaimed an abandoned idempotency claim`) → `200`, claim `SUCCEEDED`, exactly
**1** journal; a second retry replayed the **same journal id**. The `FAILED` claim behaved the same way. No
duplicate money movement.

**Result: PASS on outcome; the hypothesis held but the mechanism differs from its wording.** Pods were withdrawn and
nothing restarted, but readiness fails *by timeout*, and requests arriving in the ~18 s detection window wait 30 s
for a 500. That is a resilience-quality gap, not a safety defect, and is left **unchanged for a decision**: shortening
Hikari's `connection-timeout` also changes money-path failure semantics. (My once-per-second readiness sampling left
abandoned server-side waiters, so Hikari's `waiting` count seen in the exploratory pass is inflated by the
instrumentation.)

## Rolling update

**Expected.** With `maxUnavailable: 0`, `maxSurge: 1`, `minReadySeconds: 15` and readiness gating, a version change
keeps serving.

Two runs, because the first was interrupted (see [Interruption](#interruption)):

| | Run A: `a500aac` → `2746b2b` | Run B: `2746b2b` → `a500aac` (complete) |
|---|---|---|
| Traffic | 10 req/s SERVICE, ~3.3 req/s TUNNEL | 10 req/s SERVICE, ~3.3 req/s TUNNEL |
| Rollout duration | **not measured** (Docker stopped at +166.7 s with one old pod left) | **209 s** (apply → old ReplicaSet drained, 6 pods on the new digest) |
| `availableReplicas` / ready endpoints | never below 3 / never below 3 | never below 3 / never below 3 |
| HPA during the rollout | 3 → 6 at ~+27 s | 3 → 6 at +16 s, the moment the first new pod went Ready |
| Peak simultaneous pods | 8 | **8** (6 + 1 surge + 1 terminating) |
| SERVICE | **1600/1600 `200`** (window −10 … +150 s), max 1.8 s | **2000/2000 `200`** (window −10 … +190 s), max 2.1 s, p95 147 ms |
| TUNNEL | 533/533 `200` | 664/667 `200`, **3 failures at +57.8 s, +132.9 s, +172.9 s** |

The three run-B tunnel failures coincide exactly with three tunnel deaths (+58, +133, +173 s), each when the pod the
tunnel was pinned to was retired. Run A's tunnel had **no** failures only because its pinned pod happened to be the
last old pod to terminate, after its traffic ended (inferred from the pod timeline): luck, not robustness. The platform
served every SERVICE request in both runs. The peak of 8 pods is the worst case assumed by
`check-connection-budget.sh`, now observed.

**Result: PASS on SERVICE evidence.** No failed Service request was observed across one interrupted and one complete
rollout at a synthetic 10 req/s; this is not a general "zero downtime" guarantee. With the HPA live, a rollout at 3
replicas became a 6-replica rollout and took 209 s.

## Failed rollout and rollback

**Expected.** A bad image never replaces healthy pods; `rollout undo` restores service.

**Observed** (tag `sha-000…000`, confirmed absent from GHCR with a 404, applied under load):

- Surge pods went `ErrImagePull` → `ImagePullBackOff`; healthy pods kept serving. The HPA also scaled 3 → 6 at +17 s,
  so two surge pods were failing at once. `availableReplicas` stayed ≥ 3 and ready endpoints ≥ 3 throughout.
- `kubectl rollout status --timeout=15s` exited **1** ("2 out of 6 new replicas have been updated").
- `kubectl rollout undo` returned in **87 ms** (issued at +76.5 s); all pods were on the previous digest and the
  failed ReplicaSet drained by **+108 s**, including the HPA-added replicas becoming Ready.
- SERVICE **1100/1100 `200`** (763 during the failure window, 240 after undo); TUNNEL 367/367 `200` (its pinned pod
  survived this window).
- `kubectl` warns that `rollout undo` does not update the `last-applied` annotation, so the cluster and Git disagree
  afterwards; revert the pin in Git as well. The Deployment does not declare failure by itself until
  `progressDeadlineSeconds`.

**Result: PASS** (SERVICE 1100/1100).

## HPA

**Expected.** 70 % of CPU *requests* (250 m), 3–6 replicas, scale-down stabilised for 300 s.

**Observed** (load from in-cluster Jobs only: a tunnel would put every request on one pod):

- metrics-server v0.9.0 unmodified could **not** scrape Kind's kubelets (`x509: … doesn't contain any IP SANs`);
  `--kubelet-insecure-tls` was required and is a lab-only concession. After that `kubectl top nodes|pods` and
  `kubectl get hpa` worked.
- **Routine traffic alone moved the HPA.** At a steady 10 req/s the HPA scaled **3 → 6 at +25 s**, with no burst:
  the three original pods showed 287–330 m each at ~3.3 req/s (~90 ms of CPU per transfer, against a 175 m average
  target). With six pods sharing the load each still ran 100–220 m (~65 % of request). A warmed-up per-request figure
  was not measured, so JIT warm-up may account for part of this.
- Four bursts of 2000 transfers at 40 concurrency, on the already-scaled six pods: pods at ~264–273 % of request,
  41–48 req/s, p95 2.6–3.0 s (row-lock bound), **8000/8000 `200`**, 0 Hikari timeouts, 0 `too many clients`.
  Steady + bursts: **9200/9200 `200`**.
- PostgreSQL held 122 of 200 connections, up to 35 sessions waiting on row locks (all transfers touch the same two
  accounts), and a working set of up to **477 Mi of its 512 Mi limit**. It was not OOM-killed; at rest its cgroup
  shows anon 156 Mi + shmem 95 Mi + page cache 157 Mi, so part of the peak is reclaimable cache.
- Scale-in: **6 → 3 at 317 s after the load stopped** ("All metrics below target"), consistent with the 300 s window.
- Books: 18,900 journals = the exact count of successful transfers across all baseline runs.

**Result: PASS.** The HPA scales out and in as configured. Note that the `250m` request and 70 % target are
crossed by routine traffic of about 6 req/s across three pods.

## Exploratory pass and the ingress decision

An earlier pass on 2026-09-20 installed ingress-nginx `controller-v1.15.1` and ran the same phases through it. That
controller was **removed** and the baseline above was re-run without it. Nothing in this document's HTTP tables comes
from that pass. Its Kubernetes-state findings agreed with the baseline, and it is where the connection-ceiling
defect below was found (a database limit, independent of the traffic path).

Recorded as an observed / documentation finding:

- The `Ingress` object still exists in the repository with `ingressClassName: nginx`.
- ingress-nginx was retired by Kubernetes in March 2026 (repository archived, no releases or security fixes), so the
  old controller-install instructions are obsolete. `main` was byte-identical to the last release.
- The upstream Kind manifest also no longer selects the `ingress-ready` label, so the controller was scheduled on a
  worker, whose host ports are not the ones Docker maps: `127.0.0.1:80` closed the connection until it was pinned.
- Kind verification therefore uses a Service port-forward plus an in-cluster client. **The Ingress resource is not
  counted as runtime-verified.**
- External routing is to be verified later with the OpenShift `Route`.
- A Gateway API or other controller migration is a separate infrastructure change and is deliberately out of scope.

`deploy/kubernetes/README.md`, `ingress.yaml` and `kind-cluster.yaml` were corrected accordingly.

## Defects found and fixed

1. **PostgreSQL connection ceiling below the HPA maximum: fixed in `7ef9970`.** Every pod holds a Hikari pool of 20
   that stays open at idle. In the exploratory pass six replicas held exactly 100 connections (PostgreSQL's default
   `max_connections`), Postgres answered `FATAL: sorry, too many clients already` (144 log lines) — even `psql` could
   not connect — and money requests waited 30 s and returned 500 (78 of 2000). The manifests budgeted for three
   replicas (60 of 100). Fix: `max_connections=200`, plus `deploy/kubernetes/check-connection-budget.sh` in CI (it
   fails on the old manifests, and fails again if the pool or HPA maximum is raised without the database). In the
   baseline: 0 `too many clients`, 122 connections at six replicas, and 8 pods observed at peak during a rollout.
2. **Kind ingress instructions.** Documented and corrected as described above; the earlier fix that pinned
   ingress-nginx (`bab43dd`) is superseded, since the controller is retired.

## Interruption

Docker Desktop's engine stopped at **07:01:39** (WSL2 `docker-desktop` distro `Stopped`, Docker logs end with
"The pipe is being closed", no crash or OOM signature; the machine had not rebooted). It interrupted run A of the
rolling update, whose traffic had already finished at ~07:01:22. I restarted Docker Desktop; the Kind nodes came back
by themselves, all data survived (PostgreSQL kept its 5,569 journals), and the Deployment converged. Run A is used only
for the window before 07:01:39 and its completion time is not claimed; run B measures the complete rollout.

## Remaining limitations

- **Per-pod money concurrency limit (known, documented).** In the exploratory pass, 40 concurrent commands on 3 pods
  produced 36 × 500 (30 s Hikari timeout, `total=20 active=20 idle=0`) until the HPA scaled out. The baseline never
  hit it because the HPA had already scaled out, so it was not re-observed. Not changed.
- **Readiness fails by timeout, and requests wait 30 s in the detection window.** Decision needed before changing
  Hikari's `connection-timeout`.
- **HPA sizing and rollout interaction.** Routine traffic scales the HPA out, and the HPA scaled out during every
  rollout, so rollouts run at more replicas than they start with. The cause was not isolated (a coincidence with the
  first new pod becoming Ready was observed, not proven).
- **PostgreSQL memory headroom.** 477 Mi working set of 512 Mi at 122 connections; the 160-connection worst case the
  budget allows was not exercised.
- **Idempotency retry rule.** A retry must resend the *exact* body; the same key with regenerated
  `correlationId`/`requestId` is refused (400).
- **`/actuator/prometheus` returns 401** on the management port (only health probes are open), while the Deployment
  annotations advertise it for scraping. Not yet investigated; to be resolved in the APM phase.
- **The TUNNEL source cannot validate Service behaviour**; only SERVICE results support availability claims. Neither
  source uses persistent connections, so keep-alive draining during rollout was not tested.
- Not tested: node drain against the PDB, a black-holed (rather than absent) database, multi-node failure, TLS,
  PVC deletion, any traffic through an Ingress or Gateway.
- Redis is not deployed here; the app fell back to PostgreSQL for idempotency and let rate limiting fail open, as
  documented.
- The fix commit's CI run happens when the branch is pushed and has not yet occurred.
- Demo credentials are the three hard-coded showcase users; this is a lab, not a security posture.
