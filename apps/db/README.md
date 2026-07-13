# NeuroTruth DB

Last updated: 2026-07-13

This folder owns the PostgreSQL schema and local Docker stack for NeuroTruth.

## Files

| File | Role |
|---|---|
| `init.sql` | Idempotent Postgres schema initialization |
| `docker-compose.yml` | Local stack for `db`, `backend`, and `web` |

## Commands

Run from the repository root:

```powershell
docker compose -f apps/db/docker-compose.yml config --no-env-resolution
docker compose -f apps/db/docker-compose.yml up -d --build
docker compose -f apps/db/docker-compose.yml ps
```

Create root `.env` from `.env.example` before running the full stack.

Backend health after startup:

```powershell
Invoke-RestMethod -Uri http://localhost:8000/health
```

## Paths

The compose file lives in `apps/db`, so it uses:

| Target | Compose path |
|---|---|
| Backend build context | `../backend` |
| Web build context | `../web` |
| Postgres init SQL | `./init.sql` |
| Root env file | `../../.env` |

## Latest Validation

| Check | Result |
|---|---|
| Compose config with `--no-env-resolution` | PASS |
| Docker backend/db/web smoke | PASS |
| Backend `/health` | PASS |
| Backend async handoff OpenAPI | PASS: HTTP 202 submission route and status route are present; unknown job returns 404 |
| Schema startup | PASS with idempotent `CREATE TABLE IF NOT EXISTS` initialization |
| Same-session persistence | PASS for prediction, alert, conversation, slot, and handoff records |
