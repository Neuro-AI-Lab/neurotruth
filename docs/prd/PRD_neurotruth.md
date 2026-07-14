# PRD: NeuroTruth

Last updated: 2026-07-13

Korean mirror: [PRD: NeuroTruth Korean](PRD_neurotruth.ko.md)

## Product Summary

NeuroTruth is a wearable-assisted alcohol craving intervention prototype. It detects craving risk from watch sensor windows, applies deterministic alert rules, guides a text intervention through Bedrock GPT-5.5, extracts craving slots, and produces a handoff report through an asynchronous job flow.

The current demo is mobile-first: Galaxy Watch gathers sensor data, the Android phone shows the monitoring/intervention UI, backend performs prediction and Bedrock intervention, and Postgres stores session memory.

## Service Ownership

| Area | Path | Responsibility |
|---|---|---|
| Backend | `apps/backend` | Prediction, alerts, memory, Bedrock intervention, handoff |
| Web | `apps/web` | Web deployment surface |
| Mobile | `apps/mobile` | Phone UI, Wear OS app, sensor upload, SSE display, chat |
| DB | `apps/db` | Postgres schema and Docker stack |

## Goals

- Keep craving alerting deterministic and explainable.
- Keep the local craving model inside backend.
- Use Bedrock only for conversation, slot extraction, and handoff drafting.
- Preserve mobile API compatibility.
- Provide a usable phone and watch demo flow based on the proven `watch_test` app environment.
- Make local physical-device testing possible over the laptop LAN Docker backend.
- Isolate prediction history, cooldown, and downtrend state by session.
- Keep sensor, prediction, conversation, slot, and handoff records under one session identity.
- Keep an active intervention chat from being replaced by a later AUQ/state-check launch.
- Allow operators to configure the chat response timeout and reset only intervention state without deleting sensor history.

## Non-Goals

- No microphone or STT in this version.
- No diagnosis, medication guidance, or treatment directive.
- No model retraining in this pass.
- No direct LLM access from Android.
- No separate local AI server or GPU service.

## Core Flow

```text
sensor stream -> backend prediction -> rule alert -> text chat -> slot extraction -> handoff report
```

Detailed flow:

```text
Galaxy Watch sensors
  -> Wearable Data Layer
  -> Android phone dashboard
  -> POST /sensor-window
  -> backend RF prediction
  -> deterministic alert rule
  -> GET /prediction-stream
  -> phone/watch recommendation or required alert state
  -> text intervention chat
  -> slot extraction
  -> Markdown handoff report
```

## Current Mobile Experience

| Surface | Required behavior |
|---|---|
| Phone user dashboard | Show current state, latest craving class, alert level, intervention entry point, and monitoring status |
| Phone developer view | Show live charts, upload/SSE status, CSV export, chat timeout configuration, and intervention reset controls |
| Phone alert flow | Use backend `alertAction`, then `alertLevel`, then legacy class fallback; suppress actions for `none` and `cooldown` |
| Phone intervention chat | Run the existing 8-question state check before required intervention, suppress later AUQ launches while chat is active, and keep text turns available while an asynchronous handoff job runs |
| Watch app | Display the latest class and alert state, use a short recommendation vibration, and use stronger required-intervention feedback with phone guidance |

## Alert and Session Rules

- Alert history is isolated per session in a bounded 256-entry LRU evaluator registry.
- Mean-based recommend/required decisions wait for the configured 10-window warm-up.
- A configurable class-2 high streak, defaulting to 3, can trigger early required intervention.
- Sensor upload includes both `sessionId` and backward-compatible `sessionStartedAtMs`.
- The server-echoed session ID is preferred for chat, slot extraction, and handoff persistence.
- Clearing phone data starts a new session and resets alert, state-check, chat, and handoff state.
- Submitting or reopening chat activates an intervention latch. While active, later alerts remain recorded and watch state stays current, but neither AUQ nor repeated watch vibration/notification is presented; closing chat re-enables future distinct required alerts.
- Chat response timeout defaults to 60 minutes and is persisted as an administrator setting in the 1–1,440 minute range.
- Handoff generation uses HTTP 202 job submission plus status polling. The synchronous endpoint remains available for compatibility.

## Verified State

| Area | Result |
|---|---|
| Backend | Compile passed; pytest 50 passed |
| Docker | DB healthy; backend and web running |
| Bedrock | Live GPT-5.5 Mantle adapter call passed; the previously validated Claude Converse route remains available for rollback |
| GPT-5.5 intervention | Known issue: latest full chat smoke returns HTTP 502 during slot extraction |
| Persistence | Prediction, alert, conversation, slot, and handoff linked under one session |
| Android | Phone/Wear build, unit tests, and latest lint passed after AUQ/async-handoff changes |
| Physical devices | Latest phone/watch APK installs and launches passed; full live chat and watch-suppression observation remain |

## Safety Positioning

- Alert decisions are rule-based and based on recent prediction classes, not LLM judgment.
- Bedrock responses should be supportive and CBT-style, but must avoid clinical certainty.
- Handoff reports must separate user-reported facts from model or alert context.
- Acute safety concerns should route the user toward immediate human or emergency support.
