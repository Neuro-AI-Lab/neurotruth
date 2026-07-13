# NeuroTruth Web

Last updated: 2026-07-10

React/nginx web surface for the NeuroTruth stack. The current demo remains mobile-first; this service is kept for deployment symmetry and future dashboard work.

## Commands

```powershell
cd apps/web
npm install
npm start
npm run build
```

Docker deployment is launched from the repository root:

```powershell
docker compose -f apps/db/docker-compose.yml up -d --build
```

In the local Docker stack, the web service is exposed on port `3000`.

## Files

| File | Role |
|---|---|
| `package.json` | React dependencies and scripts |
| `public/index.html` | React document entry |
| `src/` | Minimal stack status app |
| `Dockerfile` | Web container build |
| `nginx.conf` | nginx runtime configuration |

## Latest Validation

| Check | Result |
|---|---|
| Docker compose config | PASS |
| Docker web container | PASS in local stack |
| Web root | PASS, HTTP 200 at `http://localhost:3000` |
| Backend proxy indicator | PASS, page reports `connected` |
| Browser console | PASS, no warning or error entries during smoke verification |
