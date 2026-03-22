# 05. Domain Modules

## 1. Identity / IAM
Purpose:
- authenticate operators/system users
- authorize admin/internal actions
- store roles and permissions
- enforce runtime mode/system controls

Typical tables:
- staff_users
- roles
- role_permissions
- system_configs

## 2. Customer
Purpose:
- maintain customer master record
- track KYC/documents
- risk profile
- encrypted sensitive data

Typical tables:
- customers
- customer_documents
- risk_profiles
- encrypted_customer_secrets
- compliance_alerts

## 3. Product
Purpose:
- define banking products
- version configuration over time
- map business events to posting rules

Typical tables:
- bank_products
- bank_product_versions
- posting_rule_sets
- posting_rule_lines

Important rule:
Historical contracts must bind to a **product version**, not a mutable current config.

## 4. Account
Purpose:
- manage customer-facing accounts
- manage internal ledger accounts
- maintain balance abstractions
- support hot account slotting

Typical tables:
- customer_accounts
- ledger_accounts
- account_balance_snapshots
- account_read_models
- ledger_account_balance_slots

## 5. Ledger
Purpose:
- persist journals/postings
- enforce accounting invariants
- support reversal instead of mutation
- support reconciliation

Typical tables:
- ledger_journals
- ledger_postings
- reconciliation_breaks

## 6. Payment
Purpose:
- initiate payment order
- authorize hold
- capture
- void
- refund request/initiation

Typical tables:
- payment_orders
- funds_holds
- hold_events
- payment_events
- refund_requests

## 7. Deposit
Purpose:
- open and manage deposit contracts
- accrue interest
- mature/close contracts

Typical tables:
- term_deposit_contracts
- deposit_accruals
- deposit_events

## 8. Lending
Purpose:
- manage loan applications
- manage active loans
- repayment schedule
- collateral and loan events

Typical tables:
- loan_applications
- loan_contracts
- repayment_schedules
- collaterals
- loan_events

## 9. Limits
Purpose:
- enforce transactional and periodic limits
- detect excess usage
- support risk and compliance rules

Typical tables:
- limit_profiles
- limit_rules
- limit_assignments
- limit_usage_counters

## 10. Operations
Purpose:
- maker-checker
- exception handling
- teller/admin operations
- EOD/BOD batch control

Typical tables:
- approvals
- exception_queue
- teller_sessions
- batch_runs

## 11. Integration
Purpose:
- idempotency
- webhook dedupe
- outbox
- saga/orchestration state

Typical tables:
- idempotency_keys
- webhook_events_processed
- outbox_messages
- saga_instances
- saga_steps

## 12. Audit / Reporting
Purpose:
- immutable action history
- reporting and query projections
- statement/report foundation

Typical tables:
- audit_events
- account_read_models
- account_balance_snapshots
