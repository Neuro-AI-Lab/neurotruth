# Slot Extraction Agent

Last updated: 2026-07-13

## Responsibility

The slot extraction behavior converts intervention conversation context into structured craving slots. It supports handoff readiness and backend memory; it does not decide alert level.

## Public Endpoint

```http
POST /api/intervention/slots
```

## Slot Keys

```text
trigger
duration
intensity
recent_alcohol_use
physiological_context
coping_attempt
safety_concern
support_context
intervention_summary
```

## Rules

- Do not invent missing values.
- Do not infer alcohol use from sensor prediction alone.
- Filter unknown keys before returning data to the app.
- Preserve uncertainty in user-reported context.
- Use only user-role content as factual evidence. Assistant turns identify only
  the topic of the immediately preceding single-topic question.
- Store explicit negative, unknown, and refusal answers as a non-empty flat value
  for that one topic with a short verbatim user quote.
- Preserve existing non-empty `currentSlots`; null, empty, or weaker extracted
  values do not replace them. A non-empty value may replace an existing slot only
  when user-role evidence explicitly corrects or updates that same slot.
- Calculate `missingSlots` after merging current slots with newly accepted values.
- Persist accepted slot keys under the request session ID.
