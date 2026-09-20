# 32. Service Levels

This file defines what "working" means for this system in numbers, and what the
numbers are allowed to be before someone is woken up. It is written against metrics
that exist: every PromQL expression below was checked against the meters registered in
`BankingMetrics.java`, `ReadModelMetricsBinder.java` and `LedgerCommandService.java`,
and the recording rules were validated with `promtool check rules`. Where a query
cannot work as the application is currently configured, that is stated in the section
where the query appears rather than left for someone to discover from an empty graph.

**Every target here is proposed, not measured.** There is no production deployment and
no window of real traffic over which a percentile or an availability figure could be
claimed. The numbers are the ones this design is willing to be held to; the first month
of real traffic is what would turn them into commitments or show them to be wrong.

## Which journeys are worth an objective

The application exposes reporting, product catalogue, ops and dashboard routes as well
as money routes. Only the money routes get an SLO, and the reason is not that the
others do not matter but that an objective is a promise with a cost attached, and
promises spent on the wrong thing buy nothing.

A money command is different from the rest in one specific way: **its failure can leave
the system in a state the caller cannot resolve by retrying.** A failed report is
re-run. A failed transfer might have posted. That asymmetry is what an error budget is
for. These are the routes, verified against their controllers:

| Journey | Endpoint | Why it qualifies |
|---|---|---|
| Internal transfer | `POST /api/transfers/internal` | Posts a balanced journal. The canonical money command. |
| Authorize a hold | `POST /api/payments/authorize-hold` | Moves available balance without touching posted balance. |
| Capture a hold | `POST /api/payments/capture-hold` | Converts a hold into a posting. |
| Void a hold | `POST /api/payments/void-hold` | Releases reserved funds. |
| Refund | `POST /api/payments/refund` | Posts a reversing movement. |
| Open a deposit | `POST /api/deposits/open` | Creates a funded position. |
| Accrue interest | `POST /api/deposits/accrue` | Posts accrual journals. |
| Deposit maturity | `POST /api/deposits/maturity` | Settles a position. |
| Disburse a loan | `POST /api/lending/disburse` | Moves money out against a loan. |
| Repay a loan | `POST /api/lending/repay` | Moves money in against a loan. |

`GET /api/payments/orders` and `GET /api/payments/orders/{paymentOrderId}` sit under
the same path prefix and are therefore captured by the `uri` matcher below. That is a
known imprecision, noted rather than fixed: excluding them would mean either enumerating
every money route in every expression or adding a `method` filter that would also drop
any future money command that is not a `POST`. The reads are cheap and successful, so
their effect is to make availability look very slightly better than the write-only
figure would be.

Deliberately without an SLO:

- **`/api/ops/*` and `/api/reporting/*`.** Operator surfaces. Their unavailability is
  painful and is not a customer-visible money failure. They are covered by alerts, not
  by a budget.
- **Event delivery to downstream consumers.** This one is a gap rather than a decision.
  `OutboxDeadLetters` fires when an event is permanently lost, but there is no SLI for
  delivery latency or delivery success rate, so there is no number saying how far behind
  a consumer is allowed to be. Defining one needs a publisher-side timestamp the outbox
  does not currently record.

## The SLIs

All three are recorded in `deploy/observability/recording-rules.yml`, wired into
`deploy/observability/prometheus.yml` and mounted by
`deploy/observability/docker-compose.yml`. Rule names follow the
`level:metric:operations` convention, where the level names the aggregation the series
has already been reduced to — `money` for "summed across every money endpoint and every
replica", `readmodel` for the projection freshness gauge.

### Availability

The fraction of money requests that did not fail with a server error.

```promql
1 - (
  sum(rate(http_server_requests_seconds_count{uri=~"/api/(payments|transfers|deposits|lending).*",status=~"5.."}[30d]))
    /
  sum(rate(http_server_requests_seconds_count{uri=~"/api/(payments|transfers|deposits|lending).*"}[30d]))
)
```

Recorded as `money:http_requests_availability:ratio_rate30d`, with a 5-minute
counterpart for dashboards. **Proposed target: 99.9% over a rolling 30 days.**

Both the series and the `uri` matcher are the ones the existing `MoneyEndpointErrorRate`
alert and the Grafana overview dashboard already use, which is deliberate: an SLI that
scopes money differently from the alert that pages on money produces two numbers that
disagree and no way to tell which is right.

The ratio is absent rather than `1` when there is no traffic. That is correct and
occasionally surprising on a dashboard — no requests is no evidence of availability, and
a system that reports perfect health while serving nothing is exactly the failure mode
this repository exists to argue against.

### Latency

The p95 objective for the synchronous customer-facing money paths is:

```promql
histogram_quantile(0.95,
  sum by (le) (rate(http_server_requests_seconds_bucket{uri=~"/api/(payments|transfers).*"}[5m]))
)
```

`application.yml` explicitly enables the percentile histogram for
`http.server.requests` and bounds the expected range from `10ms` to `60s`. That
configuration exists because the latency alert and dashboard both depend on the
`_bucket` series; without histogram publication the PromQL would return no value.

The bounds are deliberate:

- values below `10ms` are not operationally interesting for these money paths;
- requests cannot usefully exceed the platform's `60s` routing deadline;
- bounding the histogram limits the number of buckets and therefore metric cardinality.

The current proposed objective is **p95 under 2 seconds**, matching the
`MoneyEndpointLatency` alert threshold so the SLI and alert cannot drift into two
different definitions of acceptable latency.

This is still a **proposed target, not a measured production SLO**. The histogram
configuration makes the query valid; only runtime traffic can establish whether the
target and bucket bounds are appropriate.

`money:http_request_duration_seconds:mean5m` is also recorded from `_sum` and
`_count`. It is useful for trend context but is not a substitute for p95 because a
mean hides the long tail produced by connection-pool starvation — a failure mode already
measured in [19-runtime-failure-modes.md](19-runtime-failure-modes.md).

### Read-model freshness

How far behind the projection is, from
`corebank_read_model_projection_lag_seconds`.

```promql
sum(count_over_time((corebank_read_model_projection_lag_seconds <= 60)[30d:5m]))
  /
sum(count_over_time((corebank_read_model_projection_lag_seconds < inf)[30d:5m]))
```

Recorded as `readmodel:projection_freshness:ratio_rate30d`. **Proposed target: 99% of
measured scrapes under 60 seconds of lag.**

The denominator counts *valid samples*, not elapsed time, and that choice is the whole
design of this SLI. The reason is the next section.

## The freshness series has genuine gaps, and the SLI has to survive them

`ReadModelMetricsBinder` reports "nothing has been projected yet" as absent rather than
as a number. `ReadModelHealthService` returns `Long.MAX_VALUE` for that state, and the
binder maps it to `Double.NaN`:

```java
private static double lagSeconds(ReadModelHealthService.ReadModelHealthSnapshot snapshot) {
    long lag = snapshot.lagSeconds();
    return lag == Long.MAX_VALUE ? Double.NaN : (double) lag;
}
```

The binder's own comment gives the reasoning, and it is right: exported verbatim the
sentinel becomes `9.2e18`, which flattens every dashboard axis it shares and holds any
"lag above threshold" alert permanently firing. `NaN` is how Prometheus spells "no value
here".

There is a **second** `NaN` path in the same file, and it is easy to miss. The gauge
lambda returns `NaN` when the snapshot reference is still `null`:

```java
Gauge.builder(name, this, binder -> {
            ReadModelHealthService.ReadModelHealthSnapshot current = binder.snapshot.get();
            return current == null ? Double.NaN : reader.applyAsDouble(current);
        })
```

`refresh()` runs with `initialDelay = 5_000L`, so every replica exports `NaN` for its
first few seconds of life. A rollout therefore puts `NaN` into this series routinely,
not only in the empty-projector case.

The consequence is that any statistic over this series must tolerate `NaN`, and which
functions do is not intuitive. It was measured with `promtool test rules` over a window
containing both real and `NaN` samples rather than assumed:

| Expression | Result over a window containing `NaN` |
|---|---|
| `avg_over_time(lag[5m])` | `NaN` — poisoned |
| `max_over_time(lag[5m])` | the largest real value — survives |
| `max(lag)` across replicas | `NaN` when every current sample is `NaN` |
| `max(lag < inf)` | the largest real value, or absent if there are none |
| `count(lag <= 60)` | counts only real samples under the threshold |

The idiom used throughout the recording rules is the `< inf` filter: any comparison
against `NaN` is false, so `NaN` samples drop out of the result instead of poisoning it.

Two consequences worth stating plainly. An unfiltered `avg_over_time` is the trap, and
it is the shape most freshness SLIs are written in. And when *every* sample in the
window is `NaN`, both numerator and denominator are empty and the ratio is absent — the
SLI reports "not measured", which is the honest answer and is different from both "fresh"
and "stale".

## A refusal is not a failure

The availability SLI counts `5xx` only. A `4xx` on a money endpoint is, in this system,
usually the controls working: a duplicate idempotency claim being rejected, a validation
refusing a malformed command, a maker/checker action refused because it is not approved.
Counting those against an error budget would penalise the system for being safe, and
would make the budget burn fastest exactly when a client is retrying correctly — which
is the traffic idempotency exists to absorb.

So `4xx` is recorded separately, as `money:http_requests_refused:rate5m`, and is
deliberately outside the numerator. It is worth watching — a sudden rise in correct
refusals is still a signal that something changed — but it is not unavailability.

There is a **known open issue that lands directly on this line.** A duplicate that
arrives while the original command is still running is answered with `400`, where `409`
is the correct status; this is recorded as a rough edge in
[19-runtime-failure-modes.md](19-runtime-failure-modes.md) and was observed in the
retry-storm scenario, which produced `3 × 400` and later `13 × 400` responses. It does
not corrupt the SLI as written, because both codes are `4xx` and both are excluded. It
does corrupt anything finer: a client cannot distinguish "your request was malformed"
from "your earlier identical request is in flight, poll or wait", and any future SLI
that tries to separate client error from correct refusal will need the codes to be
right first. The fix is not a one-line patch — the error mapping is a substring match on
exception messages across several controllers, so doing it properly means giving the API
typed errors.

## The error budget, worked

At 99.9% over a rolling 30-day window:

```text
window            30 × 24 × 60 × 60   = 2,592,000 s
allowed failure   2,592,000 × 0.001   =     2,592 s
                                      =    43 min 12 s
```

A note on a figure that circulates for this number: **43 min 49 s is not the 30-day
budget.** It is the budget for an average *calendar* month of 30.4375 days
(`2,629,800 s × 0.001 = 2,629.8 s`). The two differ by 38 seconds, which is immaterial
in practice and matters enormously for whether the window is reproducible — a rolling
30-day window is the same length every time it is evaluated, and a calendar month is
not. This document uses 30 days.

The more important correction is that **the SLI above is a request ratio, not a clock**,
so the budget is denominated in requests. The 43 min 12 s figure is the time equivalent
only under the assumption that the system is either fully serving or fully down, which
is the least likely shape of a real incident. Converted honestly:

```text
at 10 money req/min   30 days = 432,000 requests
budget                432,000 × 0.001   = 432 failed requests
```

So at that traffic level the month's entire allowance is 432 server errors. For scale,
the single retry-storm scenario in
[19-runtime-failure-modes.md](19-runtime-failure-modes.md) produced ten `500`s in 32
seconds from fourteen requests. Roughly forty-three such incidents would exhaust a
month. That is the useful output of this arithmetic: it says the budget is not tight
against a well-behaved system and is very tight against one that starves its connection
pool, which is the correct pressure to put on the open refactor.

`money:http_requests_error_budget:ratio_remaining30d` records the remaining fraction:
`1` is untouched, `0` is exhausted, negative means the objective is already missed for
the window.

The chosen traffic figure is illustrative. No traffic rate has been measured, because
there is none.

## Alerts: symptom or cause

`deploy/observability/alerts.yml` holds six rules. The distinction that matters
operationally is whether a rule describes **harm happening now** — page a human — or a
**condition that will cause harm if unattended** — open a ticket. Only the first kind
justifies waking someone.

| Alert | Class | Response | Covered by an SLO? |
|---|---|---|---|
| `MoneyEndpointErrorRate` | Symptom | Page | Yes — availability |
| `MoneyEndpointLatency` | Symptom | Page | Yes — latency, once buckets exist |
| `IdempotencyKeysStranded` | Symptom | Page | No |
| `OutboxDeadLetters` | Symptom | Page | No |
| `OutboxBacklogGrowing` | Cause | Ticket | No |
| `ReconciliationBreaksOpen` | Cause | Ticket, same business day | No |

The classifications that are not obvious:

- **`IdempotencyKeysStranded` is a symptom**, despite watching an internal table and
  despite carrying `severity: warning`. Each stranded claim is one customer operation
  that did not happen and that nobody will retry, because the key is refused on every
  later attempt. The customer is harmed now and silently. The rule's threshold of `0` is
  right for the same reason: there is no healthy non-zero value.
- **`OutboxBacklogGrowing` is a cause.** The alert's own description says the API is
  still returning `200`; what is degrading is downstream consistency, not the customer's
  ability to move money. It becomes a symptom if it does not drain, which is what the
  `for: 10m` encodes.
- **`ReconciliationBreaksOpen` is a cause with a deadline.** The ledger and an external
  statement disagree, and until it is resolved the books cannot be certified. That is
  serious and it is not a 3 a.m. problem; the work of resolving a break is
  business-hours work against external parties.

Two places where the file's `severity:` label and this classification disagree, stated
because both cannot be right and neither has been reconciled:
`IdempotencyKeysStranded` is labelled `warning` but is classified here as page-worthy,
and `ReconciliationBreaksOpen` is labelled `critical` but is classified here as a
ticket. The labels are what a future Alertmanager configuration would route on, so the
disagreement is not cosmetic. It is left open rather than silently resolved in one
direction, because routing severity is a deployment decision and
`deploy/observability/README.md` already records that Alertmanager is deliberately
absent.

Every rule carries a `runbook:` annotation pointing at
`docs/31-operations-runbook.md#<lowercased alertname>`. That file exists, and each of
the six anchors resolves to an `### <AlertName>` heading under its *Alert response*
section, so every annotation lands on a written procedure. The
anchors are load-bearing rather than decorative: the link is the lowercased alert name,
so renaming an alert in `alerts.yml` or renaming a heading in the runbook breaks it
silently. The two have to be changed together.

## What is deliberately not here

- **Alertmanager routing and paging integration.** The rules evaluate; delivering them
  to a person is a deployment concern with no single right answer, and this matches the
  position already taken in `deploy/observability/README.md`.
- **Multi-window multi-burn-rate alerting.** The standard fast-burn/slow-burn pair is
  the right way to alert on an error budget, and it is not defined here because there is
  no measured baseline to tune the windows against. Adding it now would mean inventing
  thresholds and presenting them as engineering.
- **Per-endpoint objectives.** One objective across all money commands, on purpose. Ten
  objectives over routes with no traffic history would be ten guesses.

## Open questions

These are unresolved, not rhetorical.

1. **Are the current histogram bounds and cardinality appropriate under real traffic?**
   Histograms are enabled only for `http.server.requests`, bounded from `10ms` to
   `60s`. Runtime traffic is still needed to validate that choice.
2. **What is the real traffic shape?** Availability as a request ratio behaves very
   differently at 10 req/min and at 1,000 req/min, and the 432-request budget above is
   arithmetic over an assumed number.
3. **Should the availability SLI exclude `GET /api/payments/orders*`?** Doing so needs
   either route enumeration or a `method` filter, and both have maintenance costs.
4. **What is the freshness threshold actually worth?** 60 seconds is a plausible number,
   not a measured one. The right value comes from what a read-model consumer does when
   the data is stale, and that has not been established.
5. **Does event delivery deserve an SLI?** It is the largest uncovered journey. It needs
   a publish timestamp the outbox does not currently record.
