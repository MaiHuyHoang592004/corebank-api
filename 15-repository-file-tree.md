# 15. Recommended Repository File Tree

Tài liệu này mô tả cấu trúc repo khuyến nghị để nhét thẳng vào dự án Spring Boot + PostgreSQL + Kafka + Redis.

Mục tiêu:
- dễ hiểu cho AI agent
- tách domain rõ ràng
- hỗ trợ modular monolith trước
- mở đường tách service sau

---

## 1. Top-level repo structure

```text
corebank/
├── AGENTS.md
├── README.md
├── docs/
│   ├── 01-project-overview.md
│   ├── 02-business-context.md
│   ├── 03-scope-and-non-goals.md
│   ├── 04-system-architecture.md
│   ├── 05-domain-modules.md
│   ├── 06-database-context.md
│   ├── 07-financial-invariants.md
│   ├── 08-core-workflows.md
│   ├── 09-application-architecture.md
│   ├── 10-integration-events-and-cqrs.md
│   ├── 11-ops-reliability-security.md
│   ├── 12-roadmap.md
│   ├── 13-implementation-checklists.md
│   ├── 14-source-of-truth-map.md
│   ├── 15-repository-file-tree.md
│   └── 16-sequence-diagrams.md
├── db/
│   ├── migration/
│   │   ├── V001__base_identity.sql
│   │   ├── V002__customer_and_kyc.sql
│   │   ├── V003__products_and_posting_rules.sql
│   │   ├── V004__ledger_and_accounts.sql
│   │   ├── V005__payments_and_holds.sql
│   │   ├── V006__deposits.sql
│   │   ├── V007__lending.sql
│   │   ├── V008__ops_limits_approvals.sql
│   │   ├── V009__integration_outbox_audit.sql
│   │   └── V010__read_models_partitioning.sql
│   ├── seed/
│   │   ├── R__reference_data.sql
│   │   └── R__demo_data.sql
│   └── patches/
│       └── production-hardening/
├── build.gradle / pom.xml
├── gradle/ or mvnw/
├── src/
│   ├── main/
│   │   ├── java/com/example/corebank/
│   │   └── resources/
│   └── test/
├── scripts/
│   ├── local/
│   ├── test/
│   └── ops/
├── docker/
│   ├── docker-compose.local.yml
│   └── observability/
├── deploy/
│   ├── k8s/
│   └── helm/
└── tools/
    └── loadtest/
```

---

## 2. Java package structure

```text
src/main/java/com/example/corebank/
├── CoreBankApplication.java
├── common/
│   ├── config/
│   ├── exception/
│   ├── ids/
│   ├── money/
│   ├── time/
│   ├── transaction/
│   └── web/
├── platform/
│   ├── context/
│   ├── locking/
│   ├── idempotency/
│   ├── outbox/
│   ├── audit/
│   └── scheduling/
├── iam/
│   ├── api/
│   ├── application/
│   ├── domain/
│   └── infrastructure/
├── customer/
│   ├── api/
│   ├── application/
│   ├── domain/
│   └── infrastructure/
├── product/
│   ├── api/
│   ├── application/
│   ├── domain/
│   └── infrastructure/
├── account/
│   ├── api/
│   ├── application/
│   ├── domain/
│   └── infrastructure/
├── ledger/
│   ├── api/
│   ├── application/
│   ├── domain/
│   └── infrastructure/
├── payment/
│   ├── api/
│   ├── application/
│   ├── domain/
│   └── infrastructure/
├── deposit/
│   ├── api/
│   ├── application/
│   ├── domain/
│   └── infrastructure/
├── lending/
│   ├── api/
│   ├── application/
│   ├── domain/
│   └── infrastructure/
├── limits/
│   ├── api/
│   ├── application/
│   ├── domain/
│   └── infrastructure/
├── ops/
│   ├── api/
│   ├── application/
│   ├── domain/
│   └── infrastructure/
├── integration/
│   ├── api/
│   ├── application/
│   ├── domain/
│   ├── kafka/
│   ├── redis/
│   └── infrastructure/
└── reporting/
    ├── api/
    ├── application/
    ├── projector/
    └── infrastructure/
```

---

## 3. Package responsibilities

## `common`
Chứa các primitive dùng toàn hệ thống:
- Money
- Currency
- domain errors
- request context
- utility transaction helpers

Không để business logic domain-specific ở đây.

## `platform`
Chứa cross-cutting concerns:
- idempotency service
- outbox writer/publisher
- audit service
- distributed lock helpers
- schedulers

## `iam`
- staff users
- roles/permissions
- system config runtime mode

## `customer`
- customer profile
- kyc metadata
- documents
- encrypted secrets references
- risk profile

## `product`
- products
- product versions
- posting rules
- effective date handling

## `account`
- customer accounts
- account status
- balance query abstraction
- snapshots / hot account read logic

## `ledger`
- journal posting
- reversal
- reconciliation entry generation
- ledger invariant enforcement

## `payment`
- payment orders
- holds
- capture/void/refund
- payment lifecycle

## `deposit`
- savings / term deposit contracts
- accruals
- maturity/closure

## `lending`
- applications
- contracts
- schedules
- repayments
- collateral

## `limits`
- limit policy lookup
- usage counters
- daily/monthly velocity checks

## `ops`
- approvals
- exception queue
- teller flows
- batch runs / EOD jobs

## `integration`
- kafka producers/consumers
- webhook ingest
- redis coordination
- saga orchestration support

## `reporting`
- read models
- query projections
- dashboards
- statement generation

---

## 4. File examples inside a module

Ví dụ module `payment`:

```text
payment/
├── api/
│   ├── PaymentController.java
│   ├── HoldController.java
│   └── dto/
├── application/
│   ├── PaymentApplicationService.java
│   ├── HoldApplicationService.java
│   ├── RefundApplicationService.java
│   ├── command/
│   └── query/
├── domain/
│   ├── PaymentOrder.java
│   ├── FundsHold.java
│   ├── PaymentStatus.java
│   ├── HoldStatus.java
│   └── event/
└── infrastructure/
    ├── PaymentRepository.java
    ├── HoldRepository.java
    ├── PaymentSqlRepository.java
    └── mapper/
```

---

## 5. Where SQL should live

Money-critical SQL không nên rải trong controller/service.

Khuyến nghị:

```text
db/sql/
├── ledger/
│   ├── post_journal.sql
│   ├── reverse_journal.sql
│   └── reconcile_account.sql
├── payment/
│   ├── authorize_hold.sql
│   ├── capture_hold.sql
│   └── void_hold.sql
├── deposit/
├── lending/
└── reporting/
```

Hoặc nhúng trong repository nếu dùng jOOQ/JdbcTemplate, nhưng vẫn cần giữ ranh giới rõ ràng.

---

## 6. Test tree

```text
src/test/java/com/example/corebank/
├── architecture/
├── contract/
├── integration/
│   ├── ledger/
│   ├── payment/
│   ├── deposit/
│   ├── lending/
│   └── ops/
├── e2e/
└── unit/
```

### Ưu tiên test
1. ledger invariant tests
2. idempotency tests
3. hold/capture/void tests
4. limit enforcement tests
5. approval workflow tests
6. deposit/loan accrual tests

---

## 7. Recommended naming conventions

- `ApplicationService` cho orchestration
- `CommandHandler` cho explicit command flows nếu cần
- `Repository` cho persistence abstraction
- `SqlRepository` hoặc `JdbcRepository` cho SQL-first implementation
- `Projector` cho read-model updaters
- `Publisher` cho outbox/kafka publishing
- `Scheduler` cho jobs định kỳ

---

## 8. Anti-patterns in repo structure

- đặt controller gọi SQL trực tiếp
- business logic nằm trong entity JPA dày đặc khó kiểm soát
- package by technical layer toàn hệ thống (`controller/service/repository`) mà không theo domain
- nhét cả ledger/payment/account vào một package mơ hồ
- để read model code lẫn với financial write model

---

## 9. Short recommendation

Repo nên theo kiểu:

> **domain-first modular monolith**

Không phải:
- CRUD-first
- framework-first
- microservice-first

---

## 10. What agents should assume

AI agent nên assume:
- package name = business boundary
- money-critical path ở `ledger` và `payment`
- read-only/dashboard path ở `reporting`
- cross-cutting ở `platform`
- external async ở `integration`
