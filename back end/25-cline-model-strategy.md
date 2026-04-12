# 25. Cline Model Strategy

## Default recommendation for this repo
### Solo development
- Plan model: fast/cheap model
- Act model: strong coding model (Codex-first if available)
- Auto-approve: read-only + safe test/lint only
- Manual approval: write + shell side effects

## Why split Plan and Act
Plan mode is cheaper and safer for repository understanding.
Act mode should be reserved for code generation and controlled execution.
This reduces cost and lowers the chance of large wrong edits.

## Suggested model roles
### Plan
Use one of:
- cheaper OpenAI-compatible small model
- Gemini Flash-class model
- another fast reasoning model

### Act
Use one of:
- GPT-5.2-Codex / GPT-5.1-Codex
- strong Claude coding model
- equivalent strong coding model behind OpenAI-compatible gateway

## Fallback strategy
If using a gateway, route:
1. primary strong coding model
2. cheaper fallback for retries/timeouts
3. optional local/private model for sensitive review tasks

## Privacy mode
If the repository becomes sensitive:
- use local model for broad repo reading or documentation work
- use cloud coding model only on narrow scoped files
- tighten `.clineignore`

## Cost-control rules
- do not let Cline scan build artifacts or logs
- prefer narrow file lists in Plan mode
- use prompt caching where supported
- use headless automation only for repetitive constrained tasks
