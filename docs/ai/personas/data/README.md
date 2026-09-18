# NeuroTruth Demo Persona Data

Last updated: 2026-07-23

This folder versions the data needed to reproduce the `NT-DP-001 /
1_1_004_V2` and `NT-DP-002 / 1_1_010_V1` demos and to build frontend fixtures.

## Contents

| Path | Content | Classification |
|---|---|---|
| `raw_g_sensor/<subject>/` | Original calibrated PPG/GSR CSV from the selected G session | Real research data |
| `source_model_outputs/<subject>.csv` | Model output from 20-second windows at a 10-second stride | Actual model output |
| `source_model_outputs/accounts.csv` | Batch test-account extract for both subjects | Test metadata |
| `source_model_outputs/plots/original/` | Original class-1 softmax plots | Actual-output visualization |
| `source_model_outputs/plots/moving_average_10/` | MA10 plots | Actual-output visualization |
| `scenarios/<subject>.csv` | One-hour application playback event stream | Demo fixture |
| `scenarios/<subject>.json` | One-hour stream, cues, and alert metadata | Demo fixture |
| `scenarios/<subject>.png` | Final one-hour plot | Demo visualization |
| `scenarios/monthly_history/` | Thirty-day craving events and AUQ results | Fully synthetic |
| `local_validation/20260723T045807Z/` | Account, dialogue, session, and dashboard integration data | Historical run snapshot |

## Transformation

1. `cravingProbability` in `source_model_outputs/<subject>.csv` is the original
   class-1 softmax output.
2. The moving average is calculated over ten original windows.
3. MA10 values and ordering are preserved while only the time axis is linearly
   normalized to 60 minutes.
4. No invented probabilities, manual rise keyframes, or cross-subject
   concatenation are used.
5. Playback timing, alert and cue fields in `scenarios`, plus all thirty-day
   history, are synthetic demo metadata.

## Source Counts

| Subject | Source windows | Source duration | MA10 mean | MA10 min | MA10 max | MA10 final |
|---|---:|---:|---:|---:|---:|---:|
| `1_1_004_V2` | 149 | about 25 min | 0.5111 | 0.1730 | 0.8487 | 0.6740 |
| `1_1_010_V1` | 113 | about 19 min | 0.5184 | 0.0660 | 0.8321 | 0.6440 |

## Validation Snapshot Caveat

`local_validation/20260723T045807Z` ran about 28 minutes before the final
one-hour scenarios were regenerated. Use it only for account, thirty-day
dashboard, session, and dialogue examples. Report, report-summary, and automated
analysis artifacts are intentionally excluded. The authoritative final one-hour
fixtures are under `scenarios`.

## Data Handling

- `raw_g_sensor` is not synthetic. Confirm research-data authorization and
  institutional handling requirements even when the repository is private.
- Subject identifiers are pseudonymous, but source timestamps and physiological
  signals remain present.
- Names, biographies, alcohol-use histories, monthly events, AUQ responses, and
  dialogue are synthetic demo content.
- These files are not evidence of model accuracy, diagnosis, or intervention
  efficacy.

Korean mirror: [페르소나 데이터](README.ko.md)
