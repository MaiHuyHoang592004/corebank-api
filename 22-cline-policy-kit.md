# 22. Cline Policy Kit

## Purpose
This file defines the policy assets and how they should be used in the repo.

## Required repo assets
### 1. `.clineignore`
Use it to exclude:
- `.env*`
- `secrets/`
- `*.pem`, `*.key`
- `build/`, `target/`, `dist/`
- `node_modules/`
- database dumps
- large logs and artifacts
- generated coverage and reports

### 2. `CLINE_COMMAND_PERMISSIONS`
Use an allowlist approach.

Recommended dev allowlist:
- `git status`
- `git diff *`
- `git add *`
- `git commit *`
- `./mvnw test *`
- `./mvnw -q test *`
- `./mvnw spotless:check *`
- `docker compose ps`
- `docker compose logs *`

Recommended dev denylist:
- `git push *`
- `rm -rf *`
- `sudo *`
- `curl *`
- `ssh *`
- `scp *`
- `psql *` against non-local URLs

### 3. Hook before attempt completion
Before Cline can finish a task, run a small verification script.
At minimum, the hook should:
- confirm the workspace is clean enough to inspect
- run the relevant tests or dry-run command
- fail the completion step if tests fail

### 4. Workflow markdowns
Workflows should be explicit and task-specific.
This pack includes:
- feature delivery
- schema change
- bugfix

## Policy decisions for this repository
- Cline may **read widely** but should **write narrowly**.
- Cline may run local tests without approval if marked safe.
- Cline may not bypass migrations by editing the database manually.
- Cline may not push directly to remote.
- Cline may not enable new MCP servers without approval.
- Cline must treat this repo as finance-sensitive.
