# 30. Showcase Runner

This runner turns the repo into a reproducible interview demo.

## What It Proves
- the repo can be validated through one fixed showcase command
- the strongest money-flow and safety claims are backed by executable tests
- the Redis story is real but conservative: helpful for rate limiting and replay acceleration, never authoritative for money truth

## Command
Run from the repo root:

```powershell
.\30-showcase-runner.ps1
```

Optional custom output path:

```powershell
.\30-showcase-runner.ps1 -OutputPath "showcase-output/latest-showcase-report.md"
```

## Expected Runtime
- usually 5 to 10 minutes on a machine that can run the existing integration suite
- depends on Docker/Testcontainers startup time and local Maven cache state

## Output Location
- default report path: `showcase-output/latest-showcase-report.md`
- detailed raw test evidence remains in `target/surefire-reports/`

## Fixed Showcase Suite
- `PaymentApplicationServiceIntegrationTest`
- `PaymentIdempotencyIntegrationTest`
- `TransferServiceIntegrationTest`
- `DepositApplicationServiceIntegrationTest`
- `LoanApplicationServiceIntegrationTest`
- `OutboxPatternIntegrationTest`
- `DepositRateLimitIntegrationTest`
- `IdempotencyRedisReplayCacheIntegrationTest`

## Claim Mapping
- Payment semantics:
  - `PaymentApplicationServiceIntegrationTest`
  - `PaymentIdempotencyIntegrationTest`
- Transfer and idempotency safety:
  - `TransferServiceIntegrationTest`
- Deposit and lending lifecycle depth:
  - `DepositApplicationServiceIntegrationTest`
  - `LoanApplicationServiceIntegrationTest`
- Redis acceleration without truth ownership:
  - `DepositRateLimitIntegrationTest`
  - `IdempotencyRedisReplayCacheIntegrationTest`

## How To Use In An Interview
1. Open [README.md](README.md) for the high-level framing.
2. Open [28-demo-script.md](28-demo-script.md) for the spoken walkthrough.
3. Run `./30-showcase-runner.ps1` to generate current evidence.
4. Open `showcase-output/latest-showcase-report.md` as proof that the claims map to executable tests.
5. Use [29-interview-prep.md](29-interview-prep.md) for concise answers and tradeoff framing.
