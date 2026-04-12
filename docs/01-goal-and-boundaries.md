# Goal and Boundaries

## Main Goal
Build a demo frontend for CoreBank quickly by reusing UI from `banking-main/`.

## Why this exists
The purpose of the frontend is not to become a full internet banking product immediately.
The purpose is to support demoing CoreBank flows clearly and quickly.

## What this frontend is
This frontend is a **demo portal for CoreBank**.
It should make it easy to:
- view account state
- view activity history
- perform internal transfer
- later demo payment hold/capture/void
- later demo deposits and loans

## What this frontend is NOT
This is not yet:
- a full consumer internet banking product
- a full customer onboarding app
- a linked external bank app
- a Plaid/Dwolla/Appwrite system
- a production-grade auth platform

## Phase 1 scope
Only implement these pages first:
1. Dashboard
2. Accounts
3. Account activity
4. Internal transfer

## Phase 2 scope
After phase 1 works end-to-end, add:
5. Payment hold/capture/void demo
6. Demo setup/re-seed flow

## Demo setup behavior
- Use `POST /api/demo/setup` to initialize demo entities.
- Re-running setup re-seeds the demo back to a known baseline for deterministic demos.
- Treat setup as an initializer/re-seed endpoint (not a production data migration pattern).
- Setup requires privileged credentials (OPS/ADMIN), separate from regular demo read/write user credentials.

## Postpone for later
Do not do these yet unless explicitly requested:
- sign up
- Plaid link
- Dwolla transfer
- external bank linking
- full onboarding
- deposits UI
- loans UI
- ops/admin portal
- full role-based production auth

## Hard boundaries
- Backend remains the CoreBank source of truth.
- Frontend must not reuse Appwrite, Plaid, or Dwolla logic.
- Frontend should use new adapters or BFF code.
- If backend data is missing, propose a minimal backend demo API instead of inventing fake frontend data.
