# Dynatrace Incident RCA: row-lock contention on the transfer path

A controlled, reversible database incident was induced while Dynatrace was collecting telemetry from CoreBank on
Kind, then investigated from the Dynatrace side and corroborated from the cluster. The point is to tell three
things apart that all look like "the database is slow": slow connection acquisition, slow SQL, and lock
contention. Times are UTC unless stated; the Dynatrace UI showed UTC+7 (15:35 there is 08:35 here).

This is a single lab incident on a trial tenant and a closed-loop load generator. It is an investigation
method demonstrated once, not a statement about production behaviour.

## Scenario and safety

| | |
|---|---|
| Cause induced | a `psql` session inside the PostgreSQL pod ran `BEGIN; SELECT … FROM customer_accounts WHERE customer_account_id IN (<the two hot accounts>) ORDER BY … FOR UPDATE; SELECT pg_sleep(45); COMMIT;` |
| Why this statement | it is the row-lock statement the transfer path itself runs (`AccountBalanceRepository.lockByIdsInDeterministicOrder`), so holding the same rows blocks every transfer on them |
| Window | lock held 08:35:28.0 – 08:36:16.1 (48 s including client overhead; the sleep was 45 s) |
| Reversibility | the holder `COMMIT`s; nothing was deleted, no configuration changed, no pod restarted |
| Load | in-cluster client through the Service, 2000 transfers, 8 workers, ~10 req/s target, after a 700-request warm-up so the HPA was at 6 replicas and the JVMs were warm |
| Baseline window | the 60 s before the lock |

Independent of Dynatrace, the run recorded every ~3 s: PostgreSQL sessions and their wait events, the committed
journal count, and each pod's Hikari `active`/`idle`/`pending` gauges.

## Baseline (60 s before the lock)

| | |
|---|---|
| Requests completed | 599, all `200` |
| Rate | 10.0 req/s |
| Latency | p50 90 ms, p95 118 ms, max 241 ms |
| PostgreSQL | 121 connections, 0 sessions waiting on a lock |
| Hikari (6 pods, pool 20 each) | `active` 0–1, `pending` 0, `idle` 119–120 |
| Committed journals | 9.5–10.6 per second |

## The incident, as Dynatrace shows it

- **Symptom.** Requests jump from ~100 ms to ~45 s. In Distributed Tracing, filtering `Duration >= 10s` returns
  **exactly 8 requests**, all `http post /api/transfers/internal`, starting 15:35:28.234–.939 (08:35:28Z) and
  lasting **44.56 s – 45.12 s**, each `Success` server-side.
- **Where the time goes**, in the 45.12 s trace:

  | Span | Start | Duration |
  |---|---|---|
  | `server` request | 15:35:28.234 | 45.12 s |
  | `connection` (main), event `acquired` | 15:35:28.305 | held ~45 s, `commit` event at 15:36:13.347 |
  | `query` #4, `SELECT customer_account_id, customer_id, product_id, ac…` (the `FOR UPDATE`) | 15:35:28.309 | **45.02 s** |
  | authenticate, authorize, the other three `query` spans, the second `connection` | | each ≈ 0 |

  So ~99.8 % of the request is one `QUERY` span, and `acquired` happened at the very start (4 ms before that query
  began). The 45 s `CONNECTION` span is long only because it *contains* the blocked query.
- **Metric side.** `corebank.ledger.journals.posted` is exported on a 60 s step and Dynatrace stores it at
  one-minute resolution, so it cannot locate a 45 s stall: its buckets over the run were
  `[175, 494, 489, 685, 488, 369]`, and I did not try to read a dip out of them. What the counter does support is
  its total, 2,700, which equals the journals the run committed. The stall itself is read from the traces above
  and from the second-by-second database recording below.

## Discriminating the three hypotheses

| Hypothesis | What it predicts | What was observed | Verdict |
|---|---|---|---|
| **Slow connection acquisition** (pool exhausted or pool wait) | `hikaricp.connections.pending` > 0, `active` near the max, `acquired` late in the `CONNECTION` span, other requests also slow | Hikari `pending` = **0** on every pod for the whole incident; `active` = 8 (max 3 on any pod) of 120; `idle` ≥ 112; `acquired` at the start of the span; PostgreSQL connections constant at 122 | **ruled out** |
| **Slow SQL** (bad plan, missing index, heavy scan) | the same statement is slow on its own; PostgreSQL shows CPU or I/O wait | uncontended, the identical `FOR UPDATE` statement runs in `Execution Time: 0.135 ms` (Seq Scan over a tiny table, `LockRows`); PostgreSQL wait events are `Lock`, not CPU or I/O | **ruled out** |
| **Lock contention** (another transaction holds the rows) | sessions waiting on `Lock`, waits growing linearly with wall time, one long `QUERY` span, throughput collapse | exactly **8** PostgreSQL sessions in `wait_event_type='Lock'`, oldest wait **3.1 s → 44.8 s** in step with the clock, the single 45.02 s `QUERY`, journals/s **10 → 0** | **confirmed** |

The "8" is the same number three ways: 8 slow requests in Dynatrace, 8 sessions waiting on a lock in PostgreSQL,
and 8 active Hikari connections; the load generator had 8 workers, each blocked on its own connection.

## Timeline from the cluster-side recording

| t (s after lock start) | PostgreSQL waiting on `Lock` | oldest wait | Hikari active (Σ) | pending (Σ) | journals/s |
|---|---|---|---|---|---|
| −2 | 0 | 0 | 0 | 0 | 10.6 |
| +4 … +43 | **8** | 3.1 → 41.9 s | 8 | **0** | **0** |
| +46 | 8 | 44.8 s | 3 | 0 | 0 |
| +50 | 1 | 0.1 s | 4 | 0 | 28.9 |
| +53 … +63 | 0–2 | ≈ 0 | 0–3 | 0 | 37 – 43 (backlog drained) |
| +66 onward | 0 | 0 | 0–3 | 0 | ≈ 10 |

## Remediation and recovery

- **Remediation applied:** the lock holder's `COMMIT` at 45 s. That is the equivalent of the long-running
  transaction finishing. It was time-boxed on purpose.
- **What an operator would do instead, not exercised here:** find the root blocker with `pg_blocking_pids()`
  against `pg_stat_activity`, then `pg_terminate_backend()` it. The recording counted 8 distinct pids listed as
  blockers; that is consistent with the lock holder plus the waiters queued ahead of each other (an inference from
  the count: the holder's own pid was not sampled). The root blocker is the one that is not itself waiting.
- **Latency recovered.** By completion time relative to the lock start (client-observed):

  | Window | Completed | p50 | p95 | Rate |
  |---|---|---|---|---|
  | −60 … 0 s (baseline) | 599 | 90 ms | 118 ms | 10.0 req/s |
  | 0 … 45 s (locked) | **2** | 86 ms | 134 ms | ≈ 0 |
  | 45 … 48 s (release) | 105 (the 8 blocked requests at 44.7–45.0 s, plus the backlog) | 210 ms | 44.7 s | 34 req/s |
  | 48 … 78 s | 672 | 169 ms | 425 ms | 22.4 req/s |

  The generator's queue of requests held back behind the blocked workers drained faster than the steady rate,
  which is why latency is elevated (p95 425 ms against 118 ms) for about 30 s after release; the run finished at
  about +79 s. The database-side throughput returned to ≈ 10 journals/s at about +66 s.
- **Pending connections:** never rose above 0, so there was nothing to recover.
- **Nothing was retried by the platform.** All 8 blocked requests completed successfully after release.

## Financial invariants after the incident

Totals unchanged; 0 negative balances, 0 unbalanced journals, 0 duplicate correlation ids; 22,260 committed
journals equal the 22,260 `SUCCEEDED` idempotency claims; 0 stale claims.

**The ambiguous request.** One of the 8 requests was cut off by the load generator's own 45 s client timeout
(`rca-svc-1208`, status 0) and yet the server committed it after the lock released: the idempotency claim was
`SUCCEEDED` with exactly one journal. Retrying with its exact body returned `200` with that same journal and left
the count at one, so a client that timed out and retried would not have moved the money twice.

## What this incident did not exercise

- **Higher concurrency than the pool.** With 8 concurrent requests the pools never filled. A lock held while more
  requests than connections arrive would add a *second* effect: requests waiting for a Hikari connection for up to
  its 30 s timeout and returning 500 (observed separately in `kubernetes-runtime-verification.md`), and the
  readiness check competing for the same pool. Nothing here bounds the wait for the lock itself: no `lock_timeout`
  or `statement_timeout` is configured on the transfer path, so a stuck lock is limited only by the client. That is
  an observation about the code and configuration, not something this run tested.
- **Open-loop traffic.** The generator is closed-loop (fixed workers); real arrivals keep coming while requests block.
- **Other causes.** One induced cause, one tenant, one run.
- **Alerting.** No Davis problem or alert was checked; the investigation was manual.
- Hikari values were corroborated from the pods, not read in Dynatrace (the series exist; see
  `dynatrace-apm-verification.md`).
