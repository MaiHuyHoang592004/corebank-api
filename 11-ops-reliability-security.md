# 11. Operations, Reliability, and Security

## Reliability concerns
### Partitioning
High-volume tables must be partitioned:
- ledger journals
- ledger postings
- audit events

### Partition automation
In real deployment, never rely on manual partition creation only.
Use:
- scheduled DDL creation job
- pg_partman or equivalent automation
- monitoring for future partition existence

### Hot account handling
Some accounts receive many writes.
To reduce lock contention:
- use slotting/sharded counters
- write to one of N slots
- aggregate on read

Important:
all reads must go through abstraction, not direct table assumptions.

### Snapshots and summaries
Do not compute large-balance history by summing millions of postings on hot path.
Use:
- current balances
- daily snapshots
- read models
- materialized reporting support

## System runtime modes
Recommended `system_configs` examples:
- RUNNING
- EOD
- MAINTENANCE
- READ_ONLY

Application code should check mode before allowing sensitive commands.

## Deadlock prevention
When affecting multiple accounts:
- lock in deterministic order
- smaller id first
- keep transaction scope minimal
- retry only safe transient errors

## Security model
### Encrypt at application layer
Sensitive fields should be encrypted before insert:
- national id
- tax id
- secret KYC payload
- sensitive addresses if required

DB should store:
- ciphertext
- key version
- metadata only

### Audit
Every important action should record:
- actor
- action
- resource
- before/after state
- correlation ids
- trace id
- timestamp

### Idempotency TTL
Idempotency records should expire safely, but not too aggressively.
Cleanup must not break replay safety assumptions.

## Recommended ops jobs
- create future partitions
- expire holds
- accrue deposit interest
- mark delinquent loans
- EOD/BOD state transitions
- outbox retry/drain
- snapshot generation
- stale idempotency cleanup
- reconciliation jobs
