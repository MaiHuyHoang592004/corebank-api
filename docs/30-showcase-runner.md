# 30. Verification Runner

The verification runner provides one reproducible command for checking the project's
main financial and reliability claims.

## Command

Run from the repository root:

```powershell
.\30-showcase-runner.ps1
```

Optional output path:

```powershell
.\30-showcase-runner.ps1 -OutputPath "showcase-output/latest-showcase-report.md"
```

## Output

- summary report: `showcase-output/latest-showcase-report.md`
- raw test reports: `target/surefire-reports/`

## Fixed verification suite

- `PaymentApplicationServiceIntegrationTest`
- `PaymentIdempotencyIntegrationTest`
- `TransferServiceIntegrationTest`
- `DepositApplicationServiceIntegrationTest`
- `LoanApplicationServiceIntegrationTest`
- `OutboxPatternIntegrationTest`
- `DepositRateLimitIntegrationTest`
- `IdempotencyRedisReplayCacheIntegrationTest`

## Claim mapping

| Claim | Evidence |
|---|---|
| Payment lifecycle correctness | `PaymentApplicationServiceIntegrationTest`, `PaymentIdempotencyIntegrationTest` |
| Transfer and idempotency safety | `TransferServiceIntegrationTest` |
| Deposit and lending lifecycle behaviour | `DepositApplicationServiceIntegrationTest`, `LoanApplicationServiceIntegrationTest` |
| Transactional outbox persistence | `OutboxPatternIntegrationTest` |
| Redis remains non-authoritative | `DepositRateLimitIntegrationTest`, `IdempotencyRedisReplayCacheIntegrationTest` |

The runner is intentionally narrow. It does not replace `./mvnw verify`; it provides a
stable evidence set for the claims highlighted in the README and demo walkthrough.

See [28-demo-script.md](28-demo-script.md) for the corresponding live scenarios.
