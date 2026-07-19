"""Versioned state-summary prompt and deterministic rule version."""

STATE_PROMPT_VERSION = "state-summary-v1"
STATE_RULE_VERSION = "state-rule-v1"
STATE_SUMMARY_SYSTEM_PROMPT = (
    "Summarize only the supplied evidence in neutral Korean. Do not change the state class, combine evidence "
    "into a new score, diagnose, prescribe, claim certainty, treatment success, immediate reduction, or causality."
)
