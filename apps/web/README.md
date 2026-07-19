# NeuroTruth Web

Last updated: 2026-07-15

React/nginx administrator surface for the NeuroTruth supportive-intervention research stack; patients use the Android app. The console shows patient-level Low/Mid/High trends, separate AUQ values, alert/session/intervention event timing, state-inference metadata, and report generation status for `24h|7d|30d`. These views describe recorded evidence and do not claim treatment effect or causality.

## Restricted patient dashboard

- `GET /api/admin/patients/{patientId}/dashboard?range=24h|7d|30d` populates class trends, AUQ, event markers, state status, and report status.
- Dashboard list/trend views never request or render raw PPG, decrypted state summaries, intervention text, or report bodies.
- Message, state, intervention, and report content remains available only through the existing reason-gated reveal flow; every successful reveal is audited and returned with no-store semantics.
- Legacy slot sessions remain timeline-only read-only history. The dashboard does not display slot completion or `handoffReady`.

## Camera rPPG administration

- The entire section is absent unless `/api/rppg/status` reports both enabled and ready/available. A denied or unavailable status fails closed to hidden UI.
- `GET /api/admin/rppg/captures` populates a metadata-only capture table.
- Playback requires a nonblank reason and uses `POST /api/admin/rppg/captures/{captureId}/reveal-video`. The response is rendered from a temporary Blob URL, which is revoked when the modal closes.
- The UI provides no download button. `controlsList="nodownload"` and disabled context/Picture-in-Picture reduce accidental export but cannot prevent a privileged viewer from preserving rendered bytes; authorization, policy, and audit are the controls.
- Deletion requires a reason plus the exact capture UUID and calls `DELETE /api/admin/rppg/captures/{captureId}` with `confirmCaptureId`.
- All accepted success, quality-failure, and technical-failure videos remain AES-256-GCM encrypted until audited deletion. `RPPG_ENABLED=true` is the default, `false` remains the deployment off switch, and the section still stays hidden until the backend reports ready/available. The feature is not release-ready before joint phone/DGX validation.

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

In the local Docker stack, nginx still listens on container port `3000`, while Docker exposes it on host port `45511` by default (`WEB_HOST_PORT` can override this).

## Files

| File | Role |
|---|---|
| `package.json` | React dependencies and scripts |
| `public/index.html` | React document entry |
| `src/` | Administrator authentication, restricted intervention dashboard, patient operations, settings, and capability-gated rPPG controls |
| `Dockerfile` | Web container build |
| `nginx.conf` | nginx runtime configuration |

## Latest Validation

| Check | Result |
|---|---|
| Docker compose config | PASS |
| Docker web container | PASS in local stack |
| Web root | PASS, HTTP 200 at `http://localhost:45511` |
| Backend proxy indicator | PASS, page reports `connected` |
| Browser console | PASS, no warning or error entries during smoke verification |
| Production build after intervention dashboard | PASS, `npm.cmd run build` on 2026-07-15 |
