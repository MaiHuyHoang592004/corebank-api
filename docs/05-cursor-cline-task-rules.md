# Cursor and Cline Task Rules

## Read order before changing code
Always read these files first:
1. `docs/00-workspace-map.md`
2. `docs/01-goal-and-boundaries.md`
3. `docs/02-frontend-backend-mapping.md`
4. `docs/03-api-contracts.md`
5. `docs/04-phase-plan.md`

## Rules
- Treat `backend/` as the source of truth.
- Treat `banking-main/` as donor UI only.
- Put all new frontend code in `frontend/`.
- Do not reuse Appwrite, Plaid, or Dwolla logic.
- Do not invent fake frontend data if a backend API is missing.
- If a backend API is missing, propose the smallest new demo facade API.
- Prefer minimal and reversible changes.
- Keep scope focused on demoing CoreBank flows.

## Task execution order
When implementing a feature:
1. identify frontend target files in `frontend/`
2. identify donor files in `banking-main/`
3. identify backend API or missing backend API
4. map required view model
5. implement or propose change
6. list verification steps

## Do not do these automatically
- do not redesign backend core services
- do not build full auth architecture
- do not rebuild onboarding
- do not port linked-bank semantics into CoreBank
- do not expand scope to deposits, loans, or ops pages unless explicitly requested
