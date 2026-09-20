# 28. Demo Walkthrough

This walkthrough demonstrates the project's core guarantees without requiring the
reader to inspect every subsystem.

## Prepare the evidence

Run the fixed verification suite first:

```powershell
.\30-showcase-runner.ps1
```

The generated report is written to
`showcase-output/latest-showcase-report.md`.

## Start the application

```powershell
docker compose up -d postgres redis
./mvnw spring-boot:run
```

Open `http://localhost:9090/dashboard/` and use one of the published demo accounts:

- `demo_user / demo_user`
- `demo_ops / demo_ops`
- `demo_admin / demo_admin`

Initialize the demo data once before running the scenarios.

## Scenario 1 — payment hold, capture and void

Demonstrates that **available** and **posted** balances are different states.

Expected behaviour:

1. authorization reserves spendable funds
2. capture settles the authorized amount
3. void releases the remaining authorization
4. the journal remains balanced throughout the lifecycle

Evidence:

- [07-financial-invariants.md](07-financial-invariants.md)
- [16-sequence-diagrams.md](16-sequence-diagrams.md)
- `PaymentApplicationServiceIntegrationTest`
- `PaymentIdempotencyIntegrationTest`

## Scenario 2 — retry-safe transfer

Create an internal transfer with an idempotency key and replay the same request.

Expected behaviour:

- only one financial journal is posted
- the replay returns the original outcome
- concurrent pressure cannot overspend the source account

Evidence:

- [19-runtime-failure-modes.md](19-runtime-failure-modes.md)
- `TransferServiceIntegrationTest`
- `TransferServiceTransientRetryIntegrationTest`

## Scenario 3 — deposit lifecycle

Open a deposit, run accrual, then execute maturity.

The scenario demonstrates that a contract is bound to versioned product behaviour and
that lifecycle transitions retain the same idempotency, audit and outbox discipline as
the payment paths.

Evidence:

- [05-domain-modules.md](05-domain-modules.md)
- `DepositApplicationServiceIntegrationTest`
- `DepositTransientRetryIntegrationTest`

## Scenario 4 — lending and recovery controls

Run the lending flow through disbursement and repayment, then inspect overdue/default
and outbox recovery behaviour.

The useful property is not feature count: higher-risk workflows still use deterministic
locking, explicit state transitions, audit, outbox and balanced journal posting.

Evidence:

- [16-sequence-diagrams.md](16-sequence-diagrams.md)
- `LoanApplicationServiceIntegrationTest`
- `LoanTransientRetryIntegrationTest`
- `OutboxPatternIntegrationTest`

## Optional operational view

Start the local observability stack:

```bash
docker compose -f deploy/observability/docker-compose.yml up -d
COREBANK_OTLP_ENABLED=true COREBANK_LOG_FORMAT=ecs ./mvnw spring-boot:run
```

Then inspect request latency, JDBC spans, banking-specific metrics, traces and
correlation IDs.

See [deploy/observability/README.md](../deploy/observability/README.md) and
[31-operations-runbook.md](31-operations-runbook.md).
