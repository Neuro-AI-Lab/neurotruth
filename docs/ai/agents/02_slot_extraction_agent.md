# Legacy Slot Extraction Agent

Last updated: 2026-07-15

This document describes retained pre-redesign history. New intervention-first sessions do not run slot extraction, create `session_slots`, update legacy memory, or return `slots`, `missingSlots`, or `handoffReady`. Existing slot sessions remain readable with `legacy=true`; every mutation attempt returns `legacy_session_read_only`, and no backfill is performed.

## Exact Slot Keys

```text
episode_trigger
current_context
alcohol_context
drinking_status
habit_pattern
emotional_context
physical_context
alcohol_expectancy
coping_context
support_context
user_goal
safety_context
additional_context
```

## Historical Value Contract

Each retained slot is `{"status":"answered|unknown|declined","data":...}`. These encrypted values are historical patient records and must not be rewritten by the new dialogue agent.

The old `handoffReady` rule is not a current product contract. New sessions use an encrypted asked/refused topic ledger only for conversation continuity; it is not a replacement questionnaire or clinical fact store.
