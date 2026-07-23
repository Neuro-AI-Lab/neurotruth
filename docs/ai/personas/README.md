# NeuroTruth Demo Personas

These two fictional personas are grounded in the shape of actual model traces.
The source MA10 values and ordering are preserved while only the time axis is
normalized to 60 minutes. Names, life histories, alcohol-use histories, monthly
events, AUQ results, and dialogue are synthetic demo content.

| Role | Persona | Actual trace pattern | Document |
|---|---|---|---|
| Primary live demo | `NT-DP-002 / 1_1_010_V1` | Deep trough, rapid rebound, sustained elevation | [Lee Sujin](NT-DP-002_1_1_010_V1_rebound.md) |
| Secondary/dashboard demo | `NT-DP-001 / 1_1_004_V2` | Early elevation, relief, and late resurgence | [Kim Doyun](NT-DP-001_1_1_004_V2_resurgence.md) |

Use the [operator typing script](DEMO_OPERATOR_SCRIPT.md) during recording.
The complete Git-ready data bundle is documented in the
[persona data guide](data/README.md).

## Data Boundary

- Source windows: 149 for `1_1_004_V2`; 113 for `1_1_010_V1`
- One-hour conversion: calculate source MA10 first, then normalize only time
- Excluded transformations: manual rise keyframes, cross-subject concatenation,
  and invented probabilities
- Monthly history and AUQ: synthetic persona fixtures
- Purpose: application flow, dialogue, slot, handoff, and dashboard demos
- Not evidence of model accuracy, diagnosis, or intervention effect
