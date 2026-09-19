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

deploy/openshift/            overlay — see "Notes on OpenShift" below
├── kustomization.yaml
├── route.yaml
└── schemas/                 vendored Route schema, for kubeconform in CI
```

## Bring it up

```bash
kind create cluster --name corebank --config kind-cluster.yaml

# Ingress controller. Skip if you only want port-forward.
kubectl apply -f https://raw.githubusercontent.com/kubernetes/ingress-nginx/main/deploy/static/provider/kind/deploy.yaml
kubectl -n ingress-nginx wait --for=condition=ready pod \
  --selector=app.kubernetes.io/component=controller --timeout=180s

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

The package is private by default, so the cluster needs a pull secret unless the
package visibility is set to public in the repository's package settings:

```bash
kubectl -n corebank create secret docker-registry ghcr \
  --docker-server=ghcr.io --docker-username=<github-user> --docker-password=<token>
kubectl -n corebank patch serviceaccount default \
  -p '{"imagePullSecrets":[{"name":"ghcr"}]}'
```

Then add `127.0.0.1 corebank.local` to `/etc/hosts` and open
`http://corebank.local/dashboard/`, or skip the Ingress entirely:

```bash
kubectl -n corebank port-forward svc/corebank-api 8080:80
```

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
# Keep a request in flight during the rollout and watch for a non-200.
while true; do curl -s -o /dev/null -w '%{http_code}\n' http://corebank.local/actuator/health/readiness; sleep 0.2; done &

kubectl -n corebank set image deployment/corebank-api app=ghcr.io/maihuyhoang592004/corebank-api:<new-sha>
kubectl -n corebank rollout status deployment/corebank-api
```

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

## Notes on OpenShift

The base does not apply on OpenShift. `deploy/openshift/` is an overlay on top of it
that fixes the two things that stop it, and nothing else.

**Arbitrary UIDs.** The `restricted-v2` SCC assigns each namespace a UID range and
runs every container as a UID from it, rejecting any pod that asks for a specific
`runAsUser` or `fsGroup`. The application is fine as it stands: the image runs as a
non-root user, gives group 0 the same rights as the owner, and the Deployment sets
`runAsNonRoot` without naming a UID — so the platform picks one. It also drops all
capabilities, disables privilege escalation and runs read-only with a writable
`emptyDir` at `/tmp`.

`postgres.yaml` is not fine: it pins `runAsUser: 999` and `fsGroup: 999`, because
`postgres:16-alpine` needs its data directory owned by the postgres user and cannot
run as an unknown UID. Relaxing the security context would not help — the image is
the problem. So the overlay removes the StatefulSet and its Service and expects the
database to come from the Developer Catalog (whose PostgreSQL template uses a Red Hat
image built for arbitrary UIDs) or from a managed instance. That is closer to how
this would really run anyway; `postgres.yaml` says as much about itself.

**Routing.** OpenShift admits traffic with `Route`, not `Ingress`. The overlay
deletes the Ingress and adds a Route with edge TLS, an HTTP redirect, and the router
timeout raised from its 30s default to the 60s the base Ingress uses — a money
command that waits on a row lock must not be cut off by the router while the
transaction it started is still running.

```bash
oc new-project corebank
oc apply -f deploy/kubernetes/secret.yaml

# Provision a database, then point the overlay at it. The overlay defaults to a
# Service named `postgresql`, which is what the catalog template creates.
oc new-app postgresql-persistent \
  -p POSTGRESQL_DATABASE=corebank \
  -p POSTGRESQL_USER=corebank \
  -p POSTGRESQL_PASSWORD=<same as the Secret>

oc apply -k deploy/openshift
oc -n corebank rollout status deployment/corebank-api
oc get route corebank-api
```

If the database host differs, change `SPRING_DATASOURCE_URL` in the overlay's
ConfigMap patch before applying. Getting it wrong fails safe rather than quietly:
readiness gates on the database, so the pods stay out of the Service instead of
accepting money commands they cannot complete.

CI renders and validates this overlay alongside the base. `Route` is not in any
schema catalogue `kubeconform` knows about, so its schema is vendored under
`deploy/openshift/schemas/` — validating every resource except the one that is
specific to the platform would be a check that cannot fail.

This overlay is validated, not yet deployed: it renders and passes strict schema
validation, but it has not been applied to a live OpenShift cluster.

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
