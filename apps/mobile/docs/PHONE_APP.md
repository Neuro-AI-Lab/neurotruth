# Phone App

최종 업데이트: 2026-07-19

Phone 앱은 인증된 환자 client이자 Watch의 유일한 backend relay입니다. Watch가 보낸 센서 batch를 표시·업로드하고, 인증된 prediction SSE와 세션 대화를 사용자에게 제공합니다.

## 사용자 흐름

1. 환자가 이메일과 12자 이상 비밀번호로 직접 가입하고 필수 동의 및 선택 동의를 설정합니다.
2. 가입 응답 또는 로그인 응답의 access/refresh token을 저장합니다. Access token은 짧게 사용하고 refresh token은 Android Keystore 기반 암호화 저장소에 보관합니다.
3. `401`이면 refresh token을 한 번 회전하고 원래 요청을 한 번 재시도합니다. 실패하면 로컬 세션을 지우고 로그인 화면으로 이동합니다.
4. Android가 connected Wear node 목록을 확인합니다. Watch가 연결되면 sensor window를 전송하고, 연결된 node가 없으면 Home에서 수동 foreground 20초 camera-rPPG CTA를 우선 표시합니다.
5. 갈망 상승 가능성 알림에서 `지금 대화하기` 또는 `나중에`를 선택합니다. 대화를 승인하면 AUQ를 작성하거나 건너뛸 수 있습니다.
6. AI 분석 동의가 있을 때 backend UUID session을 생성/재개하고 중립 안내 뒤 `free_dialogue` 자유 대화를 진행합니다.
7. 수동 종료 또는 비활동 timeout 뒤 상태 추론과 보고서 상태를 조회합니다. 기본 설정에서는 보고서 상태가 `not_started`입니다.
8. 로그아웃하면 monitoring/SSE를 중지하고 phone token을 제거합니다. Watch에는 backend credential이 없습니다.

## 동의

| 항목 | 정책 |
|---|---|
| `tos`, `privacy`, `sensitive` | 가입 필수 |
| `biosignal` | sensor upload/SSE 처리 |
| `aiAnalysis` | 대화 session과 agent 처리 |
| `notification` | alert metadata/action 표시; 수동 AUQ·대화 진입은 차단하지 않음 |
| `reportGeneration` | 완료/중도 종료 보고서 |
| `cameraRppg` | 전면 카메라 rPPG 측정 허용 |
| `faceVideoRetention` | 수락된 얼굴 영상 암호화 영구 보존 허용 |
| `voice` | 선택적 STT 녹음/전사; 기본 STT 기능은 OFF |

변경은 `POST /api/me/consents`로 append-only snapshot을 생성합니다. 철회 전 저장된 자료는 자동 삭제하지 않습니다.

## 센서와 Watch Relay

- 20초 Watch window를 10초마다 전송하며 각 window에 UUID `clientWindowId`를 생성합니다. `windowMs`는 `19,500..20,500`이고 시작/종료 차이와 일치해야 하며, 그렇지 않으면 `422`입니다. 전송 retry는 같은 ID와 같은 payload를 사용하며 새로운 ID로 자동 중복 전송하지 않습니다.
- 같은 ID의 다른 payload는 backend `409`입니다.
- 같은 ID·payload의 동시 retry는 backend가 직렬화해 저장과 prediction을 한 번만 수행하고 같은 결과를 반환합니다.
- 원시 데이터는 backend에서 canonical JSON → gzip → AES-256-GCM으로 저장됩니다.
- SSE의 class/alert metadata를 `/prediction/class` Wear Data Layer message로 Watch에 전달합니다.
- Watch는 phone 연결이 없으면 backend에 우회 연결하지 않습니다.
- Watch 연결 판단은 Android의 connected Wear node 목록을 사용하며 sensor packet freshness로 추정하지 않습니다. 상태는 확인 중·연결됨·연결 안 됨·확인 오류를 구분합니다.

## 대화 UI

Backend가 발급한 UUID session만 사용합니다. 신규 응답은 `userMessageId`, `assistantMessageId`, `assistantText`, `phase=free_dialogue`, `reportStatus`, `inactivityTimeoutSeconds`를 표시합니다. 신규 session에는 slot 진행률이나 `handoffReady`가 없습니다. 기존 13-slot session은 `legacy=true` 읽기 전용 이력으로만 조회됩니다.

신규 session은 “지금 상황이나 원하는 도움을 편하게 말씀해 주세요”라는 중립 안내로 시작합니다. 규칙 기반 첫 중재나 필수 질문 순서 없이 자유 대화를 진행하며, 한 응답에는 질문을 최대 하나만 사용하고 이전 질문이나 사용자가 거부한 질문을 반복하지 않습니다.

Phone은 새 사용자 메시지마다 UUID `clientMessageId`를 생성합니다. Agent 호출이 구조화된 HTTP `502`로 실패하면 사용자 말풍선을 그대로 유지하고 `다시 시도` 버튼을 표시합니다. 수동 재시도는 같은 `clientMessageId`와 같은 내용을 재사용해 사용자 메시지를 중복 저장하지 않으며 최초 요청 뒤 한 번만 허용됩니다. 재시도까지 실패하면 버튼을 숨깁니다.

턴 수 제한은 없고 수동 종료 버튼과 server의 동적 비활동 timeout을 사용합니다. 수동 종료는 `completed`, timeout은 `abandoned`로 저장합니다. 종료 시 근거 기반 상태 추론은 항상 저장합니다. 기본 `REPORT_AI_ENABLED=false`에서는 보고서 AI를 호출하지 않고 `not_started`를 표시하며, 플래그가 켜진 경우에만 비동기 보고서 상태를 추적합니다.

신규 자유 대화의 위험 문맥 판단과 119/자살예방 상담전화 109 안내는 LLM prompt에 의존합니다. 이는 연구·데모용 보조 기능이며 안정적인 응급 탐지, 실시간 관리자 연결 또는 즉각적 연락을 보장하지 않습니다.

## 화면 상태

- 인증: 가입, 로그인, 임시 비밀번호 변경, 로그아웃
- 동의: 필수/선택 항목과 현재 처리 가능 상태
- Dashboard: Watch 연결, sensor/monitoring, prediction/alert
- Session: 선택형 AUQ, `free_dialogue`, 안정적인 `clientMessageId`, agent 실패 1회 수동 retry, 수동 종료, 동적 timeout
- Patient Dashboard: 오늘 24개 시간별 갈망 가능성 막대, 7/30일 일별 `recommend|required` 이벤트 막대, 오늘 시간별·7/30일 일별 AUQ 평균 막대
- Patient Dashboard: 회색 no-data와 prediction이 있는 유효한 이벤트 `0건`을 구분하고, 막대 선택 시 평균·최소·최대·표본/응답/이벤트 건수를 표시
- Patient Dashboard: 상태 요약, 보고서 상태, live Watch PPG와 소유 prediction의 과거 PPG preview 유지
- Report: `not_started|generating|ready|failed` 상태만 표시하고 본문은 미노출
- 오류: `401` 재인증, `403` 동의 부족, `409` 충돌/active session, `502` agent 일시 실패, `503` readiness/model 실패를 구분해 표시

막대 대시보드는 Phone 전용입니다. 기존 관리자 웹과 Wear OS 화면·동작은 변경하지 않습니다.

## 카메라 rPPG 확장

- `biosignal`, `aiAnalysis`, `cameraRppg`, `faceVideoRetention` 동의가 모두 있을 때만 시작합니다.
- 위 네 동의와 `/api/rppg/status` readiness가 모두 필요합니다. Service가 disabled/unavailable이거나 model/storage가 준비되지 않으면 Watch 부재를 보완한다고 표시하지 않고 측정을 시작하지 않습니다.
- connected Wear node가 없으면 Home에서 manual CTA를 primary로, 연결되면 Watch monitoring을 primary로 두면서 rPPG action은 optional로 유지합니다. 연결 확인 중이나 오류를 disconnected로 표시하지 않습니다.
- 전면 카메라에서 한 얼굴이 1초간 안정되면 720p/30fps 무음 영상을 20초 촬영합니다. 허용 `durationMs`는 `19,500..20,500`이며 얼굴이 1초 이상 사라지거나 앱이 background로 이동하면 취소합니다.
- rPPG는 사용자가 foreground에서 시작하는 point-in-time 측정이며 자동 반복·background capture·disconnect notification을 제공하지 않습니다.
- 상태는 `얼굴 찾기 → 안정화 → 촬영 → 업로드 → 분석 대기 → 결과/재촬영/실패`로 표시합니다.
- HTTP 202 이후 로컬 MP4를 삭제하고 job/capture 및 pause 상태를 저장해 앱 재시작 후 polling을 재개합니다. 품질 미달은 갈망 없음으로 표시하지 않고 새 촬영을 요구합니다.
- 촬영 시작부터 terminal job까지 Watch 수신은 유지하되 기존 sensor upload와 prediction SSE 반영을 pause하고 모든 종료 경로에서 이전 상태를 복원합니다.
- 카메라 결과 source는 `camera_rppg`이고 Phone의 latest craving state, main card, chart, alert 흐름과 rPPG 결과 카드를 갱신하지만 Watch에는 전달하지 않습니다. 이후 성공한 Watch prediction이 latest 결과를 대체할 수 있습니다. `notification` 동의는 alert metadata/action 표시만 제어하고, `aiAnalysis` 동의가 session/model 처리를 제어합니다.
- 수락된 성공·품질 미달·기술 실패 영상은 backend에서 AES-256-GCM 암호화해 관리자 감사 삭제 전까지 영구 보존합니다.

한국어 STT는 구현된 선택 기능이며 기본 OFF입니다. 전사 결과는 편집·확인한 뒤 typed text와 같은 `POST /api/sessions/{id}/messages`로 보내고 `inputModality`만 `voice`로 저장합니다. AI 응답 음성은 Android 로컬 TTS가 담당합니다. 카메라 rPPG는 `RPPG_ENABLED=true`가 기본이지만 `false`로 끌 수 있고, 실제 Phone-DGX 종단 검증 전에는 release-ready가 아닙니다. Android 16KB build baseline은 AGP 8.5.2, Gradle 8.7, CameraX 1.4.0, ML Kit face detection 16.1.7이며 APK zip/ELF alignment를 검증합니다. Backend bulk download 기능은 제공하지 않습니다.
