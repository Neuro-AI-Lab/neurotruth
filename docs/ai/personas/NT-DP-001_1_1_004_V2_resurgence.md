# NT-DP-001: Resurgent Craving - Kim Doyun

> Fictional NeuroTruth demo persona  
> Model-output source: `1_1_004_V2`  
> A person actively managing alcohol-addiction recovery and relapse risk  
> Identity, history, and dialogue are synthetic

## Core Scenario

Kim Doyun is a 36-year-old IT service planner living with his spouse. He had
years of near-daily heavy alcohol use and entered professional treatment 18
months ago. He remains in counseling and a recovery group. After four
alcohol-free months, he drank once four days ago and is now rebuilding
abstinence.

After a difficult meeting, an urge appeared and then subsided while he walked
and drank water. Seeing a familiar liquor store on the way home causes a second,
stronger urge. He reports 7/10 craving, fatigue, shoulder tension, and mild
sweating. He has not drunk today and reports no self-harm or harm-to-others
thought.

## Signal Grounding

| Field | Value |
|---|---:|
| Source prediction windows | 149 |
| Source duration | About 25 minutes |
| One-hour conversion | Preserve values/order; normalize only the time axis |
| MA10 mean / minimum / maximum / final | 0.5111 / 0.1730 / 0.8487 / 0.6740 |

The trace starts high, drops markedly, and rises again later. The persona is
therefore a **resurgence after apparent relief**, not a gradual-escalation case.

## Synthetic 30-Day Dashboard History

- Craving events: 42; daily average: 1.40
- Recommended / required interventions: 35 / 7
- AUQ: `12 → 15 → 17 → 19 → 21 → 24 → 28`
- Latest AUQ: 28/48

## Manual Demo Input

### Four-turn recording script

1. `한번 가라앉은 줄 알았는데 다시 술 생각이 세게 올라왔어요.`
2. `회의 직후에도 술 생각이 났다가 괜찮아졌는데, 예전에 가던 주류 판매점을 보고 다시 올라왔어요. 오늘은 마시지 않았고 지금은 10점 중 7점이에요.`
3. `네 달 정도 안 마시다가 4일 전에 한 번 다시 마셨어요. 지금은 다시 끊는 중이고, 물을 마시면서 천천히 걷고 있어요.`
4. `배우자에게 전화하고 술집이 없는 길로 갈게요. 지금은 안전하고 저나 다른 사람을 해칠 생각은 없어요.`

### Six-turn validation script

1. `한번 가라앉은 줄 알았는데 다시 술 생각이 세게 올라왔어요.`
2. `오늘은 마시지 않았어요. 네 달 정도 안 마시다가 4일 전에 한 번 다시 마셨고, 지금 다시 끊는 중이에요.`
3. `회의 직후에도 술 생각이 났다가 잠깐 괜찮아졌는데, 퇴근길에 예전에 가던 주류 판매점을 보고 다시 올라왔어요. 지금은 10점 중 7점이에요.`
4. `피곤하고 어깨가 긴장됐고 손에 땀이 조금 나요. 물을 마시고 천천히 걸어 봤어요.`
5. `배우자나 회복 모임에서 알게 된 분에게 연락할 수 있어요. 술집이 없는 다른 길로 가고 싶어요.`
6. `지금은 안전한 곳에 있고 저나 다른 사람을 해칠 생각은 없어요. 배우자에게 전화하고 10분 동안 결정을 미뤄 볼게요.`

## Expected Agent Behavior

- Preserve the distinction between the first and second urge.
- Do not frame the recent lapse as treatment failure.
- Help specify leaving the cue, changing route, contacting support, or delaying action.
- Include trigger, recent use, intensity, body response, coping, support, and
  safety in the handoff without repeating answered questions.

## Account

- Email: `alcohol-test+1_1_004_v2@example.com`
- Password: `testtest1234`
- Test ID: `NT-DEMO-001`
