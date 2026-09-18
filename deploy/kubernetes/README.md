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

The image runs as a non-root user and gives group 0 the same rights as the owner,
which is what OpenShift's `restricted-v2` SCC requires: it assigns an arbitrary UID
in group 0 rather than the UID in the Dockerfile. The container also drops all
capabilities, disables privilege escalation and runs with a read-only root
filesystem, with a writable `emptyDir` mounted at `/tmp` for the JVM and Tomcat.

Route instead of Ingress:

```bash
oc new-project corebank
oc apply -f secret.yaml
oc apply -k .
oc expose service/corebank-api
oc get route
```

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
