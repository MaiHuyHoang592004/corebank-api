# CoreBank Documentation

This directory contains the public technical documentation for CoreBank. The index is
curated so readers can start with the guarantees and evidence instead of following the
project's implementation history.

## Start here

1. **[07 — Financial invariants](07-financial-invariants.md)**  
   The rules that must remain true whenever money moves.

2. **[14 — Source-of-truth map](14-source-of-truth-map.md)**  
   What PostgreSQL, Redis, Kafka and read models are allowed to decide.

3. **[19 — Runtime failure modes](19-runtime-failure-modes.md)**  
   Measured failures, incorrect assumptions that were found, and the resulting fixes.

## Architecture

- [04 — System architecture](04-system-architecture.md)
- [05 — Domain modules](05-domain-modules.md)
- [06 — Database context](06-database-context.md)
- [09 — Application architecture](09-application-architecture.md)
- [10 — Integration events and CQRS](10-integration-events-and-cqrs.md)
- [16 — Sequence diagrams](16-sequence-diagrams.md)

## Correctness and verification

- [13 — Implementation checklists](13-implementation-checklists.md)
- [18 — Testing strategy](18-testing-strategy.md)
- [20 — Acceptance criteria](20-acceptance-criteria.md)
- [28 — Demo walkthrough](28-demo-script.md)
- [30 — Verification runner](30-showcase-runner.md)

## Operations and reliability

- [11 — Operations, reliability and security](11-ops-reliability-security.md)
- [27 — Backup, restore and partition archive runbook](27-backup-restore-and-partition-archive-runbook.md)
- [31 — Operations runbook](31-operations-runbook.md)
- [32 — Service levels](32-service-levels.md)
- [33 — Platform deployment and APM guide](33-platform-deployment-and-apm-guide.md)

## Domain context

- [01 — Project overview](01-project-overview.md)
- [02 — Business context](02-business-context.md)
- [03 — Scope and non-goals](03-scope-and-non-goals.md)
- [08 — Core workflows](08-core-workflows.md)

Planning history, local AI-agent instructions, interview notes and personal execution
checklists are intentionally not part of the public documentation surface.
