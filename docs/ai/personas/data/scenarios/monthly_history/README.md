# Synthetic 30-Day Persona History

This directory contains deterministic demo fixtures for the 30-day period from
2026-06-23 through 2026-07-22.

| Persona | Subject | Craving events | Recommend | Required | AUQ assessments | Latest AUQ |
|---|---|---:|---:|---:|---:|---:|
| `NT-DP-001` | `1_1_004_V2` | 42 | 35 | 7 | 7 | 28/48 |
| `NT-DP-002` | `1_1_010_V1` | 65 | 46 | 19 | 10 | 28/48 |

Each persona has one combined JSON file, one craving-event CSV, and one AUQ CSV.
`monthly_summary.csv` provides the dashboard-level aggregate. AUQ responses use
eight items scored from 0 to 6, for a total range of 0 to 48.
Daily frequency varies from zero to four events. The 30-day averages remain near
two events per day without forcing identical daily counts.

All records are synthetic and for demo/testing only. Event counts, survey scores,
triggers, and alcohol-use records are not observations from the original dataset
participants and must not be used for clinical or model evaluation.

Regenerate from the `Alcohol_Test` directory:

```powershell
python scripts/build_monthly_persona_history.py
```
