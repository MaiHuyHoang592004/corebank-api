# CoreBank on Kubernetes and OpenShift with Dynatrace: Platform Proof of Concept

| | |
|---|---|
| Subject | Running a ledger-style Spring Boot service on Kubernetes and OpenShift and observing it with Dynatrace |
| Nature | An **executed lab POC**: every result below was observed on a running system and is backed by a document in `docs/evidence/` |
| Date | 2026-09-20 to 2026-09-21 |
| Environments | Kind (local Kubernetes), Red Hat Developer Sandbox (OpenShift), Dynatrace trial tenant, upstream Istio on Kind |
| Revision under test | `2746b2b7deef531b1a3c4f238d5f310e0e2910c3` (plus the fix commits listed under [Findings](#findings-that-changed-the-repository)) |
| Author's position | A lab exercise by one engineer. It is **not** production experience with OpenShift or Dynatrace, and none of it should be presented as such |

## Reading guide

Statuses used throughout:

- **Met**: run and observed, the criterion held.
- **Met, with a finding**: the criterion held but the run exposed a defect or a limit that matters.
- **Partly met**: some of the criterion was observed; what is missing is stated.
- **Not met**: attempted, and it could not be done in this environment.
- **Not run**: out of scope or deliberately left.

## 1. Problem statement

A team has a service that moves money: a single Spring Boot application backed by PostgreSQL, with an
idempotency contract, double-entry journals and Flyway-managed schema. Its repository already carried Kubernetes
manifests, an OpenShift overlay, an OpenTelemetry collector and a Dynatrace export path, but every one of them was
labelled *CI-validated; runtime pending*. A manifest that renders is not a deployment that runs. Before any decision
to adopt a platform or an APM vendor, the team needs to know:

1. Does the workload behave on the platform as its manifests claim: self-healing, rollout, rollback, scaling?
2. Does the telemetry the documentation promises actually arrive in Dynatrace, and is it sufficient to diagnose a
   real fault?
3. What does the OpenShift security model change, and what breaks?
4. What can and cannot be done with the access a low-privilege environment gives?

## 2. Current architecture

```text
Client ──► Ingress (Kubernetes) / Route (OpenShift) ──► Service ──► CoreBank pods (3–6, HPA)
                                                                        │  management port 9091 (private)
                                                                        └──► PostgreSQL (single instance)
CoreBank pods ──OTLP/HTTP──► OpenTelemetry Collector ──OTLP + Api-Token──► Dynatrace
```

| Element | As committed |
|---|---|
| Application | one Spring Boot deployable, HTTP on 9090, actuator on a separate management port 9091 that the Service does not publish |
| Data | PostgreSQL 16, Flyway migrations, a `customer_accounts` row-lock in the transfer path, an idempotency table with a 2-minute claim lease |
| Kubernetes base | Deployment (3 replicas, `maxUnavailable: 0`, `maxSurge: 1`), Service, HPA (250m request, 70 %, 3–6), PDB (`minAvailable: 2`), probes, restricted security context, image pinned to a commit |
| OpenShift overlay | deletes the in-cluster database and the Ingress, adds a Route (edge TLS, HTTP redirected, 60 s timeout) |
| Telemetry | OpenTelemetry SDK → collector → Dynatrace OTLP; `service.version` is the commit SHA |
| Service mesh | none at the start of the POC |

## 3. POC objectives

| # | Objective |
|---|---|
| O1 | Run the base on Kubernetes and observe pod replacement, database loss, rolling update, failed rollout and rollback, and autoscaling |
| O2 | Get application traces and metrics into Dynatrace with a minimum-scope credential, and read them back |
| O3 | Diagnose a deliberately induced database incident from the Dynatrace side, and tell slow SQL, pool wait and lock contention apart |
| O4 | Run the workload on OpenShift under `restricted-v2` with an arbitrary UID, through a public Route |
| O5 | Try traffic shifting, mutual TLS and rollback with a service mesh |
| O6 | Record what does not work, what was not tried, and why |

## 4. Scope

**In scope.** The existing single application, its database, its manifests; Kind; the OpenShift Developer Sandbox;
a Dynatrace trial tenant; upstream Istio on Kind; synthetic, bounded traffic; financial-invariant checks after each
experiment.

**Out of scope, deliberately.** Splitting the application into microservices; Terraform; ArgoCD or any GitOps
controller; Vault or any external secret manager; new banking features; production sizing, capacity planning and
SLO measurement; Dynatrace OneAgent, Davis problems, alerting and Smartscape; Dynatrace logs ingestion; OpenShift
Service Mesh (not installable, see below); custom domains and certificates; backups and disaster recovery.

## 5. Prerequisites

| Need | What was used | For a customer environment |
|---|---|---|
| Container runtime and local cluster | Docker Desktop, Kind v0.33.0 (Kubernetes v1.36.4), 1 control-plane + 3 workers | a dev or staging cluster with metrics-server |
| CLI | `kubectl`, `oc` 4.22.13, `istioctl` 1.31.0, all checksum-verified | the same, from the customer's approved sources |
| Image registry | public GHCR, image pinned by commit SHA | a registry the cluster can pull from, with a pull secret if private |
| OpenShift | Red Hat Developer Sandbox: shared, project-scoped, free | a project with quota for 6 replicas + collector + database; **cluster-admin for operators** |
| Dynatrace | free trial tenant, one ingest token with only `openTelemetryTrace.ingest` and `metrics.ingest`, 7-day expiry | a tenant with a token policy; a Dynatrace Operator install needs an admin |
| Secrets | git-ignored local Secret files, never committed | a secret manager (out of scope here) |

**Access limits found in the Sandbox** (they shaped the plan): no cluster-admin, no permission to create projects,
namespaces, CRDs, cluster roles, operator subscriptions in `openshift-operators` or operator groups.

## 6. Deployment architecture

```text
KIND                                              OPENSHIFT SANDBOX
kubectl port-forward (one pod) ─┐                 workstation ──HTTPS──► Route (edge TLS, HTTP→HTTPS)
in-cluster client Job ──────────┼─► Service                                   │
                                │      │                                      ▼
                                ▼      ▼                                   Service
                       CoreBank pods 3–6 (HPA)                     CoreBank pods 3–6 (restricted-v2, UID 1003610000)
                                │                                             │
                        PostgreSQL 16 (StatefulSet)                  PostgreSQL 15.8 (catalog template, PVC)
                                │                                             │
                     OTel Collector ─► Dynatrace                   OTel Collector ─► Dynatrace (no Route to the receiver)

ISTIO ON KIND (separate namespace)
in-mesh client ─mTLS─► Service ─ VirtualService 90/10 ─► v1 pods (2)   ┐ same PostgreSQL,
                                                       └► v2 pods (2)   ┘ database outside the mesh
```

## 7. Implementation plan and what happened

| Phase | Work | Outcome |
|---|---|---|
| 1. Kubernetes baseline | Fresh Kind cluster; local Secret; `kubectl apply -k`; two traffic sources (a one-pod tunnel and an in-cluster client) reported separately | 3/3 Ready at +50.8 s; see [Observed results](#8-observed-results) |
| 2. Failure experiments on Kind | pod deletion, database scaled to 0, rolling update, non-existent image tag then `rollout undo`, HPA load | all passed; a database connection ceiling was found and fixed |
| 3. Dynatrace onboarding | trial tenant, minimum-scope token, collector on Kind, application OTLP switched on | traces, JDBC spans and 10 `corebank.*` metric series read back |
| 4. Incident and RCA | a bounded, reversible row-lock on the two hot accounts (45 s) | diagnosed from Dynatrace traces plus cluster-side evidence |
| 5. OpenShift | `oc login --web`, overlay, catalog PostgreSQL, Route, replacement, rollout, rollback, HPA | two defects found and fixed; then all passed |
| 6. Telemetry on OpenShift | collector into the project, application switched on | data arrived; attribute read-back **not completed** |
| 7. Service mesh | OpenShift Service Mesh permissions checked; upstream Istio 1.31.0 on Kind | OSSM not installable; Istio canary, mTLS, rollback verified |
| 8. DynaKube | permissions checked | not possible; recorded and stopped |

## 8. Observed results

All times are measured on a single host or a shared free cluster with synthetic ~10 requests/s traffic. They are
lab observations, **not SLOs and not production measurements**.

| Area | Kind (`kubernetes-runtime-verification.md`) | OpenShift Sandbox (`openshift-runtime-verification.md`) |
|---|---|---|
| Cold start | 3/3 Ready at +50.8 s (images cached); every pod restarts once while the headless database Service has no DNS record | 3/3 Ready at +48.9 s incl. image pull; 26/26 Flyway migrations |
| Pod replacement | replacement Ready at +15 s; Service **800/800** `200` | replacement Ready at +29.7 s; Route **450/450** `200` |
| Database loss | all 3 pods withdrawn at +18.9 s, 0 restarts; requests in the 18 s detection window waited ~30 s for a `500`; abandoned claim recovered after the 2-minute lease with exactly one journal | database pod replaced (about 26 s, inferred): 4 requests held 26 – 27 s then succeeded, 1 × `503` at the router, 699/700 `200`; 2,000 journals survived on the PVC |
| Rolling update | 209 s at 6 replicas (HPA scaled out mid-rollout), peak 8 pods, Service **2000/2000** `200` | 133.4 s, ≥ 3 available, Route **1500/1500** `200` |
| Failed rollout and rollback | bad tag `ErrImagePull`; `rollout undo` in 87 ms; Service **1100/1100** `200` | bad tag; undo in 2.0 s, 3/3 on the previous ReplicaSet 4.6 s later; Route **800/800** `200` |
| HPA | 3 → 6 at +25 s under routine load; 6 → 3 at 317 s after the load stopped | 6/6 Ready after a fix; peak requests.cpu 1560m of a 3 CPU quota |
| Route | not applicable; Ingress **not counted as verified** | HTTPS `200` with a public CA certificate, HTTP → `302`, `/actuator/*` → `404` |

**Dynatrace** (`dynatrace-apm-verification.md`, `dynatrace-incident-rca.md`):

- The collector ran with the committed manifests: `runAsNonRoot` satisfied (uid 10001), read-only root filesystem,
  `health_check` `200`.
- One service `corebank-api` with `service.namespace=corebank` and `service.version` equal to the commit SHA; a
  server span per request with JDBC `CONNECTION` (events `acquired`, `commit`) and `QUERY` client spans carrying the SQL
  text and pool name.
- 10 `corebank.*` metric series and the Hikari series; `corebank.ledger.journals.posted` summed to exactly the
  2,700 journals committed.
- **Not observed:** OTLP logs (0 records), Davis problems, alerting, Smartscape.
- **On OpenShift:** the collector deployed under `restricted-v2` with no Route for the OTLP receiver, the application
  exported to it, and a 1,200-transfer run produced 1,000 requests in Dynatrace's trace explorer (server span
  100 – 140 ms, the last at the moment the run ended). The resource attributes (`deployment.environment=openshift`,
  `service.version`) and the JDBC spans of *those* traces were **not read back**: the browser tab used for that was
  hidden by the operating system. This part is **partly met**.

**Service mesh** (`service-mesh-verification.md`): upstream Istio 1.31.0 on Kind. Three independent counters
agreed on where every request went; weights 50/50 gave 49.8 %; weights 90/10 gave 8.7 % to v2 over 4,936 requests
(a shortfall not explained); a plaintext client without a sidecar was reset while the same request from a meshed pod
was answered; a rollback to 100 % v1 under load sent 1,064 of 1,064 subsequent requests to v1 with no error.

## 9. Acceptance criteria

| ID | Criterion | Result | Evidence |
|---|---|---|---|
| AC1 | The base applies to a fresh cluster and every pod becomes Ready | **Met, with a finding** (one restart per pod at cold start) | Kubernetes |
| AC2 | Deleting a pod does not fail a request through the Service | **Met** | Kubernetes, OpenShift |
| AC3 | Losing the database withdraws pods from traffic without restarting them | **Met, with a finding** (readiness fails by timeout; requests wait 30 s) | Kubernetes |
| AC4 | A rolling update fails no Service request at ~10 requests/s | **Met** (not a general zero-downtime guarantee) | Kubernetes, OpenShift |
| AC5 | A bad image never replaces healthy pods and `rollout undo` restores service | **Met** | Kubernetes, OpenShift |
| AC6 | The HPA scales within its bounds and back | **Met, with a finding** (routine traffic scales it; database connection ceiling) | Kubernetes, OpenShift |
| AC7 | The overlay is admitted on OpenShift under `restricted-v2`, with an arbitrary UID, no `anyuid`, no SCC change | **Met, with a finding** (two defects, both fixed) | OpenShift |
| AC8 | HTTPS Route, HTTP redirected, actuator not published | **Met** | OpenShift |
| AC9 | Replacement, rollout and rollback keep the Route serving | **Met** | OpenShift |
| AC10 | Traces and metrics reach Dynatrace with the release identity and JDBC spans | **Met on Kind; partly met on OpenShift** (arrival seen, attributes not read back) | Dynatrace |
| AC11 | An induced database incident can be diagnosed from Dynatrace | **Met** | RCA |
| AC12 | Financial invariants hold after every experiment | **Met** (0 negative balances, 0 unbalanced journals, 0 duplicate correlation ids, journals = succeeded claims, each environment) | all |
| AC13 | Canary, mutual TLS and rollback with a service mesh | **Met with upstream Istio on Kind; OpenShift Service Mesh: Not met** (operator cannot be installed) | Service mesh |
| AC14 | Dynatrace Kubernetes Operator / DynaKube on OpenShift | **Not met** (no CRD, cluster-scoped creates denied) | this document |
| AC15 | No secret, token, tenant identifier, account e-mail or project name is committed | **Met** (checked over every commit on the branch; the commit author metadata is the only place an address appears) | repository |

## 10. Test scenarios

| # | Scenario | Method | Where |
|---|---|---|---|
| T1 | Cold start | fresh apply, watch pods and EndpointSlice | Kind, OpenShift |
| T2 | Pod replacement | delete one pod under load | Kind, OpenShift |
| T3 | Database loss | scale the database to 0 for 54 s (Kind); replace the database pod (OpenShift) | Kind, OpenShift |
| T4 | Idempotent retry after an outage | retry abandoned and failed claims with the exact original body | Kind |
| T5 | Rolling update | change the image tag under load | Kind, OpenShift |
| T6 | Failed rollout and rollback | non-existent tag, then `rollout undo` | Kind, OpenShift |
| T7 | HPA scale-out and scale-in | routine and bursty load | Kind, OpenShift |
| T8 | Route behaviour | HTTPS, HTTP redirect, actuator path, certificate | OpenShift |
| T9 | Telemetry arrival and content | 600 – 2,000 transfers, read back in Dynatrace | Kind, OpenShift (partly) |
| T10 | Incident diagnosis | 45 s row-lock on the two hot accounts, bounded and reversible | Kind |
| T11 | Weighted routing | 90/10 and 50/50 across two versions, three counters | Istio on Kind |
| T12 | mTLS | server-side metric label, plaintext client without a sidecar, `istioctl x describe` | Istio on Kind |
| T13 | Mesh rollback | apply a 100 % v1 route under load | Istio on Kind |
| T14 | Operator-based installs | permission checks and dry-runs for OpenShift Service Mesh and DynaKube | OpenShift |

## 11. APM incident and root-cause analysis

While Dynatrace was collecting telemetry, a `psql` session held `SELECT … FOR UPDATE` on the two accounts every
transfer touches, for 45 s, then committed. Read from Dynatrace: 8 requests of `http post /api/transfers/internal`
took 44.6 – 45.1 s each (down from ~100 ms), and in the longest trace a single `QUERY` span of 45.02 s held ~99.8 % of
the request while the `acquired` event sat at the very start of the `CONNECTION` span.

Three explanations were tested against measurements:

| Hypothesis | Observed | Verdict |
|---|---|---|
| Slow connection acquisition | Hikari pending 0 throughout; `acquired` at the start of the span | ruled out |
| Slow SQL | the identical statement ran in 0.135 ms uncontended; waits were `Lock`, not CPU or I/O | ruled out |
| Lock contention | 8 sessions in `wait_event_type='Lock'`, oldest wait growing 3.1 → 44.8 s, journals/s 10 → 0 | **confirmed** |

Recovery was the lock holder's `COMMIT`; all 8 blocked requests then completed. One request had been cut off by the
client's own timeout yet had committed; retrying it with its exact body returned the same journal, so no money moved
twice. What an operator would use instead (`pg_blocking_pids()` then `pg_terminate_backend()`) was **not exercised**, and
no `lock_timeout` or `statement_timeout` is configured on the transfer path. The investigation was manual: no Davis
problem or alert was checked. Full detail: `dynatrace-incident-rca.md`.

## 12. Security

- **Credentials.** One Dynatrace ingest token, scopes `openTelemetryTrace.ingest` and `metrics.ingest` only, 7-day
  expiry, held in a git-ignored file and applied as a Kubernetes Secret. It was never printed, logged or committed; a
  scan of every commit on the branch finds no token, tenant identifier, account e-mail or Sandbox project name.
  **It should be revoked when the lab is torn down.**
- **OpenShift login.** `oc login --web` (browser OAuth with PKCE, completed by the account owner). The token was never
  read, printed or copied by the automation. No `anyuid`, no SCC created or changed, no bypass of a denied permission.
- **Pod security.** `restricted-v2`, an arbitrary UID from the project range, read-only root filesystem, no privilege
  escalation, all capabilities dropped. The application needed no patch for this; the repository's PostgreSQL could
  not run under it, which is why the overlay uses the catalog image.
- **Exposure.** The management port 9091 is not on the Service and `/actuator/*` returns `404` through the Route. The
  OTLP receiver has a ClusterIP Service only, no Route. The collector has no NetworkPolicy.
- **Data in motion.** Route: TLS terminated at the router, plain HTTP inside the cluster. Dynatrace: HTTPS with an
  `Api-Token`. Mesh: STRICT mutual TLS between meshed pods; **the application-to-database connection is not encrypted
  by the mesh.**
- **Identity.** The three showcase users are hard-coded demo credentials of the lab application. This is not a
  security posture.
- **A silent failure.** A wrong Dynatrace token gives a permanent `401` that the exporter treats as final: data is
  dropped, only the collector's log says so, and nothing alerts.

## 13. Rollback

Of the application (all exercised): `kubectl rollout undo` / `oc rollout undo`, then revert the pin in Git, because
`undo` leaves the cluster and Git disagreeing. Of the mesh canary: apply `deploy/service-mesh/rollback-to-v1.yaml`.
Of the database change on OpenShift: none needed; the connection limit is raised, not lowered.

Of the POC itself (not exercised here): delete the `corebank`, `corebank-mesh` and `lab-clients*` namespaces on Kind or
the whole Kind cluster; delete the Sandbox project's workload objects; `istioctl uninstall --purge`; **revoke the
Dynatrace token** and delete the notebook created in the tenant. Nothing outside the lab was changed.

## 14. Limitations

- Single host, free shared Sandbox, trial tenant, synthetic closed-loop traffic. No result is an SLO.
- The Ingress resource is legacy and **not** counted as verified: its controller, ingress-nginx, is retired and was not
  installed. External routing was verified only through the OpenShift Route.
- The pinned image `sha-a500aac…` cannot produce JDBC spans; Dynatrace and mesh v2 runs used `sha-2746b2b…`.
- OpenShift's database is a deprecated `DeploymentConfig` running PostgreSQL 15.8 (the application is tested on 16),
  single replica, no backups.
- Dynatrace read-back on OpenShift is incomplete (see AC10). OTLP logs, alerting and Davis were not covered.
- Open decisions in the repository, deliberately not changed: Hikari `connection-timeout` (30 s makes readiness fail by
  timeout and money requests wait 30 s), PostgreSQL memory limit (477 Mi of 512 Mi observed on Kind), HPA sizing
  (routine traffic scales it out).
- Mesh: sidecar mode only, no gateway, no external traffic, no telemetry backend, a 90/10 split that delivered ~9/91.
- OpenShift Service Mesh and DynaKube were not run; nothing here is experience of either.
- The branch has not been pushed, so CI has not yet run on the fix commits.

## 15. Recommendation

The repository's manifests are **runtime-verified on Kind and on a project-scoped OpenShift Sandbox** for the behaviours
in section 9, and the Dynatrace path **works on Kind** end to end, including an incident diagnosis. That is enough to
justify a next-stage POC. It is not enough to justify a production decision, and this document does not make one.

A next stage should, in this order:

1. **Fix or decide the three open items** above (Hikari timeout, database memory, HPA sizing) before any load figure is
   quoted, and push the branch so CI runs on the fixes.
2. **Move the database off the catalog `DeploymentConfig`** onto a managed instance, sized from the connection budget
   the script prints, with backups and a tested restore.
3. **Complete the Dynatrace read-back on OpenShift**, then add what was not covered: logs correlation, a Davis
   problem and an alert on a wrong-token or dropped-data condition, and a NetworkPolicy for the collector.
4. **Repeat on an environment with cluster-admin**, where OpenShift Service Mesh and the Dynatrace Operator can actually
   be installed. Only that run can support statements about either product.
5. **Decide what the mesh is for** before adopting one: the experiment shows it works with this application, not that
   this application needs it. Ask why the 90/10 split delivered less than 10 %, and whether the database hop should be
   encrypted.
6. **Only then** treat any latency, recovery time or throughput here as an input to SLOs, and measure them on the
   target platform. The proposed service levels in `docs/32-service-levels.md` remain proposals.
