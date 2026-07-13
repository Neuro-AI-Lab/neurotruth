# Dialogue Agent

Last updated: 2026-07-13

## Responsibility

The dialogue behavior provides text-first craving intervention support on the phone. It should acknowledge the user's current state, suggest one small coping step, and ask at most one focused follow-up question.

## Public Endpoint

```http
POST /api/intervention/chat
```

## Expected Behavior

- Use supportive, nonjudgmental Korean by default.
- Treat positive, negative, unknown, and declined answers as completed topics.
- Use accumulated `currentSlots` and `missingSlots`; never re-ask a completed
  topic by paraphrase.
- Ask at most one concise question, and do not force empathy or a question when
  a neutral acknowledgement is more appropriate.
- Respect deterministic backend alert context without overstating certainty.
- Avoid diagnosis, treatment claims, or shame-based language.
- Encourage immediate support or emergency help if acute safety risk appears.
- Compare non-safety questions with the latest three assistant questions. An
  exact or strongly similar question receives one Bedrock repair attempt. If
  repair fails or repeats, the backend removes the repeated question and returns
  a question-free acknowledgement. Safety, probe, crisis, and emergency content
  bypasses this suppression.

## Implementation

The backend builds the Bedrock prompt through `apps/backend/app/ai/bedrock_agents.py` and persists user/assistant turns under the shared intervention session ID.

Repetition comparison is stateless and request-local. It uses NFKC and case
normalization, removes leading acknowledgement wording and punctuation/spacing,
and applies `difflib.SequenceMatcher` with a threshold of `0.86`. No repair
metadata is added to the public response.
