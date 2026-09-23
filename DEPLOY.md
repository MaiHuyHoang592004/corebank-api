# CoreBank API — Deployment Guide

## Deploy to Railway (primary)

Railway builds the repository's `Dockerfile` and redeploys on every push to the connected
branch. Build and deploy settings live in [`railway.json`](railway.json), so they are
versioned with the code; the steps below are the parts that can only be done in the
Railway dashboard.

### 1. Create the project

1. In Railway: **New Project → Deploy from GitHub repo** → `MaiHuyHoang592004/corebank-api`.
   Autodeploy needs a project member with a connected GitHub account that has contributor
   access to the repository.
2. In the same project: **+ New → Database → PostgreSQL**. Keep the default service name
   `Postgres`; the variable reference below uses it.

Railway detects `railway.json` and builds with the `Dockerfile`. The first build takes a few
minutes because Maven resolves every dependency; later builds reuse that layer.

### 2. Set the application service variables

| Variable | Value | Why |
|---|---|---|
| `DATABASE_URL` | `${{Postgres.DATABASE_URL}}` | Reference to the database's private URL. Rewritten to JDBC at startup, see [DB URL conversion](#db-url-conversion) |
| `SPRING_PROFILES_ACTIVE` | `showcase` | Disables Kafka auto-configuration and denies the destructive ops paths |
| `COREBANK_KAFKA_ENABLED` | `false` | No broker in this deployment; the outbox rows are still written to PostgreSQL |
| `RAILWAY_DEPLOYMENT_DRAINING_SECONDS` | `45` | Time between SIGTERM and SIGKILL for the previous deploy. The app shuts down gracefully within `spring.lifecycle.timeout-per-shutdown-phase` (30s); 45 matches `terminationGracePeriodSeconds` in `deploy/kubernetes/deployment.yaml` |
| `JAVA_OPTS` | `-Xmx768m` | Recommended. The image sizes the heap as 70% of the container's memory limit, and Railway bills memory by use, so an uncapped limit lets the heap grow for no benefit. 768 MB is well above what the app needs; it ran on 512 MB total |
| `COREBANK_ENVIRONMENT` | `railway` | Optional. Tags logs, metrics and traces with where they came from |

`PORT` is not set by hand: Railway injects it, and `server.port` already reads it.

Do not set `SPRING_DATASOURCE_URL` as well as `DATABASE_URL`. When both are present,
`SPRING_DATASOURCE_URL` wins, which is the right precedence but a confusing one to debug.

### 3. Expose it and choose the trigger branch

1. Application service → **Settings → Networking → Generate Domain**.
2. **Settings → Source**: set the trigger branch (normally `main`).
3. **Settings → Source → Wait for CI**: turn it on. `.github/workflows/ci.yml` runs on every
   push to `main`, so a commit whose test suite fails is not deployed.

Commits that only touch `docs/evidence/`, `docs/poc/`, `deploy/` or `.github/` do not
trigger a rebuild — see `watchPatterns` in `railway.json`. `README.md` and the top-level
`docs/*.md` are included because the build packages some of them into the jar for the
dashboard's document links, so an edit to them must reach the running image.

### 4. Verify

```bash
URL="https://<your-service>.up.railway.app"
curl -i "$URL/actuator/health/readiness"   # 200 once the database is reachable
curl -i "$URL/dashboard/"
```

In the deploy logs, look for this line near the top of startup:

```
[DatabaseUrlConverter] spring.datasource.url taken from DATABASE_URL: jdbc:postgresql://postgres.railway.internal:5432/railway
```

If it is missing, `DATABASE_URL` did not reach the service and the application is pointing at
its local default, `localhost:5433`. The readiness healthcheck then never returns `200` and
Railway marks the deploy failed after `healthcheckTimeout` (300s) rather than routing traffic to
it.

Railway calls the healthcheck only while a deploy is starting, not afterwards. It is not
monitoring.

### Optional: tag builds with the commit

The `Dockerfile` accepts `ARG APP_VERSION`, and Railway passes service variables with a
matching name as build arguments. Setting `APP_VERSION` to `${{RAILWAY_GIT_COMMIT_SHA}}`
should put the commit into the artifact version that telemetry reports. This has not been
verified on Railway; check the build log for `-Drevision=` followed by a sha.

## Deploy to Render (alternative)

`render.yaml` is kept so an existing Render deployment keeps working while Railway takes over.

Free-tier limits that motivated the move: the web service sleeps after 15 minutes idle, so the
first request can take 30–60s, and the free PostgreSQL instance expires after 90 days.

### Option A: Blueprint (auto-provision DB)

1. Go to https://dashboard.render.com → Blueprints
2. Connect the repo — Render reads `render.yaml` and provisions a Docker web service and
   PostgreSQL 16
3. Visit the URL Render assigns, of the form `https://corebank-api-<suffix>.onrender.com/`

### Option B: Manual setup

1. Create a **PostgreSQL** database (version 16)
2. Create a **Web Service** (Docker runtime), health check path `/actuator/health/readiness`
3. Set `SPRING_PROFILES_ACTIVE=showcase`, `COREBANK_KAFKA_ENABLED=false`, and
   `SPRING_DATASOURCE_URL` to the database's connection string (`postgres://` form is fine)

## DB URL conversion

Platforms hand out `postgres://user:pass@host:5432/dbname`. Spring Boot needs
`jdbc:postgresql://host:5432/dbname` and the user and password as separate properties.

`DatabaseUrlEnvironmentPostProcessor` does this at startup. It reads:

1. `SPRING_DATASOURCE_URL` — what Render's blueprint sets
2. otherwise `DATABASE_URL` — what Railway's PostgreSQL service publishes

A `postgres://` or `postgresql://` URL is rewritten to JDBC; the credentials are
percent-decoded into `spring.datasource.username` and `spring.datasource.password` and are not
put into the JDBC URL. A URL that is already JDBC is used as it is.

## Local Docker Compose

```powershell
docker compose up -d postgres redis
docker build -t corebank-api .
docker run --rm --network corebank-api_corebank-network -p 9090:9090 `
  -e SPRING_DATASOURCE_URL=jdbc:postgresql://corebank-postgres:5432/corebank `
  -e SPRING_DATASOURCE_USERNAME=corebank `
  -e SPRING_DATASOURCE_PASSWORD=corebank123 `
  corebank-api
```
