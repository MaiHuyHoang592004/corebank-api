# 21. Cline Operating Model For This Repository

## Why this file exists
This repository is too sensitive to let an agent improvise its own workflow.
The repository contains money-moving logic, accounting state, and operational controls.
Therefore, Cline must follow a predictable operating model.

## Core principle
Use **Plan -> Act -> Test -> Review**.

### Plan mode is mandatory when
- touching ledger, payments, deposits, lending, approvals, migrations, or outbox
- changing more than one module
- refactoring existing flows
- debugging unclear failures
- introducing new dependencies, tools, or MCP servers

### Act mode is allowed when
- the plan is accepted
- the file scope is clear
- the change is small enough to verify quickly
- the required tests are known

### Checkpoints are mandatory when
- editing SQL/Flyway migrations
- touching ledger or balance logic
- changing hold/capture/void flow
- introducing retries, async changes, or worker logic
- editing hooks/policies/command permissions

## Default execution loop
1. Read relevant docs first.
2. Enter Plan mode.
3. Produce a plan with exact files to inspect.
4. Ask for approval if scope is ambiguous or high-risk.
5. Enter Act mode.
6. Change one logical slice at a time.
7. Run minimal tests after each slice.
8. Summarize exact changes, tests run, and residual risk.

## Approval policy
### Auto-approve allowed
- reading files
- searching code
- git status / git diff
- safe test and lint commands
- generating docs inside the repo

### Manual approval required
- file writes in financial modules
- shell commands with side effects
- database commands
- docker compose up/down if it affects local state unexpectedly
- browser automation
- MCP usage
- any git push

## Completion rule
Cline must not claim completion until:
- code changes are done
- tests relevant to the scope were run
- no invariant is knowingly broken
- the summary includes what changed, what was tested, and what remains risky
