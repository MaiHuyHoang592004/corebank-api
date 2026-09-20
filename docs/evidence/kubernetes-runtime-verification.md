# Kubernetes Runtime Verification

Everything below was executed on a local Kind cluster and observed; nothing is inferred from
configuration. Times are UTC. Raw recordings (pod/EndpointSlice/ReplicaSet captures every ~1.3 s,
per-request results, probe samples) are kept locally and are not committed; the numbers here
are derived from them.

This is a **single-host lab** result. It shows that the committed manifests behave as designed
on Kubernetes. It is not a production measurement, and no figure here is an SLO.

## Environment

| | |
|---|---|
| Date | 2026-09-20 (21:31 – 22:21 UTC) |
| Git SHA under test | `2746b2b7deef531b1a3c4f238d5f310e0e2910c3` (CI green: compile/unit, manifests, full Testcontainers suite, image build) |
| Fix commits made during the run | `7ef9970` (DB connection budget), `bab43dd` (Kind ingress docs), see [Defects found](#defects-found-and-fixed) |
| Docker | Engine 29.7.2, Docker Desktop 4.86.0, WSL2 kernel 6.18.33.2, 16 CPU, 15.5 GiB |
| Kind | v0.33.0, binary SHA-256 verified against the official `.sha256sum` |
| Kubernetes | v1.36.4 (`kindest/node:v1.36.4`, containerd 2.3.4, Debian 13) |
| Topology | `deploy/kubernetes/kind-cluster.yaml`: 1 control-plane (ports 80/443 mapped, `ingress-ready=true`) + 3 workers, all `Ready` |
| Ingress | ingress-nginx `controller-v1.15.1` (digest-pinned), see the note on the archived upstream below |
| metrics-server | v0.9.0, with `--kubelet-insecure-tls` (Kind-only, see HPA) |
| Application image, initial | `sha-a500aac382ac…` = `sha256:fd7537176097…` (the tag pinned in `kustomization.yaml`) |
| Application image, update target | `sha-2746b2b7deef…` = `sha256:30fea6fafd50…` (current `main`); no Flyway migration differs between the two |
| Database | in-cluster PostgreSQL 16 StatefulSet with a 1 Gi PVC (as committed) |
| Load generator | localhost only, authenticated `POST /api/transfers/internal` of 1 minor unit, through the Ingress (`Host: corebank.local`) so that requests cross every pod |

The cluster `corebank` already existed when this run began (created minutes earlier by the previous
session). It was adopted rather than recreated after checking that it matches the committed config:
`ingress-ready=true` on the control-plane in `/kind/kubeadm.conf`, ports 80/443 bound, three workers.

## Startup

**Expected.** `kubectl apply -k deploy/kubernetes` brings up PostgreSQL and three application
replicas, spread over nodes, all Ready.

**Observed.**

- PostgreSQL Ready at +48 s; deployment 3/3 available at +96 s (first image pull ~28 s per node).
- Replicas landed on three different workers; running image digest equals the registry digest.
- A real transfer through the Ingress returned `200` with a journal id; the database showed exactly
  one minor unit moved. Liveness and readiness were `UP` on the private management port.
- **Every app pod restarted once** during cold start. Cause, from the previous container's log:
  `UnknownHostException: corebank-postgres`. The PostgreSQL Service is headless, so its DNS name has no
  records until the pod is Ready; the app pulled its image faster, Flyway could not connect, the JVM
  exited 1 and the kubelet restarted it. No manual step was needed.

**Evidence.** `kubectl get pods -o wide`, previous-container logs, image IDs vs GHCR manifest digest.

**Result: PASS.** Cold start self-heals with one restart per replica (a startup-ordering characteristic,
not a correctness fault). A fresh database has no accounts; the app's own `POST /api/demo/setup` seeds them.

## Pod replacement

**Expected.** Deleting one pod is reconciled by the ReplicaSet; traffic is withdrawn from the dying
pod and served by the rest. This demonstrates Kubernetes reconciliation, not financial correctness.

**Observed** (one pod deleted while 380 transfers streamed through the Ingress):

| Event | Time after delete request |
|---|---|
| Victim (`uid a23202ee`) `Terminating`, removed from the ready EndpointSlice set | +0.5 s (first observation) |
| Replacement (`uid 489923bb`) created and scheduled | +0 s |
| Victim object gone | +6.9 s (5 s `preStop` + shutdown) |
| Replacement `Ready` (API condition) and in the EndpointSlice | **+20 s** |

Ready endpoints never fell below 2. Replacement restart count 0. **380/380 requests returned 200**, no
transport errors; latency p50 was 94–109 ms in every phase (one 1.6 s outlier in the two-pod window, cause
not isolated).

**Result: PASS.** Books after the test: totals unchanged, 0 unbalanced journals, 0 duplicate correlation ids.

## Readiness vs liveness

**Expected.** Database unavailable → readiness unhealthy → pods removed from traffic → liveness stays
healthy → no pod restarts.

**Method.** `StatefulSet` scaled to 0 for 54 s (PVC retained), then back to 1, with ~700 transfers
flowing and both probes sampled through the management port once per tick.

**Observed.**

| Signal | Result |
|---|---|
| EndpointSlice | all 3 pods removed at **+18.5 s**; restored at +72 s (18 s after PostgreSQL was restarted) |
| Liveness | `200` in every sample, max 12 ms |
| Restarts / pod UIDs | unchanged (0, 1, 1); no `Killing` events for app pods |
| Readiness mechanism | **not an explicit 503.** The endpoint *hangs*: Hikari has no connection (`total=0`) and blocks for its full 30 s `connectionTimeout`; the kubelet's 3 s probe timeout fails it (`context deadline exceeded`, on all three pods together) |
| HTTP, 0–18 s (DB gone, pods still routed) | the 2 in-flight requests returned **500 after ~30.1 s** |
| HTTP, 18–72 s | nginx answered `503` in ~0 ms (356 requests) |
| HTTP, after recovery | 298/298 `200` |
| Idempotency / ledger | the two failed requests left **no idempotency row and 0 journals** (the claim itself needs the DB), so a retry with the same key is clean; 723 `SUCCEEDED` = 723 journals; totals unchanged |

**Result: PASS on outcome; the hypothesis held but the mechanism differs from the wording.** Traffic was
withdrawn and nothing restarted, but readiness fails *by timeout*, and requests that arrive in the ~18 s
detection window wait 30 s for a 500. That is a resilience-quality gap, not a safety defect, and it is left
**unchanged and for a decision**: shortening Hikari's `connection-timeout` also changes money-path failure
semantics. (Caveat: my once-per-second readiness sampling left abandoned server-side waiters, so the
`waiting=10` seen in Hikari is inflated by the instrumentation.)

## Rolling update

**Expected.** With `maxUnavailable: 0`, `maxSurge: 1`, `minReadySeconds: 15` and readiness gating, a
version change keeps serving.

**Observed** (`sha-a500aac…` → `sha-2746b2b…`, 10 req/s, 4 concurrent clients, whole rollout):

- Apply → all three pods on the new digest and the old ReplicaSet drained: **147 s** (includes the first
  pull of the new image on each node).
- `availableReplicas` never below 3; ready endpoints never below 3 (peaked at 4); up to 5 pods existed at once.
- Each old pod was terminated ~15 s (`minReadySeconds`) after its replacement became Ready.
- **1600/1600 requests returned 200**; 0 × 5xx; 0 transport errors. Latency p50 78 ms, p95 110 ms; the slowest
  requests (1.3–1.8 s) coincide with new pods entering rotation (+76.7 s, +127.3 s): a warm-up correlation, not
  isolated.
- All pods on `sha256:30fea6fa…`, 0 restarts. PostgreSQL was not restarted.

**Result: PASS.** No failed request was observed in this test. That is a statement about one rollout at a
synthetic 10 req/s, not a general "zero downtime" guarantee.

## Failed rollout and rollback

**Expected.** A bad image never replaces healthy pods; `rollout undo` restores service.

**Observed** (tag `sha-000…000`, confirmed absent from GHCR with a 404, applied under load):

- The surge pod went `ErrImagePull` → `ImagePullBackOff`; the three healthy pods kept serving. Deployment
  stayed `3/3 available, 1 up-to-date` for the whole 77 s window; ready endpoints stayed at 3.
- `kubectl rollout status --timeout=15s` exited **1** ("1 out of 3 new replicas have been updated").
- `kubectl rollout undo` returned in **114 ms**; the failed ReplicaSet was drained and 3 pods were on the
  previous digest (`30fea6fa…`) **~1.2 s** later.
- **1100/1100 requests returned 200** (766 during the failure window, 227 after undo).
- `kubectl` warns that `rollout undo` does not update the `last-applied` annotation, so the cluster and Git
  disagree afterwards; revert the pin in Git as well.

**Result: PASS.** Disclosure: this scenario was run three times. Attempt 1's script aborted before its
undo (my error: a native-command stderr under `ErrorActionPreference=Stop`) and was undone by hand; run 2's
HTTP data is **invalid** because I reused an idempotency-key prefix, so every request was correctly refused
(400). Only run 3 (unique keys) is quoted above.

## HPA

**Expected.** 70 % of CPU *requests* (250 m), 3–6 replicas, scale-down stabilised for 300 s.

**Observed.**

- metrics-server v0.9.0 unmodified could **not** scrape Kind's kubelets (`x509: … doesn't contain any IP
  SANs`); `--kubelet-insecure-tls` was required and is a lab-only concession. Then `kubectl top nodes|pods`
  and `kubectl get hpa` worked (idle: 3 m/pod, `1%/70%`).
- Load: repeated bursts of 2000 transfers at 40 concurrency (the helper's bound), through the Ingress. Pods
  reached ~900–1000 m against a 1000 m limit. **HPA scaled 3 → 6 at +22 s; 6/6 Ready by ~45 s.**
- After the load stopped: **6 → 4 at +323 s, → 3 at +338 s**, consistent with the 300 s window.
- With the connection-budget fix (below), bursts on six pods sustained 48–56 req/s with 6000/6000 `200`;
  PostgreSQL held 121 of 200 connections, with up to 35 sessions waiting on row locks (peak active sessions
  37; all transfers contend on the same two accounts, so throughput is lock-bound).

**Result: PASS, after a real defect was found and fixed.** See below. The pods' CPU pressure was real here,
so no limitation needs recording for the trigger itself.

## Defects found and fixed

1. **PostgreSQL connection ceiling below the HPA maximum: fixed in `7ef9970`.** Every pod holds a Hikari pool
   of 20 that stays open at idle. Six replicas held exactly 100 connections, PostgreSQL's default
   `max_connections`, and answered `FATAL: sorry, too many clients already` (144 log lines) — even `psql`
   could not connect. Money requests waited 30 s and returned 500 (78 of 2000 in that run). The
   manifests and runbook budgeted for three replicas (60 of 100). Fix: `max_connections=200`, plus
   `check-connection-budget.sh` in CI (fails on the old manifests; fails again if the pool or HPA maximum is
   raised without the database). After the fix: 0 `too many clients`, and the same load ran clean on six pods.
2. **Kind ingress instructions: fixed in `bab43dd`.** The upstream manifest no longer selects `ingress-ready`, so
   the controller was scheduled on a worker and `127.0.0.1:80` refused connections; the `kind-cluster.yaml`
   comment claiming otherwise was false. `kubernetes/ingress-nginx` is also **archived** (last release
   `controller-v1.15.1`, March 2026).

## Remaining limitations

- **Per-pod money concurrency limit (known, documented).** 40 concurrent commands on 3 pods produced 36 × 500
  (30 s Hikari timeout, `total=20 active=20 idle=0`) until the HPA scaled out. The HPA reacts in ~45 s, so a
  sudden burst is exposed for that time. Not changed.
- **Readiness fails by timeout, and requests wait 30 s in the detection window** (see Readiness vs liveness).
  Decision needed before changing Hikari's `connection-timeout`.
- **PostgreSQL memory.** Working set peaked at 436 Mi of its 512 Mi limit at 121 connections. The 160-connection
  worst case the new budget allows was not exercised.
- **Idempotency retry rule.** A retry must resend the *exact* body: the same key with regenerated
  `correlationId`/`requestId` was refused (400), the identical body replayed the original journal, a fresh key
  created a new one.
- **`/actuator/prometheus` returns 401** on the management port (only health probes are open), while the
  Deployment annotations advertise it for scraping. Not yet investigated; to be resolved in the APM phase.
- Not tested: node drain against the PDB, a network black-holed (rather than absent) database, multi-node
  failure, TLS on the Ingress, persistence across a PVC delete, HTTP/1.1 keep-alive draining behaviour.
- Redis is not deployed here; the app fell back to PostgreSQL for idempotency and let rate limiting fail
  open, as documented.
- The fix commits were verified locally (guard script, live cluster). Their CI run happens when the branch is
  pushed and has not yet occurred.
- Demo credentials are the three hard-coded showcase users; this is a lab, not a security posture.
