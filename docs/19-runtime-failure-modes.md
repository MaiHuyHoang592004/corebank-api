# 19. Runtime Failure Modes and Operating Rules

This file describes expected behavior when the system or its dependencies fail.
The goal is not to avoid all failures. The goal is to fail predictably without corrupting financial truth.

## Core rule
If any subsystem conflicts with ledger truth, ledger wins.

## Dependency roles
- PostgreSQL: authoritative financial truth
- Redis: optional performance/coordination layer
- Kafka: async transport only
- Read models: query optimization only

## Failure mode: PostgreSQL unavailable
Expected behavior:
- money-moving commands fail closed
- no fallback to Redis/read model balances
- outbox publication stops because source records cannot be read safely

Never do:
- serve stale cached balances as authoritative
- accept transfers and queue them in memory for later posting

## Failure mode: Redis unavailable
Expected behavior:
- system may lose cache, rate-limit optimization, or coordination helpers
- authoritative money flows should still work if DB is healthy
- degraded mode is acceptable if performance impact is controlled

Never do:
- block financial operations only because cache is missing, unless a specific control explicitly depends on Redis

## Failure mode: Kafka unavailable
Expected behavior:
- committed financial writes remain committed
- outbox rows remain pending
- async side effects are delayed, not lost

Required behavior:
- publisher retries safely
- duplicate downstream publication is tolerated by idempotent consumers

## Failure mode: missing partition for event table
Expected behavior:
- this must be prevented by automation before it happens
- if it still happens, fail fast, alert loudly, and route to a known operational procedure

Recommended mitigation:
- pre-create future partitions
- optional default partition only as safety net, not long-term design

## Failure mode: read model lagging behind
Expected behavior:
- UI/query freshness degrades
- financial writes continue if DB is healthy
- decisions about available balance must still use authoritative balance path

Never do:
- authorize or reject money movement using only read-model data

## Failure mode: deadlock / serialization conflict
Expected behavior:
- command fails or retries according to safe retry policy
- no partial financial state leaks out

Required design:
- deterministic lock ordering
- retry only for safe transient DB errors
- idempotent command handling

## Failure mode: outbox worker crash during publish
Expected behavior:
- claimed rows become available again after lock expiry or heartbeat failure
- duplicate publish is tolerated by idempotent consumers
- no committed business transaction is rolled back because publisher failed later

## Failure mode: system mode = `EOD_LOCK` or `MAINTENANCE`
Expected behavior:
- new money-moving commands are blocked unless explicitly whitelisted
- read operations continue where safe
- batch/reconciliation jobs may continue depending on mode policy

## Failure mode: sensitive data exposure risk
Expected behavior:
- DB should not store plaintext of highly sensitive fields when avoidable
- application-layer encryption and key versioning should allow controlled rotation

## Failure mode: hot account saturation
Expected behavior:
- write pressure is distributed across balance slots
- reads aggregate slot totals
- no caller assumes one-row-per-account if account is marked hot
- configured strategy and runtime-applied strategy are explicit in hot-account read responses:
  - `selectionStrategy=HASH` -> `runtimeSelectionStrategyApplied=HASH`, `runtimeStrategySemantics=NATIVE_HASH`
  - `selectionStrategy=ROUND_ROBIN|RANDOM` -> runtime currently applies `HASH` with `runtimeStrategySemantics=HASH_FALLBACK`
- fallback semantics are intentional in the current slice and must not be interpreted as native runtime support for `ROUND_ROBIN` or `RANDOM`

## Operational rules
1. Never repair ledger truth by editing historical rows.
2. Prefer compensating actions over destructive mutation.
3. Every replayable async path must be idempotent.
4. Every operator action with financial impact must be auditable.
5. Every batch job must be safe to resume or rerun.

---

# Verified failure behaviour

Everything above this line is reasoning about how the system should behave. This
section is what it actually did when the failures were induced, on a real PostgreSQL,
with the commit that records each finding.

The method was to pick failures whose outcome nobody knew in advance. Deleting a pod
to watch Kubernetes recreate it proves that Kubernetes works as documented; it proves
nothing about this system. Each scenario below was chosen because it could plausibly
have gone either way, and two of them went badly.

The invariant checked after every scenario is the same one that matters in a bank:
total money across the accounts involved is unchanged, no journal is unbalanced, and
no balance is negative. **That invariant held in every scenario, including the two
that failed.** What broke was availability and recoverability, never correctness.

## Abrupt termination during money commands

**Induced:** 60 concurrent transfers, `SIGKILL` to the JVM once the money path was
demonstrably busy.

**Result, before the fix:** money was correct — PostgreSQL rolled back every
uncommitted transaction — but four idempotency keys were left at `IN_PROGRESS`
forever. The commands had not happened and could never be retried: the same key was
refused on every later attempt, and the maintenance job deletes only `SUCCEEDED` and
`FAILED` rows, so nothing ever cleared them. Recovery meant deleting rows by hand.

For a system whose headline claim is idempotency, that is the worst shape of bug: the
control that exists to make retries safe was the thing that made recovery impossible.

**Fix:** a claim now records when it was taken, and a claim older than the lease
belongs to a process that is no longer running, so a retry may take it over. The
takeover is a conditional update, so concurrent retries cannot both win.
`corebank_idempotency_stale` counts claims past the lease; that is the number worth
alerting on, because `in_flight` on its own is just traffic.

**Verified after the fix:** the same four stranded keys, replayed with their original
payloads, completed successfully, with the takeover recorded as a warning. Money still
conserved, no unbalanced journals.

## A retry storm on one idempotency key

**Induced:** 14 concurrent requests sharing a single idempotency key against a freshly
started instance.

**Result:** the instance stopped serving for 32 seconds. Ten requests returned HTTP
500 with connection-pool timeouts. Exactly one journal was posted, so money was still
correct, but the service was effectively down for the duration.

The cause was not lock contention, which was the first hypothesis and was wrong. The
pool metrics showed all connections leased and thirteen threads queueing, with no lock
timeouts at all. A money command holds two connections at once — the business
transaction, and the `REQUIRES_NEW` transaction that records the idempotency outcome
inside it — so once concurrent commands reach the pool size, every one holds a
connection while waiting for a second that nobody can release. The pool starves
itself and recovers only when the connection timeout expires.

Proving it took one measurement: the identical burst against a larger pool.

| Pool size | Elapsed | Responses | Pool timeouts | Journals |
|---|---|---|---|---|
| 10 (default) | 32s | 1 × 200, 3 × 400, 10 × 500 | 10 | 1 |
| 40 | 1s | 1 × 200, 13 × 400 | 0 | 1 |

**Fix:** the pool is now sized explicitly, with the arithmetic and the reason written
next to the number. The real fix is to stop holding two connections per command; until
that refactor the bound keeps the failure out of reach rather than removing it, and
the configuration says so.

**What makes this worth knowing:** a retry storm on one key is exactly the traffic
idempotency exists to absorb, and a freshly started instance is exactly what a client
retry storm meets after a rollout.

## Concurrent duplicates of the same command

**Induced:** 8, then 12, identical concurrent requests with one key.

**Result:** exactly one journal every time, so exactly-once held. One request in the
first run returned HTTP 500 rather than a clean rejection. The cause was a
unique-violation being caught and the row then read back inside the same transaction —
but a unique violation aborts the PostgreSQL transaction, so every later statement in
it fails with `25P02`. The loser of the claim race learned it had lost by receiving a
server error, and a client that retries on 5xx would have kept hammering.

**Fix:** the claim is an `INSERT ... ON CONFLICT DO NOTHING` whose row count decides
the winner, so losing the race never poisons a transaction.

**Verified after the fix:** twelve concurrent duplicates, one journal, no 500s.

## Things that did not break

Worth recording, because a scenario that finds nothing is still a result:

- Twelve concurrent transfers with **distinct** idempotency keys completed cleanly.
  The starvation above needs commands piling into the same claim, not load alone.
- Every scenario preserved total money, journal balance and non-negative balances,
  including the two that produced HTTP 500s.

## Known rough edge

A duplicate that arrives while the original is still running is answered with HTTP
400. It is not a bad request; the correct answer is 409, and the distinction matters to
a client deciding whether to retry. The error mapping is a substring match on the
exception message across several controllers, so fixing it properly means giving the
API typed errors rather than patching one branch.
