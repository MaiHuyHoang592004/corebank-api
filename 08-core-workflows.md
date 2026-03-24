# 08. Core Workflows

## 1. Internal transfer workflow
1. Receive transfer command
2. Check auth + request metadata
3. Check idempotency
4. Check system mode
5. Check limits/risk
6. Validate source/destination accounts
7. Build postings
8. Post balanced journal
9. Persist transfer/payment state
10. Write audit event
11. Write outbox message
12. Commit
13. Async publish/project after commit

## 2. Payment authorize-hold workflow
1. Create/init payment order
2. Check idempotency
3. Validate payer account
4. Check available balance
5. Create funds hold
6. Decrease available balance
7. Write hold event = AUTHORIZED
8. Write audit + outbox
9. Commit

Result:
- money is reserved
- posted balance unchanged

## 3. Payment capture workflow
1. Receive capture command
2. Check idempotency
3. Load hold with lock
4. Validate status and remaining amount
5. Resolve postings
6. Post journal
7. Decrease remaining hold
8. If remaining = 0 -> mark fully captured
9. Write hold/payment events
10. Write audit + outbox
11. Commit

## 4. Payment void workflow
1. Receive void command
2. Check idempotency
3. Load hold with lock
4. Validate status
5. Restore remaining amount to available balance
6. Mark hold voided/expired
7. Write hold event
8. Write audit + outbox
9. Commit

## 5. Deposit open workflow
1. Select product version
2. Validate customer/account
3. Create contract
4. Move principal according to posting rule
5. Write deposit event
6. Write audit + outbox
7. Commit

## 6. Deposit accrual workflow
Usually batch-driven:
1. Identify active contracts due for accrual
2. Compute interest by product version
3. Persist accrual row
4. Optionally post accrual journal depending on accounting policy
5. Write events/audit

## 7. Loan disbursement workflow
1. Loan application reviewed/approved
2. Create loan contract from approved version
3. Generate repayment schedule
4. Post disbursement journal
5. Write loan event
6. Write audit + outbox
7. Commit

## 8. Loan repayment workflow
1. Receive repayment command
2. Check idempotency
3. Load schedule/installment state
4. Split repayment into principal/interest/fees
5. Post journal
6. Update repayment schedule
7. Write loan event
8. Write audit + outbox
9. Commit

## 9. Approval workflow
1. Maker creates action request
2. Approval row created with status pending
3. Checker approves or rejects
4. Only approved command may execute final business action
5. Final action writes audit trail with maker/checker linkage

## 10. EOD/BOD workflow
EOD goals:
- freeze or restrict new writes if needed
- finalize daily batches
- accruals
- snapshots
- reconciliation
- reporting cut
- roll operational state

BOD goals:
- reopen system mode
- initialize daily counters
- readiness checks
