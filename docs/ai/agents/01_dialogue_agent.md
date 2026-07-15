# Dialogue Agent

Last updated: 2026-07-15

## Endpoint and Responsibility

`POST /api/sessions/{sessionId}/messages` accepts one authenticated patient message, persists encrypted user/assistant turns, updates the encrypted conversation ledger, and returns `assistantText`, `phase`, `safety`, `activeInterventions`, `stateSnapshot`, and `reportStatus`. New sessions never write slots or return slot-coverage fields.

## Dialogue Policy

- Use concise, supportive, nonjudgmental Korean.
- Ask at most one short question. Topic order is optional guidance, not a questionnaire or completion target.
- Do not repeat a topic already asked or declined, including by paraphrase, unless the patient explicitly corrects it. The encrypted topic ledger survives app restarts.
- Acknowledge corrections and preserve explicit negatives, uncertainty, and refusal.
- Do not diagnose, prescribe, shame, promise clinical outcomes, or infer drinking from prediction data.
- Deterministic rules select the first intervention. Later LLM suggestions are limited to the approved intervention allowlist and every delivered suggestion is persisted with order and version evidence.
- When `interventionsEnabled=false`, continue safety handling and state inference without ordinary intervention wording or rows.
- Never claim immediate craving reduction, CBT efficacy, diagnosis, treatment success, certainty, or a causal effect. One validation repair is allowed before a deterministic supportive fallback.

The optional Korean question guide is versioned as `niaaa-samhsa-who-ko-v1`. It uses original, non-clinical paraphrases informed by [NIAAA brief intervention](https://www.niaaa.nih.gov/health-professionals-communities/core-resource-on-alcohol/conduct-brief-intervention-build-motivation-and-plan-change), [SAMHSA TIP 35](https://library.samhsa.gov/product/tip-35-enhancing-motivation-change-substance-use-disorder-treatment/pep19-02-01-003), and [WHO mhGAP alcohol guidance](https://www.who.int/teams/mental-health-and-substance-use/treatment-care/mental-health-gap-action-programme/evidence-centre/alcohol-use-disorders). It is not a clinical script.

## Safety

Immediate-risk content bypasses normal LLM sequencing so support is not delayed by provider failure. The response may direct the patient to 119 and Korean suicide-prevention line 109 and asks once whether to record requested administrator involvement. It explicitly states that the record does not guarantee live connection or immediate contact. Acceptance or refusal is audited and remaining dialogue continues.

Provider failures preserve the patient message and return a deterministic supportive response with no new question or unapproved intervention. Voice, rPPG, self-event capture, and wearable-absent AUQ automation are not dialogue inputs in this release.
