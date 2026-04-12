# 16. Sequence Diagrams

Tài liệu này dùng **Mermaid** để mô tả các flow chính.
Nếu viewer không render Mermaid, hãy đọc như pseudo-sequence.

---

## 1. Internal transfer

```mermaid
sequenceDiagram
    participant C as Client
    participant API as Spring API
    participant IDS as IdempotencyService
    participant LIM as LimitCheckService
    participant ACC as AccountService
    participant LED as LedgerCommandService
    participant DB as PostgreSQL
    participant OUT as OutboxWriter

    C->>API: POST /transfers
    API->>IDS: check(idempotencyKey, requestHash)
    IDS->>DB: insert/select integration.idempotency_keys
    DB-->>IDS: SUCCESS or ALREADY_COMPLETED
    IDS-->>API: ok

    API->>LIM: check limits
    LIM->>DB: read limit configs + usage counters
    DB-->>LIM: ok
    LIM-->>API: allowed

    API->>ACC: validate source/destination account
    ACC->>DB: read account status + balances
    DB-->>ACC: valid

    API->>LED: post transfer journal
    LED->>DB: BEGIN
    LED->>DB: lock accounts in deterministic order
    LED->>DB: insert ledger_journals
    LED->>DB: insert ledger_postings
    LED->>DB: update account.customer_accounts (current balance source)
    LED->>OUT: stage domain event
    OUT->>DB: insert integration.outbox_messages
    LED->>DB: COMMIT

    API-->>C: 201 Created
```

---

## 2. Merchant payment authorize hold

```mermaid
sequenceDiagram
    participant Merchant as Merchant Client
    participant API as Payment API
    participant IDS as IdempotencyService
    participant LIM as LimitCheckService
    participant PAY as PaymentApplicationService
    participant DB as PostgreSQL

    Merchant->>API: POST /payments/{id}/authorize
    API->>IDS: check idempotency
    IDS->>DB: integration.idempotency_keys
    DB-->>IDS: SUCCESS

    API->>LIM: check customer/payment limits
    LIM->>DB: read limits + usage
    DB-->>LIM: allowed

    API->>PAY: authorize hold
    PAY->>DB: BEGIN
    PAY->>DB: validate payment + account
    PAY->>DB: subtract available balance only
    PAY->>DB: insert funds_holds
    PAY->>DB: insert hold_events(AUTHORIZED)
    PAY->>DB: insert payment_events(AUTHORIZED)
    PAY->>DB: insert outbox event
    PAY->>DB: COMMIT

    API-->>Merchant: 200 Authorized
```

---

## 3. Capture held funds

```mermaid
sequenceDiagram
    participant Merchant as Merchant Client
    participant API as Hold API
    participant IDS as IdempotencyService
    participant PAY as PaymentApplicationService
    participant LED as LedgerCommandService
    participant DB as PostgreSQL

    Merchant->>API: POST /holds/{holdId}/capture
    API->>IDS: check idempotency
    IDS->>DB: integration.idempotency_keys
    DB-->>IDS: SUCCESS

    API->>PAY: capture hold(amount)
    PAY->>DB: BEGIN
    PAY->>DB: lock hold row
    PAY->>DB: validate remaining amount
    PAY->>LED: post journal for capture
    LED->>DB: insert journal + postings
    LED->>DB: update posted balance
    PAY->>DB: reduce remaining hold amount
    PAY->>DB: if remaining = 0 -> status CAPTURED
    PAY->>DB: insert hold_events(CAPTURED)
    PAY->>DB: insert payment_events(CAPTURED)
    PAY->>DB: insert outbox event
    PAY->>DB: COMMIT

    API-->>Merchant: 200 Captured
```

---

## 4. Void hold

```mermaid
sequenceDiagram
    participant Merchant as Merchant Client
    participant API as Hold API
    participant PAY as PaymentApplicationService
    participant DB as PostgreSQL

    Merchant->>API: POST /holds/{holdId}/void
    API->>PAY: void remaining hold
    PAY->>DB: BEGIN
    PAY->>DB: lock hold row
    PAY->>DB: restore available balance from remaining amount
    PAY->>DB: mark hold VOIDED
    PAY->>DB: insert hold_events(VOIDED)
    PAY->>DB: insert payment_events(VOIDED)
    PAY->>DB: insert outbox event
    PAY->>DB: COMMIT
    API-->>Merchant: 200 Voided
```

---

## 5. Open term deposit

```mermaid
sequenceDiagram
    participant User as Customer
    participant API as Deposit API
    participant IDS as IdempotencyService
    participant DEP as DepositApplicationService
    participant LED as LedgerCommandService
    participant DB as PostgreSQL

    User->>API: POST /deposits/term
    API->>IDS: idempotency check
    IDS->>DB: integration.idempotency_keys
    DB-->>IDS: SUCCESS

    API->>DEP: create term deposit contract
    DEP->>DB: BEGIN
    DEP->>DB: create deposit contract bound to product version
    DEP->>LED: post funding journal
    LED->>DB: debit customer cash / credit deposit liability
    DEP->>DB: insert deposit_events(CONTRACT_OPENED)
    DEP->>DB: insert outbox event
    DEP->>DB: COMMIT

    API-->>User: 201 Deposit opened
```

---

## 6. Daily deposit interest accrual batch

```mermaid
sequenceDiagram
    participant SCH as Scheduler
    participant JOB as DepositAccrualJob
    participant DEP as DepositApplicationService
    participant LED as LedgerCommandService
    participant DB as PostgreSQL

    SCH->>JOB: run daily accrual
    JOB->>DB: fetch active deposits due for accrual
    DB-->>JOB: deposits list

    loop each deposit
        JOB->>DEP: accrue interest(depositId)
        DEP->>DB: BEGIN
        DEP->>LED: post accrual journal
        LED->>DB: debit interest expense / credit deposit liability
        DEP->>DB: insert deposit_accruals
        DEP->>DB: insert deposit_events(INTEREST_ACCRUED)
        DEP->>DB: COMMIT
    end
```

---

## 7. Loan disbursement

```mermaid
sequenceDiagram
    participant Ops as Credit Officer
    participant API as Loan API
    participant APP as ApprovalService
    participant LOAN as LendingApplicationService
    participant LED as LedgerCommandService
    participant DB as PostgreSQL

    Ops->>API: Approve + disburse loan
    API->>APP: confirm maker-checker approval
    APP->>DB: read ops.approvals
    DB-->>APP: approved

    API->>LOAN: disburse loan contract
    LOAN->>DB: BEGIN
    LOAN->>DB: create/activate loan contract
    LOAN->>DB: generate repayment schedule
    LOAN->>LED: post disbursement journal
    LED->>DB: debit loan receivable / credit customer account
    LOAN->>DB: insert loan_events(DISBURSED)
    LOAN->>DB: insert outbox event
    LOAN->>DB: COMMIT

    API-->>Ops: 200 Disbursed
```

---

## 8. Loan repayment

```mermaid
sequenceDiagram
    participant User as Customer
    participant API as Loan API
    participant LIM as LimitCheckService
    participant LOAN as LendingApplicationService
    participant LED as LedgerCommandService
    participant DB as PostgreSQL

    User->>API: POST /loans/{loanId}/repay
    API->>LIM: check repayment limits/rules
    LIM->>DB: read limits
    DB-->>LIM: ok

    API->>LOAN: repay loan
    LOAN->>DB: BEGIN
    LOAN->>DB: select due installment(s)
    LOAN->>LED: post repayment journal
    LED->>DB: debit customer cash / credit loan receivable + interest income
    LOAN->>DB: update installment paid amounts
    LOAN->>DB: insert loan_events(REPAYMENT)
    LOAN->>DB: insert outbox event
    LOAN->>DB: COMMIT

    API-->>User: 200 Repayment posted
```

---

## 9. Outbox to Kafka projector

```mermaid
sequenceDiagram
    participant APP as CoreBank App
    participant DB as PostgreSQL
    participant PUB as OutboxPublisher
    participant K as Kafka
    participant PROJ as ReadModelProjector
    participant RM as account_read_models

    APP->>DB: commit business tx + outbox row
    PUB->>DB: claim pending outbox batch
    DB-->>PUB: rows
    PUB->>K: publish event(s)
    PUB->>DB: mark outbox published

    K->>PROJ: consume ledger/account/payment events
    PROJ->>RM: upsert query projection
```

---

## 10. Saga for cross-service orchestration

```mermaid
sequenceDiagram
    participant API as CoreBank API
    participant SAGA as SagaCoordinator
    participant DB as PostgreSQL
    participant EXT as External Service

    API->>SAGA: start orchestration
    SAGA->>DB: insert saga_instances + first step
    DB-->>SAGA: created

    SAGA->>EXT: call external operation
    EXT-->>SAGA: timeout/fail/success

    alt success
        SAGA->>DB: mark step completed
        SAGA->>DB: advance next step or complete saga
    else failure
        SAGA->>DB: mark failed step
        SAGA->>DB: schedule compensating action
    end
```

---

## 11. Runtime mode check before write command

```mermaid
sequenceDiagram
    participant C as Client
    participant API as API Layer
    participant MODE as SystemModeGuard
    participant DB as PostgreSQL
    participant APP as ApplicationService

    C->>API: money-moving request
    API->>MODE: assert system writable
    MODE->>DB: read system_configs
    DB-->>MODE: RUNNING or EOD_LOCK

    alt RUNNING
        MODE-->>API: allowed
        API->>APP: continue command
    else EOD_LOCK / MAINTENANCE
        MODE-->>API: reject
        API-->>C: 503 / business error
    end
```

---

## 12. Notes for agents

- Các diagram này mô tả **logical flow**, không bắt buộc 1:1 với class names cụ thể
- Khi code thật, phải luôn giữ nguyên tinh thần:
  - idempotency trước write
  - limits trước money mutation
  - ledger là truth
  - outbox trong cùng DB transaction
  - read model chỉ update sau commit
