# NeuroTruth Database and Local Stack

Last updated: 2026-07-19

This directory owns the PostgreSQL extension bootstrap and Docker Compose stack. Alembic under `apps/backend/alembic` owns all business-schema changes.

## Files and Volumes

| Item | Role |
|---|---|
| `init.sql` | Installs required PostgreSQL extensions only |
| `docker-compose.yml` | Local `db`, `backend`, optional/default-off `stt`, and `web` stack |
| `postgres_data_v25` | Fresh authenticated PostgreSQL data |
| `encrypted_sensor_data` | AES-GCM raw sensor files |
| `encrypted_rppg_data` | Optional AES-GCM camera videos and provider payloads |

The legacy `postgres_data` volume is backup/rollback material. Do not attach it to the new migration path and do not expect backfill.

## Migration Chain

| Revision | Change |
|---|---|
| `20260715_0001` | 18-table authenticated baseline |
| `20260715_0002` | DGX rPPG captures/jobs and prediction linkage |
| `20260715_0003` | Intervention-first session state, `state_inferences`, multi-intervention evidence |
| `20260716_0004` | `free_dialogue` session phase and unique client-message retry idempotency |
| `20260717_0005` | Retained legacy 10-second and current 20-second rPPG duration rule; downgrade intentionally refused |

`20260717_0005` is the current required head. `session_slots` remains for legacy
read-only history. New sessions start in `free_dialogue`, do not write slots,
and use `clientMessageId` metadata to retry a failed dialogue once without
duplicating the user message.

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
Invoke-RestMethod -Uri http://localhost:25991/health
```

## DGX Spark Deployment Beside rPPG

When the DGX Spark already runs the FactorizePhys rPPG API on its internal/LAN
port `8000`, keep that port for rPPG. NeuroTruth listens on DGX port `25991`,
with router TCP forwarding from public port `58441` to
`192.168.68.50:25991`.
Add these values to the DGX-only root `.env` (never commit that file):

```dotenv
BACKEND_HOST_PORT=25991
RPPG_ENABLED=true
RPPG_BASE_URL=http://192.168.68.50:8000
```

The NeuroTruth backend listens on container port `25991`, and nginx proxies
internal `/api/` requests to `backend:25991`. The web and rPPG services do not
need public router port forwarding.

From the repository root on the DGX:

```bash
docker compose -f apps/db/docker-compose.yml up -d --build
docker compose -f apps/db/docker-compose.yml ps
curl -fsS http://127.0.0.1:25991/health
curl -I http://127.0.0.1:45511/
```

For the configured external mobile build, use
`http://223.194.33.26:58441` as the API base URL. Do not point the phone directly
at the web or rPPG services. Plain HTTP is for controlled experiments only;
deploy TLS before transmitting real participant data over the public internet.

## Deployment Safety

1. Stop writes to the legacy deployment.
2. Back up the existing `postgres_data` volume.
3. Create fresh database and encrypted-storage volumes.
4. Run migrations through the current head, `20260717_0005`.
5. Deploy backend and Android together, then the administrator web.
6. Keep the legacy image/volume for rollback; do not downgrade new-schema data.

Do not store plaintext exports, decrypted sensor files, keys, or database dumps in Git.

## Latest Validation

| Check | Result |
|---|---|
| Existing Compose runtime head | PASS at `20260717_0005` in the prior restructured-backend smoke |
| Fresh PostgreSQL 0001→0005 | Not rerun during this 2026-07-19 documentation refresh |
| Populated upgrade through 0005 | Not rerun during this documentation refresh; `0005` is additive and refuses downgrade while 20-second captures may exist |
| Legacy session interaction/dialogue state | Preserved as NULL/read-only |
| Expected application tables | PASS |
| Required unique indexes | PASS |
