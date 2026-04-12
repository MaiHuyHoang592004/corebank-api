# 04. System Architecture

## High-level architecture
Recommended deployment style:

```text
[ Client / Admin / Future Channels ]
                |
           Spring Boot API
                |
  +-------------+-------------+
  |             |             |
PostgreSQL     Kafka         Redis
(source of     (async)       (cache/coordination)
 truth)
```

## Responsibility split
### Spring Boot
- application orchestration
- HTTP/API layer
- validation
- authorization
- workflow/state transitions
- calling SQL functions
- writing outbox rows
- consuming Kafka for projections

### PostgreSQL
- financial source of truth
- double-entry ledger
- atomic balance mutation
- hold/capture/void correctness
- product version references
- approvals persistence
- audit persistence
- partitioned historical storage

### Kafka
- asynchronous integration backbone
- outbox publication target
- read model projection pipeline
- notification/event fanout
- saga step signaling

### Redis
- rate limit
- short-lived locks
- OTP/session/challenge
- config cache
- projection/cache support
- short-term dedupe

## Architectural style
### V1
**Modular monolith**
- one Spring Boot deployable
- one primary PostgreSQL database
- optional Kafka/Redis depending on stage

### V2
Same business modules, but async projection and integration become stronger:
- outbox publisher
- Kafka topics
- read model projector
- notification consumer
- reconciliation consumers

### V3
Selective extraction only if justified:
- outbox publisher worker
- read-model projector
- notification service
- risk/limits engine
- external integration gateway

## Why not microservices first?
Because this project is money-heavy and domain-heavy.
Early microservice split would:
- slow development
- increase accidental complexity
- make transactional correctness harder

For portfolio purposes, modular monolith is the best tradeoff.
