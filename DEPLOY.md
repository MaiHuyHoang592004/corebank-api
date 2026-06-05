# CoreBank API — Deployment Guide

## Deploy to Render (Recommended First Deploy)

### Option A: Blueprint (Auto-provision DB)

1. Fork/push repo to GitHub
2. Go to https://dashboard.render.com → Blueprints
3. Connect your repo — Render reads `render.yaml` and provisions:
   - Web Service (Docker)
   - PostgreSQL 16 (free tier)
4. Wait ~5-10 min for first build
5. Visit `https://corebank-api.onrender.com/`

### Option B: Manual Setup

1. Create a **PostgreSQL** database on Render (free tier, version 16)
2. Create a **Web Service** (Docker runtime):
   - Build Command: (leave empty — uses Dockerfile)
   - Health Check Path: `/actuator/health`
   - Dockerfile Path: `./Dockerfile`
3. Set env vars (see `.env.example`):
   - `SPRING_PROFILES_ACTIVE=showcase`
   - `SPRING_DATASOURCE_URL` → construct from Render DB connection info
   - `SPRING_DATASOURCE_USERNAME` → from Render DB
   - `SPRING_DATASOURCE_PASSWORD` → from Render DB
   - `COREBANK_KAFKA_ENABLED=false`
   - `JAVA_OPTS=-Xmx384m`

### DB URL Conversion

Render provides: `postgres://user:pass@host:5432/dbname`
Spring Boot needs:  `jdbc:postgresql://host:5432/dbname?user=user&password=pass`

**The app automatically converts `postgres://` URLs to JDBC format at startup** via `RenderDatabaseUrlEnvironmentPostProcessor`. No manual URL rewriting needed for Blueprint deploys.

If deploying manually, you can either:
- Set `SPRING_DATASOURCE_URL` in `postgres://` format (auto-converted), or
- Set it directly in JDBC format for clarity

### Verify Deploy

```powershell
$URL = "https://your-app.onrender.com"
curl "$URL/actuator/health"
curl "$URL/dashboard/"
```

### Free Tier Notes

- Web service sleeps after 15 min inactivity — first request may take 30-60s
- PostgreSQL expires after 90 days
- 512 MB RAM — keep `JAVA_OPTS=-Xmx384m`

## Deploy to Railway (Alternative)

Same approach — use Dockerfile, set env vars. Railway provides `DATABASE_URL` in `postgres://` format. Convert to JDBC format for `SPRING_DATASOURCE_URL`.

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
