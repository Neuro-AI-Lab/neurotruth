# Dialogue Agent

Last updated: 2026-07-18

## Endpoint and Responsibility

`POST /api/sessions/{sessionId}/messages` accepts one authenticated patient message and persists encrypted user/assistant turns and the conversation ledger. New sessions run in `free_dialogue` and never write slots or return slot-coverage fields.

## Dialogue Policy

- `free-dialogue-v4-met-cbt-informed` uses MET and CBT principles as a non-clinical conversation stance, not as a treatment protocol.
- Respect autonomy, free choice, and ambivalence; avoid persuasion, argument, lecturing, and labels.
- Use only one mode per turn: reflection, neutral ambivalence exploration, a situation-thought-feeling/body-action-consequence link, permission-based support, or a user-owned next-step summary.
- Ask zero or one question only when useful. There is no fixed assessment or topic-completion target, and asked or declined questions are not repeated by paraphrase.
- Offer only one coping option, and only after the user asks for help or grants permission.
- Do not frame a lapse as failure or invent motivation, strengths, or facts the user did not express.
- Never diagnose, prescribe, shame, promise clinical outcomes, claim immediate craving reduction, treatment success, certainty, or causality.
- Never suggest self-guided alcohol cue exposure, clinical contingency-management protocols, medication changes, or prescribed drinking amounts.
- Generate two to five short, TTS-friendly Korean sentences. Invalid or repeated output receives one repair; a second failure becomes a provider error.

The dialogue stance adapts autonomy, empathy, ambivalence exploration, self-efficacy, functional analysis, and coping-skill principles summarized in Sang Kyu Lee's 2019 review, “Motivational Enhancement Therapy and Cognitive Behavioral Therapy for Alcohol Use Disorders.” The paper is neither a validated chatbot protocol nor evidence for treatment-effect claims. The optional Korean question guide `niaaa-samhsa-who-ko-v1` remains reference material only.

## Safety

When the LLM judges immediate physical danger, it places 119 guidance before ordinary dialogue and includes 109 for suicide/self-harm context. It states that the system cannot contact responders or guarantee professional support. This is research/demo LLM output, not guaranteed emergency handling.

Provider failures preserve the user message for one retry with the same `clientMessageId` without duplicate storage. Confirmed STT text and directly typed text use the same dialogue policy.
