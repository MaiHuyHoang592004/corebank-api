# 23. Cline Workflows

## Workflow A: Feature delivery (default)
Best for normal backend feature work.

1. Plan mode
2. Read relevant docs and list files
3. Small Act step
4. Run local tests
5. Repeat until complete
6. Summarize changes and propose commit message

Use this for:
- new endpoint
- service logic
- read model changes
- validation and auth changes

## Workflow B: Schema change
Best for DB migrations and accounting-sensitive work.

1. Plan mode only at first
2. Read schema + invariants + workflows docs
3. Explain migration impact and rollback story
4. Create Flyway migration
5. Update repositories/services/tests
6. Run integration tests
7. Review diff carefully before completion

Use this for:
- adding columns or tables
- changing constraints
- changing posting rules or balance behavior
- partitioning or snapshot changes

## Workflow C: Bugfix / incident-style debugging
Best for failures or balance mismatches.

1. Reproduce with smallest failing test/command
2. Check logs and failing layer
3. Create checkpoint
4. Fix the minimal root cause
5. Re-run targeted tests
6. Add regression test
7. Summarize root cause and guardrail

Use this for:
- wrong balance
- idempotency bug
- hold/capture state mismatch
- worker/projector failures

## Workflow D: CI/headless automation
Only after repo policies are in place.

1. Run in sandbox/ephemeral environment
2. Use command allowlist only
3. Output JSON/log artifacts
4. Never expose prod secrets
5. Human gate before merge

Use this for:
- fixing failing tests
- auto-reviewing diffs
- generating commit messages
- mass doc updates
