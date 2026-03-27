# 28. Demo Script

This is the fixed interview demo walkthrough for this repo.

The goal is not to show every subsystem. The goal is to show the strongest proof points quickly and coherently.

## Demo Rules
- Keep the walkthrough to 8-10 minutes.
- Show only four scenarios.
- Anchor every claim to one invariant, one proof point in code/tests/docs, and one clear business reason.
- Do not drift into low-ROI infrastructure polish unless asked.

## Run The Evidence First
Before the walkthrough, regenerate the current evidence pack:

```powershell
.\30-showcase-runner.ps1
```

Then keep these three files open during the demo:
- [README.md](README.md)
- [30-showcase-runner.md](30-showcase-runner.md)
- `showcase-output/latest-showcase-report.md`

## Run Live Dashboard
Start the app and open the browser dashboard:

```powershell
docker compose up -d postgres redis
mvn spring-boot:run
```

Open `http://localhost:9090/dashboard/` and use demo credentials:
- `demo_user / demo_user`
- `demo_ops / demo_ops`
- `demo_admin / demo_admin`

Recommended flow in UI:
1. Click `Initialize Demo Data`.
2. Run one action in each tab: Payment, Transfer, Deposit, Lending.
3. Use evidence links in the side panel when interviewer asks for deeper proof.

## Minute-By-Minute Flow
1. Minute 0-1: frame the project
2. Minute 1-3: payment hold/capture/void
3. Minute 3-5: transfer idempotency and concurrency safety
4. Minute 5-7: deposit lifecycle
5. Minute 7-9: lending plus overdue/default and outbox reliability
6. Minute 9-10: Redis story and why the repo stops here

## Opening Frame
Say:

"This is a production-like fintech backend portfolio project. I focused on making money flows correct first, then layering in the controls you expect in real systems: idempotency, approvals, outbox, audit, reconciliation, runtime mode, and selective Redis/Kafka usage without letting them become the source of truth."

Proof points:
- [README.md](README.md)
- [14-source-of-truth-map.md](14-source-of-truth-map.md)
- [07-financial-invariants.md](07-financial-invariants.md)

Avoid unless asked:
- full roadmap history
- every phase name in order
- every ops endpoint added over time

## Scenario 1: Payment hold/capture/void
What to say:
- "Payment authorization reduces available balance but not posted balance. Capture posts money. Void restores availability from the remaining hold."

Invariant proven:
- posted and available semantics are not blurred
- hold, capture, and void are modeled explicitly instead of being one generic payment status flip

Proof in repo:
- [16-sequence-diagrams.md](16-sequence-diagrams.md)
- `src/test/java/com/corebank/corebank_api/payment/PaymentApplicationServiceIntegrationTest.java`
- `src/test/java/com/corebank/corebank_api/payment/PaymentIdempotencyIntegrationTest.java`

What to avoid unless asked:
- every payment table name
- detailed DTO fields
- Kafka/read-model details during this section

## Scenario 2: Transfer with idempotency and concurrency safety
What to say:
- "Transfers are idempotent, retry-safe, and hardened for transient lock/deadlock failures. The point is not just moving money once, but moving it correctly under retries and concurrent pressure."

Invariant proven:
- duplicate or concurrent requests do not double-post
- transient database contention is retried in a bounded way
- failed attempts do not leak partial money state

Proof in repo:
- [16-sequence-diagrams.md](16-sequence-diagrams.md)
- `src/test/java/com/corebank/corebank_api/transfer/TransferServiceIntegrationTest.java`
- `src/test/java/com/corebank/corebank_api/transfer/TransferServiceTransientRetryIntegrationTest.java`

What to avoid unless asked:
- deep JDBC/SQL details
- every retry classifier branch
- partition or hot-account topics here

## Scenario 3: Deposit lifecycle
What to say:
- "The deposit flow is more than just creating a row. It opens a contract bound to a product version, accrues interest, matures correctly, and carries the same idempotency/outbox/audit discipline as the payment and transfer paths."

Invariant proven:
- contracts bind to versioned product configuration
- lifecycle events are explicit
- transient retry hardening was applied without changing truth ownership

Proof in repo:
- [05-domain-modules.md](05-domain-modules.md)
- `src/test/java/com/corebank/corebank_api/deposit/DepositApplicationServiceIntegrationTest.java`
- `src/test/java/com/corebank/corebank_api/deposit/DepositTransientRetryIntegrationTest.java`

What to avoid unless asked:
- every schema column
- all deposit ops/history entries in `PROGRESS.log`

## Scenario 4: Lending plus overdue/default and outbox recovery narrative
What to say:
- "Lending shows that the repo goes beyond simple payments. It covers contract lifecycle, repayment behavior, overdue/default transitions, deterministic lock ordering, and async reliability through outbox and dead-letter handling."

Invariant proven:
- higher-risk domain flows still preserve transactional discipline
- contention-heavy paths were hardened intentionally
- async integration is delayed safely, not treated as financial truth

Proof in repo:
- [16-sequence-diagrams.md](16-sequence-diagrams.md)
- `src/test/java/com/corebank/corebank_api/lending/LoanApplicationServiceIntegrationTest.java`
- `src/test/java/com/corebank/corebank_api/lending/LoanTransientRetryIntegrationTest.java`
- `src/test/java/com/corebank/corebank_api/reporting/OutboxReportingControllerIntegrationTest.java`
- `src/test/java/com/corebank/corebank_api/integration/OutboxPatternIntegrationTest.java`

What to avoid unless asked:
- every ops hardening slice added after Phase 4
- all dead-letter UX details
- all scheduler/maintenance stories

## If Asked: Hot-Account Strategy Semantics
Say:

"The profile keeps the configured strategy, but runtime currently applies native HASH only. If profile is configured as ROUND_ROBIN or RANDOM, the runtime explicitly reports HASH_FALLBACK so ops can see there is no fake native support."

Proof in repo:
- [19-runtime-failure-modes.md](19-runtime-failure-modes.md)
- `src/main/java/com/corebank/corebank_api/ops/hotaccount/HotAccountOpsService.java`
- `src/main/java/com/corebank/corebank_api/ledger/HotAccountSlotRuntimeService.java`

## Redis Talking Point
Say this exactly or very close to it:

"Redis is intentionally helpful but non-authoritative here. I use it for rate limiting and conservative idempotent success replay caching to reduce load, but PostgreSQL still decides money truth and idempotency truth. That is the important design choice."

Proof in repo:
- [14-source-of-truth-map.md](14-source-of-truth-map.md)
- [19-runtime-failure-modes.md](19-runtime-failure-modes.md)
- `src/test/java/com/corebank/corebank_api/deposit/DepositRateLimitIntegrationTest.java`
- `src/test/java/com/corebank/corebank_api/integration/IdempotencyRedisReplayCacheIntegrationTest.java`

## Closing Line
Say:

"I stopped here on purpose. At this point the project already looks like a real fintech backend. More infra polish would add less interview value than a clean, explainable architecture and a strong correctness story."
