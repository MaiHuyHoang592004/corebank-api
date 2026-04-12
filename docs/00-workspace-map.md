# Workspace Map

## backend/
This is the real CoreBank backend.
It contains the authoritative banking logic and source of truth for:
- transfers
- payments
- deposits
- lending
- ledger
- idempotency
- outbox
- approvals
- ops and reporting

Treat `backend/` as the system of record.
Do not redesign business logic here unless a task explicitly requires it.

## banking-main/
This is a donor frontend project.
Use it only as a source for:
- layout
- visual components
- page composition ideas
- styling patterns
- responsive shell

Do not reuse from `banking-main/`:
- Appwrite logic
- Plaid logic
- Dwolla logic
- old transaction semantics
- signup/onboarding flow
- linked-bank assumptions
- fake status logic

## frontend/
This is the new frontend app for CoreBank.
All new frontend code must go here.
It should reuse UI ideas from `banking-main/`, but must use CoreBank APIs and CoreBank data semantics.

## docs/
This folder defines project intent and migration rules.
Before changing code, always read the docs in this folder first.

## Global rule
- `backend/` is the source of truth.
- `banking-main/` is UI donor only.
- `frontend/` is the real new app.
