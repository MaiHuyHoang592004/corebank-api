# 07. Financial Invariants

Đây là phần quan trọng nhất của dự án.

## Core invariants

### Invariant 1 — Double-entry always balances
For every journal:
- total debit = total credit

If this is violated, the transaction must fail.

### Invariant 2 — Ledger history is immutable
Never update/delete posted financial history.
Use reversal journals instead.

### Invariant 3 — Hold does not change posted balance
Authorize hold:
- decreases available balance
- does not change posted balance

### Invariant 4 — Capture realizes the hold into accounting
Capture:
- posts ledger journal
- reduces remaining hold
- may move hold to fully captured

### Invariant 5 — Void/expiry restores available balance
Void or expiry:
- restores remaining held amount to available balance
- does not post a customer-spend journal unless business rule explicitly requires it

### Invariant 6 — Available balance cannot go below allowed threshold
Normally:
- available balance cannot go negative
unless explicitly allowed by product/overdraft rule.

### Invariant 7 — Reversal is compensating, not destructive
If a financial correction is required:
- create reversal journal
- do not mutate original journal/posting rows

### Invariant 8 — Contracts bind to product versions
A deposit/loan contract must reference a versioned product configuration.
Historical contracts must keep historical rules.

### Invariant 9 — Write commands must be idempotent
Any command that changes money/state materially must support replay safely.

### Invariant 10 — Outbox publication must be post-commit
Do not publish messages directly from the middle of a business transaction.
Write outbox row in the same DB transaction.
Publish after commit.

### Invariant 11 — Read models are eventually consistent
Read models can lag.
Financial decisions must use source-of-truth tables/functions.

### Invariant 12 — Locking order must be deterministic
When multiple accounts are affected:
- always lock/update in deterministic order
- e.g. smaller account id first

This reduces deadlock risk.

## Additional practical invariants

### Limit enforcement before posting
Limits should be checked before final journal posting.

### Approval before high-risk execution
For certain actions:
- refund above threshold
- large transfer
- loan approval
execution must require approval first.

### System mode can block writes
During EOD/maintenance:
- non-essential writes may be blocked
- only controlled jobs allowed

### Hot account read semantics differ
Hot accounts may use slotting.
Balance reads must aggregate across slots, not assume single-row balance.

## What an agent must never do
- bypass ledger with direct balance edits
- use read model for money authorization
- mutate historical accounting rows
- reuse idempotency key with changed payload
- ignore product versioning
