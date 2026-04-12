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
