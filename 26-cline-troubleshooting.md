# 26. Cline Troubleshooting

## Symptom: Cline scans too much and wastes tokens
Likely causes:
- missing `.clineignore`
- prompt too broad
- file scope not constrained in Plan mode

Fix:
- tighten `.clineignore`
- tell Cline to list files before reading
- run smaller scoped tasks

## Symptom: Cline makes risky shell suggestions
Likely causes:
- weak command permissions
- Auto-Approve too broad

Fix:
- narrow `CLINE_COMMAND_PERMISSIONS`
- disable auto-approve for shell side effects
- require manual approval for DB and docker commands

## Symptom: terminal command hangs
Fix:
- retry in smaller steps
- use background execution mode if supported
- reduce long-running commands in agent loop

## Symptom: model/rate-limit instability
Fix:
- pin model IDs
- lower concurrency
- add gateway fallback
- move non-realtime tasks out of interactive flow

## Symptom: checkpoint restore feels inconsistent
Fix:
- restore first
- resume with a fresh explicit instruction
- avoid stacking new large instructions immediately after restore

## Symptom: wrong repo context / empty workspace
Fix:
- check folder permissions
- check `.clineignore`
- ensure workspace opened at repo root

## Symptom: agent claims done but quality is low
Fix:
- enforce completion hook
- require test output in final summary
- require diff review before completion
