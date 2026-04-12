# 24. Cline Prompts And Task Templates

## Universal task template
Use this for most non-trivial tasks.

```text
You are operating inside a finance-sensitive Spring Boot + PostgreSQL repository.
Follow Plan -> Act -> Test -> Review.

Objective:
<goal>

Constraints:
- Do not bypass ledger invariants.
- Do not update balances directly unless the documented DB function allows it.
- Do not mutate append-only history.
- Do not change files outside the listed scope.
- Do not run destructive shell commands.

First respond in PLAN mode with:
1. assumptions
2. files to inspect
3. step-by-step plan (<=10 steps)
4. tests to run
5. rollback/checkpoint notes
```

## Feature prompt
```text
Implement <feature> using the default feature workflow.
Read only the minimum required files first.
After each meaningful code change, run the smallest relevant test.
At completion, provide:
- changed files
- why each change was needed
- tests executed
- remaining risks
```

## Schema prompt
```text
We need a schema change.
Start in PLAN mode.
Read database context and financial invariants first.
Explain:
- migration impact
- backfill need
- effect on historical data
- effect on read models/projectors
Then implement via Flyway migration only.
```

## Bugfix prompt
```text
Investigate and fix <bug>.
Do not refactor broadly.
Reproduce first, then fix the smallest root cause.
Add or update a regression test.
Use a checkpoint before risky changes.
```

## Review prompt
```text
Review the current diff for:
- ledger invariant risk
- idempotency regressions
- migration safety
- missing tests
- hidden coupling
Output findings grouped by severity.
```
