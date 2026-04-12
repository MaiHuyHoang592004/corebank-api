# Frontend to Backend Mapping

## Overall mapping rule
Use `banking-main/` as a donor of UI shell and component structure.
Do not copy its data access logic.
Do not preserve its old domain language when it conflicts with CoreBank.

---

## Dashboard
### Frontend target
- `frontend/app/(demo)/dashboard/page.tsx`
- `frontend/components/dashboard/TotalBalanceBox.tsx`
- `frontend/components/activity/RecentActivity.tsx`
- `frontend/components/demo/DemoReseedCard.tsx`

### Donor files from banking-main
- dashboard page shell
- `HeaderBox`
- `TotalBalanceBox`
- `RecentTransactions` (adapt into `RecentActivity`)

### Backend data needed
- `GET /api/demo/accounts`
- `GET /api/demo/accounts/{id}/activity?page=0&size=5` for each demo account (fetched in parallel, aggregated in frontend)

### Notes
- Show total available balance and total posted balance.
- Do not use linked-bank semantics.
- Do not show add-bank or Plaid actions.
- Dashboard should act as a demo launchpad with quick links to Accounts, Transfer, and Payments.
- Dashboard recent feed is multi-account and frontend-aggregated:
  - merge per-account activity responses
  - dedupe by `eventId`
  - sort by `occurredAt desc` (`null` timestamps last)
  - keep top 10 narrative items
- Render `Outcome highlights` from aggregated feed for:
  - `HOLD_AUTHORIZED`
  - `HOLD_CAPTURED`
  - `HOLD_VOIDED`
  - `TRANSFER_COMPLETED`
- `Recent Activity` supports client-side tabs: `All`, `Payments`, `Transfers`.
- `View all` from dashboard activity goes to `/accounts` because feed is cross-account.

---

## Navigation discoverability
### Frontend target
- `frontend/components/Sidebar.tsx`
- `frontend/components/MobileNav.tsx`

### Notes
- Ensure `Payments` is visible in both desktop and mobile navigation.
- Route must point to `frontend/app/(demo)/payments/page.tsx` via `/payments`.

---

## Accounts
### Frontend target
- `frontend/app/(demo)/accounts/page.tsx`
- `frontend/components/accounts/AccountCard.tsx`
- `frontend/components/accounts/AccountSummaryCard.tsx`

### Donor files from banking-main
- `my-banks` page shell
- `BankCard` -> `AccountCard`
- `BankInfo` -> `AccountSummaryCard`

### Backend data needed
- `GET /api/demo/accounts`
- `GET /api/demo/accounts/{id}`

### Notes
- Replace “bank” language with “account”.
- Replace current balance only with available + posted balance.
- Use masked account number, not linked-bank metadata.
- Use shared setup readiness UX (`DemoReseedCard`) instead of legacy backend-not-reachable panel text.
- In `ready` state, show account list plus setup controls for quick re-seed; in non-ready states, show setup-status card only.

---

## Account Activity
### Frontend target
- `frontend/app/(demo)/accounts/[accountId]/page.tsx`
- `frontend/components/activity/ActivityTable.tsx`
- `frontend/components/Pagination.tsx`

### Donor files from banking-main
- `transaction-history` page shell
- `TransactionsTable` -> `ActivityTable`
- `Pagination`

### Backend data needed
- `GET /api/demo/accounts/{id}`
- `GET /api/demo/accounts/{id}/activity?page=&size=`

### Notes
- Do not derive status from date.
- Status must come from backend.
- Amount must be rendered from `minor units + currency`.
- Activity is not the same thing as Plaid transaction history.
- UI pagination is 1-based; backend query pagination is 0-based. Normalize `searchParams.page` before calling backend.
- Payments/Transfers event presentation is frontend-composed for demo clarity:
  - `TRANSFER_COMPLETED` -> `Transfer Out` (debit) / `Transfer In` (credit) by account context
  - `HOLD_AUTHORIZED` -> neutral (available hold hint)
  - `HOLD_CAPTURED` -> debit
  - `HOLD_VOIDED` -> credit
- Event types outside current demo scope keep neutral fallback presentation.

---

## Internal Transfer
### Frontend target
- `frontend/app/(demo)/transfers/new/page.tsx`
- `frontend/components/transfers/InternalTransferForm.tsx`
- `frontend/components/accounts/AccountSelect.tsx`

### Donor files from banking-main
- `payment-transfer` page shell
- `PaymentTransferForm` -> `InternalTransferForm`
- `BankDropdown` -> `AccountSelect`

### Backend data needed
- `GET /api/demo/accounts`
- `GET /api/demo/accounts/lookup?query=`
- `POST /api/demo/transfers/internal`
- `GET /api/demo/accounts/{id}` (refresh source/destination snapshots after transfer)

### Notes
- Remove Appwrite, Plaid, and Dwolla logic.
- Remove shareableId assumptions.
- Use source account + recipient lookup + amount + description.
- Use the same setup readiness card/state model as Dashboard/Payments/Accounts for consistent demo narrative.
- After successful transfer, show an impact summary card composed from backend response + account refresh:
  - journal/status
  - source available/posted before-after + delta
  - destination available/posted before-after + delta
- Provide CTA from transfer outcome to:
  - source account activity
  - destination account activity
  - dashboard outcomes

---

## Payment Hold/Capture/Void (Phase 2)
### Frontend target
- `frontend/app/(demo)/payments/page.tsx`
- `frontend/components/payments/HoldFlowPanel.tsx`
- `frontend/components/payments/HoldCard.tsx`
- `frontend/components/demo/DemoReseedCard.tsx`

### Backend data needed
- `GET /api/demo/accounts/{id}` (refresh payer account snapshot after each action)
- `GET /api/demo/payments/accounts/{id}/holds`
- `POST /api/demo/payments/authorize`
- `POST /api/demo/payments/capture`
- `POST /api/demo/payments/void`

### Notes
- This page exists to demonstrate CoreBank’s strongest balance semantics.
- Show available vs posted changes clearly using backend responses plus account re-fetch.
- Auto-refresh active holds when account changes and after authorize/capture/void.
- After each successful action, show an impact summary card:
  - hold/payment status
  - available balance before/after + delta
  - posted balance before/after + delta
- Provide CTA to `/accounts/{accountId}` to verify event history on account activity.
- Provide a clear demo re-seed entry point using `POST /api/demo/setup` via frontend BFF.

---

## Cross-page setup readiness
### Frontend target
- `frontend/components/demo/DemoReseedCard.tsx`
- `frontend/lib/demo-setup.ts`

### Notes
- Setup readiness state is standardized across Dashboard, Payments, Accounts, and Transfers:
  - `backend_unreachable`
  - `setup_creds_missing`
  - `seed_required`
  - `ready`
- Use one setup-status card path across pages to avoid conflicting guidance.
