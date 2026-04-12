# 13. Implementation Checklists

## Checklist for a new money-moving command
- [ ] Has deterministic idempotency key
- [ ] Validates system mode
- [ ] Validates auth/permission
- [ ] Validates limits
- [ ] Validates approval requirement
- [ ] Locks affected financial rows deterministically
- [ ] Posts balanced journal if needed
- [ ] Updates domain state
- [ ] Writes audit row
- [ ] Writes outbox row
- [ ] Has integration test
- [ ] Has retry policy only for safe transient errors

## Checklist for a new product feature
- [ ] New product/version row instead of mutating old rule
- [ ] Posting rules defined
- [ ] Contract binds to version
- [ ] Historical behavior preserved
- [ ] Read model/report impact considered

## Checklist for a new async integration
- [ ] Event schema versioned
- [ ] Outbox-backed
- [ ] Consumer idempotent
- [ ] Retry policy defined
- [ ] Dead-letter strategy defined
- [ ] Observability added

## Checklist for read model/projection
- [ ] Projection is explicitly non-authoritative
- [ ] Backfill/rebuild strategy exists
- [ ] Lag monitoring exists
- [ ] Hot-path queries benefit from it
- [ ] Financial decisions do not rely on it

## Checklist for DB change
- [ ] Migration/backfill plan exists
- [ ] No invariant broken
- [ ] Historical contracts unchanged in meaning
- [ ] Partition/index effect reviewed
- [ ] Rollback path considered
