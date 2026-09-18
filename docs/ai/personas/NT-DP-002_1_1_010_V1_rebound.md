# NT-DP-002: Sustained Craving Rebound - Lee Sujin

> Fictional primary NeuroTruth demo persona  
> Model-output source: `1_1_010_V1`  
> A person actively managing alcohol-addiction recovery and repeated relapse risk  
> Identity, history, and dialogue are synthetic

## Core Scenario

Lee Sujin is a 31-year-old retail operations team lead who lives alone. She
experienced repeated heavy drinking and loss of control and entered professional
treatment two years ago. After two alcohol-free months, she drank six days ago
and is again maintaining abstinence.

Distress after a workplace conflict appeared to settle. At a convenience store
near her bus stop, an alcohol display causes a rapid rebound that remains high.
She reports 8/10 craving, a fast heartbeat, and jaw and shoulder tension. She
steps away and slows her breathing but has not contacted support. She has not
drunk today and reports no self-harm or harm-to-others thought.

## Signal Grounding

| Field | Value |
|---|---:|
| Source prediction windows | 113 |
| Source duration | About 19 minutes |
| One-hour conversion | Preserve values/order; normalize only the time axis |
| MA10 mean / minimum / maximum / final | 0.5184 / 0.0660 / 0.8321 / 0.6440 |

The trace reaches a deep trough, rebounds quickly, and remains elevated. This
persona is the recommended live-demo account.

## Synthetic 30-Day Dashboard History

- Craving events: 65; daily average: 2.17
- Recommended / required interventions: 46 / 19
- AUQ: `13 → 17 → 16 → 20 → 18 → 22 → 21 → 27 → 25 → 28`
- Latest AUQ: 28/48

## Manual Demo Input

### Four-turn recording script

1. `퇴근할 때는 마음이 불편했는데 한동안 괜찮아졌어요. 그런데 지금 다시 확 올라왔어요.`
2. `직장 동료와 부딪힌 뒤 편의점 술 진열대를 봤어요. 5분도 안 돼서 마시고 싶은 마음이 10점 중 8점까지 올라왔어요.`
3. `오늘은 마시지 않았어요. 두 달 버티다가 6일 전에 다시 마셨고, 그 뒤로 다시 끊고 있어요. 지금은 진열대에서 떨어져 천천히 숨을 쉬고 있어요.`
4. `언니에게 전화하고 다음 버스를 타고 집에 갈게요. 지금은 안전하고 저나 다른 사람을 해칠 생각은 없어요.`

### Six-turn validation script

1. `퇴근할 때는 마음이 불편했는데 한동안 괜찮아졌어요. 그런데 지금 다시 확 올라왔어요.`
2. `편의점 술 진열대를 보니까 갑자기 한잔하고 싶다는 생각이 강해졌어요.`
3. `오늘은 마시지 않았어요. 두 달 버티다가 6일 전에 다시 마셨고, 그 뒤로 다시 끊고 있어요.`
4. `직장 동료와 부딪힌 뒤부터였고 5분 정도밖에 안 됐는데 금방 세졌어요. 지금은 10점 중 8점쯤이에요.`
5. `심장이 빨리 뛰고 턱과 어깨에 힘이 들어갔어요. 천천히 숨을 쉬고 진열대에서 떨어져 나왔어요.`
6. `언니나 중독 상담 선생님께 전화할 수 있어요. 지금은 안전하고 저나 다른 사람을 해칠 생각은 없어요.`

## Expected Agent Behavior

- Reflect that the urge subsided and then rebounded.
- Preserve current recovery after the lapse without stigma.
- Help specify leaving the store, breathing, contacting support, or going home.
- Include workplace conflict, alcohol cue, recent use, 8/10 intensity, physical
  response, coping, support, and safety in the handoff.

## Account

- Email: `alcohol-test+1_1_010_v1@example.com`
- Password: `testtest1234`
- Test ID: `NT-DEMO-002`
