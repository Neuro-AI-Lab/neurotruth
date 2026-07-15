# NeuroTruth Database and Local Stack

Last updated: 2026-07-15

This directory owns the PostgreSQL extension bootstrap and Docker Compose stack. Alembic under `apps/backend/alembic` owns all business-schema changes.

## Files and Volumes

| Item | Role |
|---|---|
| `init.sql` | Installs required PostgreSQL extensions only |
| `docker-compose.yml` | Local `db`, `backend`, and `web` stack |
| `postgres_data_v25` | Fresh authenticated PostgreSQL data |
| `encrypted_sensor_data` | AES-GCM raw sensor files |
| rPPG storage volume | Optional AES-GCM camera videos and provider payloads |

The legacy `postgres_data` volume is backup/rollback material. Do not attach it to the new migration path and do not expect backfill.

## Migration Chain

| Revision | Change |
|---|---|
| `20260715_0001` | 18-table authenticated baseline |
| `20260715_0002` | DGX rPPG captures/jobs and prediction linkage |
| `20260715_0003` | Intervention-first session state, `state_inferences`, multi-intervention evidence |

`session_slots` remains for legacy read-only history. New sessions do not write it.

## Start a Fresh Local Stack

Create and review root `.env`, then run from the repository root:

```powershell
docker compose -f apps/db/docker-compose.yml config
docker compose -f apps/db/docker-compose.yml up -d --build
docker compose -f apps/db/docker-compose.yml ps
docker compose -f apps/db/docker-compose.yml logs backend
```

The backend applies `alembic upgrade head` before starting. Readiness fails if the database, migration revision, encryption keyring, or required storage is unavailable.

Health:

```powershell
Invoke-RestMethod -Uri http://localhost:8000/health
```

## Deployment Safety

1. Stop writes to the legacy deployment.
2. Back up the existing `postgres_data` volume.
3. Create fresh database and encrypted-storage volumes.
4. Run migrations through `20260715_0003`.
5. Deploy backend and Android together, then the administrator web.
6. Keep the legacy image/volume for rollback; do not downgrade new-schema data.

Do not store plaintext exports, decrypted sensor files, keys, or database dumps in Git.

## Latest Validation

| Check | Result |
|---|---|
| Fresh PostgreSQL 0001→0002→0003 | PASS |
| Populated fixture 0002→0003 | PASS |
| Legacy session interaction/dialogue state after 0003 | Preserved as NULL/read-only |
| Expected application tables | PASS |
| Required unique indexes | PASS |
