# Running CoreBank on Kubernetes

Manifests for running the API as three replicas with health probes, zero-downtime
rollouts, autoscaling and a disruption budget. Everything here is plain Kubernetes;
there is no Helm chart and no operator, so each object can be read on its own.

```
deploy/kubernetes/
├── kind-cluster.yaml     four-node local cluster, ingress-ready
├── namespace.yaml
├── configmap.yaml        non-secret configuration
├── secret.example.yaml   template — copy to secret.yaml, never commit it
├── postgres.yaml         single-instance database for the lab
├── deployment.yaml       3 replicas, three probes, graceful shutdown
├── service.yaml
├── ingress.yaml
├── hpa.yaml              3 to 6 replicas on CPU
├── pdb.yaml
└── kustomization.yaml

deploy/openshift/            overlay for OpenShift — see "OpenShift" below
├── kustomization.yaml
├── route.yaml
└── schemas/                 vendored Route schema, for kubeconform in CI
```

## Bring it up

```bash
kind create cluster --name corebank --config kind-cluster.yaml

# No ingress controller is installed. The Ingress in this directory targets
# ingress-nginx, which Kubernetes retired in March 2026 (repository archived, no further
# releases or security fixes), so this guide does not install it. Reach the application
# with a port-forward instead, see "Reaching the application" below.

# Secrets first: the database and the application both read from them.
cp secret.example.yaml secret.yaml
$EDITOR secret.yaml
kubectl apply -f namespace.yaml
kubectl apply -f secret.yaml

kubectl apply -k .
kubectl -n corebank rollout status deployment/corebank-api --timeout=300s
```

### Choosing the image tag

`kustomization.yaml` pins one immutable tag, `sha-<commit>`, and releasing is editing
`newTag` there. Apply this directory through kustomize — `kubectl apply -f
deployment.yaml` would use the placeholder tag in that file instead.

Edit it by hand rather than with `kustomize edit set image`. That command rewrites
the whole file through its YAML printer: on v5.4.3 it reindents every list, adds a
redundant `newName`, and silently drops the explicit `includeSelectors: false`,
leaving the comment that explains that field describing something no longer there.
The render is unchanged because `false` is the default — but a release should not
rely on a default it meant to state, and a one-line version bump should not arrive
as a thirteen-line diff nobody reads.

The pin is not a formality. A floating tag answers "what is newest" and never "what
is running": two pods started an hour apart can be on different code under one name,
`rollout undo` rolls back to the name it just left, and an incident timeline has
nothing to anchor to. CI fails the manifests job if a rendered manifest carries a
floating tag.

`latest` is doubly wrong here — CI applies it only on the default branch, so it does
not exist yet and deploying it gives `ImagePullBackOff`. Every build is also tagged
with a sanitised branch name if you want to track a branch instead of a commit.

The package is public, so no pull secret is needed. Verified by fetching the pinned
tag's manifest from `ghcr.io` with an anonymous token and no credentials available:
it returns the same digest CI's push log recorded, while the same request without a
token returns 401.

GHCR packages are private when first published. If you fork this and see
`ImagePullBackOff` with an authentication error, either make your own package public
under the repository's package settings, or give the namespace a pull secret:

```bash
kubectl -n corebank create secret docker-registry ghcr \
  --docker-server=ghcr.io --docker-username=<github-user> --docker-password=<token>
kubectl -n corebank patch serviceaccount default \
  -p '{"imagePullSecrets":[{"name":"ghcr"}]}'
```

### Reaching the application

```bash
kubectl -n corebank port-forward svc/corebank-api 8080:80
# then open http://localhost:8080/
```

The Service publishes port 80, which forwards to the application container's port 9090.
The management port, 9091, is deliberately not on the Service: probes reach it through
the pod IP, and to look at it yourself forward a pod rather than the Service
(`kubectl -n corebank port-forward pod/<name> 9091:9091`).

Two limits of a port-forward matter when you test, and both were observed rather than
assumed. `kubectl` resolves `svc/…` to **one pod** when it starts and stays on it: 61
requests sent through it were all served by a single pod. So it cannot show load
balancing, readiness gating or EndpointSlice membership, and if that pod is deleted or
scaled in, the tunnel drops. Use it to try the application; to measure availability
across pods, send requests from inside the cluster to
`http://corebank-api.corebank.svc.cluster.local` (see the rollout exercise below).

**The Ingress is legacy and unverified.** `ingress.yaml` is kept as it was, with
`ingressClassName: nginx`. It was applied to the API server, which accepted it, but no
controller has served traffic through it in the runtime verification, so nothing here
claims it works. On OpenShift, external routing is the Route in `deploy/openshift/`. Moving
to another ingress controller or to the Gateway API is a separate infrastructure decision
and is not part of this directory.

## What each probe is for

The three probes answer three different questions, and conflating them is the usual
way a healthy system gets taken down by its own orchestrator.

| Probe | Question | Endpoint | Consults |
|---|---|---|---|
| startup | has it finished booting? | `/actuator/health/readiness` | database |
| readiness | should it receive traffic? | `/actuator/health/readiness` | database |
| liveness | is this JVM wedged? | `/actuator/health/liveness` | nothing shared |

Liveness deliberately ignores PostgreSQL and Redis. If it did not, a single database
blip would report `DOWN` on all three replicas at once and the kubelet would restart
the entire deployment — turning a recoverable degradation into an outage. Redis is
excluded from readiness too, because rate limiting and the idempotency replay cache
both degrade open by design.

Both probe paths are served anonymously. The kubelet sends no credentials, so a `401`
there restart-loops every pod; this was a real defect in the security configuration
before these manifests existed.

## Database migrations

Flyway runs inside the application at startup. With three replicas starting at once,
PostgreSQL's advisory lock serialises them: the first migrates, the other two block
until it commits and then find nothing to do. Nothing is corrupted, but the second
and third pods sit in startup for as long as the migration takes — which is why the
startup probe allows up to five minutes and the liveness probe does not begin until
it passes.

The alternative is a `Job` or an init container with `spring.flyway.enabled=false` in
the application. That is the better answer for a large schema or a migration that
takes a lock on a hot table, because it makes the migration a separate, observable
step with its own failure mode. It is not used here because Kubernetes has no native
way to order a Job before a Deployment without Helm hooks or Argo sync waves, and
that complexity is not worth it at this schema size. If a migration ever needs a
maintenance window, move it out of the application first.

## Exercises

These are the behaviours the manifests exist to demonstrate. Each one is a thing to
be able to explain, not just run.

### Self-healing

```bash
kubectl -n corebank get pods -w &
kubectl -n corebank delete pod "$(kubectl -n corebank get pod -l app.kubernetes.io/name=corebank-api -o jsonpath='{.items[0].metadata.name}')"
```

The ReplicaSet observes two pods against a desired three and creates a replacement.
Nothing "detects the failure" in the sense of an alert firing; the controller is
continuously reconciling actual state toward desired state, and a deleted pod is just
a difference to close.

### Zero-downtime rollout

```bash
# Keep requests in flight during the rollout and watch for a non-200. Run them from
# inside the cluster, against the Service, so they cross every pod: a port-forward stays
# on one pod and would drop when that pod is replaced. Probe an application path, not
# /actuator: the management port is private and is not on the Service.
kubectl run rollout-probe --rm -i --restart=Never --image=curlimages/curl -- \
  sh -c 'while true; do curl -s -o /dev/null -w "%{http_code}\n" http://corebank-api.corebank.svc.cluster.local/; sleep 0.2; done'

# In another terminal:
kubectl -n corebank set image deployment/corebank-api app=ghcr.io/maihuyhoang592004/corebank-api:<new-sha>
kubectl -n corebank rollout status deployment/corebank-api
```

If metrics-server is installed the HPA is live, and it was observed scaling the Deployment
from 3 to 6 while a rollout was in progress, so a rollout can end with more replicas than
it started with and take longer than the replica count at the start suggests.

`maxUnavailable: 0` adds a pod before retiring one, so capacity never dips. The
`preStop` sleep matters as much: endpoint removal and `SIGTERM` race each other, and
without the pause a terminating pod stops accepting connections while kube-proxy is
still sending it traffic. Graceful shutdown then lets in-flight money commands finish
rather than being killed while holding row locks.

### Rollback

```bash
# Roll out something that cannot become ready.
kubectl -n corebank set image deployment/corebank-api app=ghcr.io/maihuyhoang592004/corebank-api:does-not-exist
kubectl -n corebank rollout status deployment/corebank-api --timeout=90s   # fails

kubectl -n corebank rollout undo deployment/corebank-api
kubectl -n corebank rollout status deployment/corebank-api
```

The failed rollout never takes the service down, because the new pod never passes
readiness and `maxUnavailable: 0` means no healthy pod was retired to make room for
it. `revisionHistoryLimit: 5` is what gives `rollout undo` somewhere to go back to.

### Scaling

```bash
kubectl -n corebank scale deployment/corebank-api --replicas=5
kubectl -n corebank get hpa corebank-api -w
```

The HPA needs metrics-server, which kind does not ship — `hpa.yaml` has the install
command. Without it the HPA reports `<unknown>` and simply never acts.

## OpenShift

The base does not apply on OpenShift. `deploy/openshift/` is an overlay on top of it
that fixes the three things that stop it, and nothing else.

### Why the base fails there

**Arbitrary UIDs.** The `restricted-v2` SCC gives each namespace a UID range and runs
every container as a UID from it. Any pod that names a specific `runAsUser` or
`fsGroup` is rejected.

The application already complies: its image runs as a non-root user, gives group 0
the same rights as the owner, and `deployment.yaml` asks for `runAsNonRoot` without
naming a UID, so the platform picks one. That was checked rather than assumed, and it
is why the overlay contains no patch for the Deployment.

`postgres.yaml` does not comply: it pins `runAsUser: 999` and `fsGroup: 999`, because
`postgres:16-alpine` needs its data directory owned by the postgres user and cannot
start as an unknown UID. Loosening the security context does not help — the image is
what cannot run, not the policy. So the overlay stops running that image here.

**Routing.** OpenShift admits external traffic with `Route`. The base's `Ingress`
names an nginx ingressClass that does not exist on OpenShift.

**The Namespace object.** On the Red Hat Developer Sandbox a user works inside a project
that already exists and cannot read or create namespaces, so applying the base's
`Namespace` fails with `Forbidden ... cannot get resource "namespaces"`. Every other
object is refused too while the overlay still says `namespace: corebank`, because the
project has another name. Both were observed on a live Sandbox; see
`docs/evidence/openshift-runtime-verification.md`.

### What the overlay changes

| Object | Change | Why |
|---|---|---|
| StatefulSet `corebank-postgres` | deleted | cannot run under an arbitrary UID |
| Service `corebank-postgres` | deleted | nothing left to select |
| ConfigMap `corebank-config` | `SPRING_DATASOURCE_URL` → `postgresql:5432`, `COREBANK_ENVIRONMENT` → `openshift` | point at a database provisioned outside the overlay |
| Ingress `corebank-api` | deleted | wrong object for this platform |
| Route `corebank-api` | added | edge TLS, HTTP redirected, router timeout 60s |
| Namespace `corebank` | deleted | a project-scoped user cannot create or read namespaces |
| Deployment, Service, HPA, PDB | unchanged | already valid under `restricted-v2` |

The router timeout is the one number worth explaining. OpenShift's default is 30s,
where the base Ingress allows 60s. A money command can wait on a row lock; if the
router cuts the connection at 30s, the caller sees a failure for a transaction that
is still running and may yet commit. The Route raises it back to 60s so the two
paths behave the same.

Deleting the database is not a workaround dressed up as a decision — but it is also
not a loss. A ledger does not belong in a hand-rolled single-replica StatefulSet with
no failover, no backups and no point-in-time recovery, and `postgres.yaml` says so
about itself. On OpenShift the database comes from the Developer Catalog, whose
PostgreSQL template uses a Red Hat image built for arbitrary UIDs, or from a managed
instance.

### Deploying it

The project has to exist first. Where you can create one, `oc new-project corebank` gives
the overlay's default name. Where you cannot (the Developer Sandbox gives you a project
named after your account), use the one you have and substitute its name at render time
instead of editing the overlay.

```bash
PROJECT=$(oc project -q)        # or: oc new-project corebank && PROJECT=corebank
SUBST="s/^\(\s*\)namespace: corebank$/\1namespace: $PROJECT/"

# The password below and the one in the Secret must match. Nothing checks this for
# you; a mismatch shows up as pods that never pass readiness.
sed "$SUBST" deploy/kubernetes/secret.yaml | oc apply -f -

# The template's default is PostgreSQL 10, which is end of life. 15-el9 is the newest
# tag the Sandbox catalog carries; the application is tested against 16.
oc new-app postgresql-persistent \
  -p POSTGRESQL_VERSION=15-el9 \
  -p POSTGRESQL_DATABASE=corebank \
  -p POSTGRESQL_USER=corebank \
  -p POSTGRESQL_PASSWORD=<same value as SPRING_DATASOURCE_PASSWORD>

# The template starts PostgreSQL with max_connections=100 and has no parameter for it.
# The application needs 170 (see "The database connection ceiling" below), so set it
# before the application is deployed. This restarts the database once.
oc set env dc/postgresql POSTGRESQL_MAX_CONNECTIONS=200
oc rollout status dc/postgresql

kubectl kustomize deploy/openshift | sed "$SUBST" | oc apply -f -
oc rollout status deployment/corebank-api
oc get route corebank-api
```

`oc apply -k deploy/openshift` works only where the project really is called `corebank`.
Elsewhere it is refused, because the overlay's `namespace: corebank` names a project the
user has no rights in.

`oc new-app postgresql-persistent` still creates a `DeploymentConfig`, which OpenShift
has deprecated since 4.14. It works, and it is the Red Hat image that runs under an
arbitrary UID, so the overlay leaves it alone; it is a reason to move the database to a
managed instance rather than something to rewrite here.

#### The database connection ceiling

Each application pod's Hikari pool holds its connections while idle, so pods cost
connections whether or not they serve traffic. With the HPA at its maximum of 6, one
extra pod during a rollout and one still terminating, that is 8 pods x 20 + 10 reserve =
170 connections; `check-connection-budget.sh` prints the figure for this overlay. The
catalog database's default of 100 does not cover it: on a Developer Sandbox the sixth
pod the HPA created could not start, because Flyway got

```
FATAL: remaining connection slots are reserved for non-replication superuser connections
```

and the pod sat in `CrashLoopBackOff` while the other five served traffic. Setting
`POSTGRESQL_MAX_CONNECTIONS=200`, as in the steps above, is what fixed it. Any managed
database used instead needs `max_connections` of at least the figure the script prints.
The template's 512Mi memory limit was enough at the 121 client connections observed with
that setting (304Mi used); the full 170 was not exercised.

If your database is not the Service named `postgresql`, edit `SPRING_DATASOURCE_URL`
in the overlay's ConfigMap patch first. Getting it wrong fails safe: readiness gates
on the database, so the pods stay out of the Service rather than accepting money
commands they cannot finish.

### What is checked, and what is not

CI renders the overlay and validates it strictly on every push. `Route` is in no
schema catalogue `kubeconform` ships with, so its schema is vendored under
`deploy/openshift/schemas/` — validating every object except the one that is specific
to the platform would be a check that cannot fail. That schema was tested against a
deliberately misspelled enum and rejected it.

That is the whole of the evidence. **The overlay has never been applied to a live
OpenShift cluster.** Rendering and schema validation catch a malformed manifest; they
say nothing about whether the SCC admits the pods, whether the router behaves as
described, or whether the catalog database works as assumed.

### Not done yet

These are gaps, not decisions. The section below this one lists the things that are
absent on purpose.

1. **Never run on a real cluster.** As above — rendered and validated only. Every
   claim here about SCC admission and router behaviour is reasoning from the docs,
   not an observation.
2. **No database manifest on this path.** The overlay deletes PostgreSQL and expects
   one to exist; nothing in the repository provisions it. The password has to be kept
   in sync by hand between `oc new-app` and `secret.yaml`, and `secret.example.yaml`
   still carries a `POSTGRES_PASSWORD` key that nothing on this path reads.
3. **The Route has no host and no certificate of its own.** OpenShift generates the
   hostname and the router serves its default wildcard certificate. Workable for a
   lab, not for a named domain.
4. **Resource footprint never checked against a Developer Sandbox quota.** Three
   replicas request 750m CPU and 1.5Gi and cap at 3 CPU and 3Gi, and the PDB wants 2
   of 3 available. Whether that fits the Sandbox's limits is untested; the overlay
   patches neither the replica count nor the HPA's `minReplicas: 3`.
5. **No telemetry leaves the cluster yet.** `COREBANK_OTLP_ENABLED` is still
   `"false"`. `deploy/observability/kubernetes/` now carries manifests for an
   OpenTelemetry Collector that forwards to Dynatrace over OTLP, but they have never
   been applied to a cluster and the switch is left off until they have — turning
   export on with nothing answering produces a stream of export failures and no
   telemetry. Prometheus, Tempo and Grafana stay docker-compose-only, deliberately.
   Until that cutover this is still the largest gap between what the application can
   emit and what the deployment actually collects.
6. **Nothing scrapes the metrics endpoint.** The pods carry `prometheus.io/*`
   annotations, but the overlay creates no `ServiceMonitor` and no credentials
   Secret, and the endpoint requires authentication.
7. **Customer-secret endpoints return 503.** `corebank.security.master-key-b64` is
   unset everywhere under `deploy/`, and `CustomerSecretCryptoService` answers
   `SERVICE_UNAVAILABLE` without it. This applies to every deployment path, not just
   this one.
8. **No service mesh, and no progressive delivery.** Traffic goes Route → Service →
   pods. There is no mTLS between workloads, no canary or blue/green split, and no
   per-request routing. The rollout safety here comes from `maxUnavailable: 0` and
   the probes, which is a different and weaker guarantee.

## Deliberately not here

- **Database high availability.** `postgres.yaml` is one instance with one volume. It
  is the dependency under test, not a reference deployment. Production uses a managed
  service or an operator that handles failover and point-in-time recovery.
- **TLS.** The Ingress serves plain HTTP. Adding cert-manager is a solved problem and
  would not demonstrate anything about this application.
- **Kafka.** Disabled via `COREBANK_KAFKA_ENABLED=false`. Money commands still write
  outbox rows to PostgreSQL; only async publication and projection are paused.
- **Secret management.** Kubernetes Secrets are base64, not encryption. A real
  deployment sources these from a secret manager.
