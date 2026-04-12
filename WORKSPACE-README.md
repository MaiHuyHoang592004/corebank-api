# CoreBank Workspace

This workspace contains three distinct parts with different roles.

## Structure

### `backend/`
The real CoreBank backend.

This is the source of truth for:
- transfers
- payments
- deposits
- lending
- ledger
- balances
- idempotency
- outbox
- approvals
- reconciliation
- ops/reporting

All business correctness lives here.

### `banking-main/`
A donor frontend project.

Use it only as a source for:
- layout ideas
- reusable UI shell
- component structure
- styling patterns

Do **not** reuse its business/data logic:
- Appwrite
- Plaid
- Dwolla
- linked-bank flows
- onboarding flows
- fake transaction semantics
- shareableId/encrypt/decrypt helpers

### `frontend/`
The new frontend app being built for CoreBank.

All new frontend code should go here.

This app exists to:
- save time by reusing UI from `banking-main`
- support demos of CoreBank flows
- present backend capabilities through a clean UI

It is **not** a full internet banking rebuild at this stage.

---

## Main Goal

Build a **demo portal for CoreBank** quickly by reusing selected UI from `banking-main`.

The immediate purpose is to support demos of the backend, not to ship a full customer-facing banking product.

---

## Scope for the Current Frontend

### In scope
1. App shell
2. Dashboard
3. Accounts
4. Account activity
5. Internal transfer

### Very likely next
6. Payment hold / capture / void
7. Demo reset / seeded scenario support

### Out of scope for now
- sign-up
- customer onboarding
- Plaid link
- Dwolla transfer
- external bank linking
- full production auth architecture
- full role-aware customer/ops/admin portal
- deposits UI
- loans UI
- ops/admin UI

---

## Source-of-Truth Rules

### Backend truth
`backend/` is authoritative for:
- money state
- ledger correctness
- posted vs available semantics
- idempotency truth
- approval truth
- outbox truth
- operational rules

Never treat frontend state, read-model convenience, or tutorial code as financial truth.

### Frontend truth
`frontend/` is authoritative only for:
- presentation
- page composition
- UI interaction
- demo user flow

### Donor project rule
`banking-main/` is never the source of truth for business behavior.

Use it only to copy and adapt UI.

---

## Documentation Guide

### Read these first for backend/core behavior
- `backend/README.md` or root backend README
- `04-system-architecture.md`
- `07-financial-invariants.md`
- `08-core-workflows.md`
- `14-source-of-truth-map.md`
- `18-testing-strategy.md`
- `19-runtime-failure-modes.md`
- `20-acceptance-criteria.md`
- `AGENTS.md`

### Read these first for frontend-demo migration
- `docs/00-workspace-map.md`
- `docs/01-goal-and-boundaries.md`
- `docs/02-frontend-backend-mapping.md`
- `docs/03-api-contracts.md`
- `docs/04-phase-plan.md`
- `docs/05-cursor-cline-task-rules.md`

---

## Working Rules for AI Agents

When working in this workspace:

1. Treat `backend/` as the real system.
2. Treat `banking-main/` as UI donor only.
3. Put all new frontend code in `frontend/`.
4. Do not reuse Appwrite/Plaid/Dwolla code.
5. If frontend needs data not exposed yet, propose a new backend demo/query facade API.
6. Prefer minimal, reversible changes.
7. Do not rewrite backend core logic unless explicitly requested.
8. Optimize for demo value and clarity first.

---

## Recommended Implementation Order

1. Prepare workspace docs and boundaries
2. Audit `banking-main` and classify reusable files
3. Build `frontend/` skeleton
4. Copy/adapt shell and UI primitives
5. Add backend demo/query facade APIs
6. Bind:
    - dashboard
    - accounts
    - activity
    - internal transfer
7. Add payment hold/capture/void demo panel
8. Add demo reset/setup support

---

## Success Criteria

This workspace setup is successful when:
- frontend clearly demos CoreBank backend flows
- no Appwrite/Plaid/Dwolla logic remains in the new frontend
- backend remains the source of truth
- demo flows are easy to run and explain
- UI improves presentation without distorting domain semantics