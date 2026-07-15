# Phone App

최종 업데이트: 2026-07-15

Phone 앱은 인증된 환자 client이자 Watch의 유일한 backend relay입니다. Watch가 보낸 센서 batch를 표시·업로드하고, 인증된 prediction SSE와 세션 대화를 사용자에게 제공합니다.

## 사용자 흐름

1. 환자가 이메일과 12자 이상 비밀번호로 직접 가입하고 필수 동의 및 선택 동의를 설정합니다.
2. 가입 응답 또는 로그인 응답의 access/refresh token을 저장합니다. Access token은 짧게 사용하고 refresh token은 Android Keystore 기반 암호화 저장소에 보관합니다.
3. `401`이면 refresh token을 한 번 회전하고 원래 요청을 한 번 재시도합니다. 실패하면 로컬 세션을 지우고 로그인 화면으로 이동합니다.
4. 생체신호 동의가 있을 때만 Watch sensor window를 전송하고 prediction SSE를 유지합니다.
5. 갈망 상승 가능성 알림에서 `지금 대화하기` 또는 `나중에`를 선택합니다. 대화를 승인하면 AUQ를 작성하거나 건너뛸 수 있습니다.
6. AI 분석 동의가 있을 때 backend UUID session을 생성/재개하고 안전 확인 뒤 자유 중재 대화를 진행합니다.
7. 수동 종료 또는 비활동 timeout 뒤 상태 추론과 보고서 생성 상태를 조회합니다.
8. 로그아웃하면 monitoring/SSE를 중지하고 phone token을 제거합니다. Watch에는 backend credential이 없습니다.

## 동의

| 항목 | 정책 |
|---|---|
| `tos`, `privacy`, `sensitive` | 가입 필수 |
| `biosignal` | sensor upload/SSE 처리 |
| `aiAnalysis` | 대화 session과 agent 처리 |
| `notification` | craving 알림 표시 |
| `reportGeneration` | 완료/중도 종료 보고서 |
| `cameraRppg` | 전면 카메라 rPPG 측정 허용 |
| `faceVideoRetention` | 수락된 얼굴 영상 암호화 영구 보존 허용 |
| voice | 비활성; 현재 UI/수집 없음 |

변경은 `POST /api/me/consents`로 append-only snapshot을 생성합니다. 철회 전 저장된 자료는 자동 삭제하지 않습니다.

## 센서와 Watch Relay

- 최근 10초 window마다 UUID `clientWindowId`를 생성합니다. 전송 retry는 같은 ID와 같은 payload를 사용하며 새로운 ID로 자동 중복 전송하지 않습니다.
- 같은 ID의 다른 payload는 backend `409`입니다.
- 원시 데이터는 backend에서 canonical JSON → gzip → AES-256-GCM으로 저장됩니다.
- SSE의 class/alert metadata를 `/prediction/class` Wear Data Layer message로 Watch에 전달합니다.
- Watch는 phone 연결이 없으면 backend에 우회 연결하지 않습니다.

## 대화 UI

Backend가 발급한 UUID session만 사용합니다. 신규 응답은 `assistantText`, `phase`, `safety`, `activeInterventions`, `stateSnapshot`, `reportStatus`, `inactivityTimeoutSeconds`를 표시합니다. 신규 session에는 slot 진행률이나 `handoffReady`가 없습니다. 기존 13-slot session은 `legacy=true` 읽기 전용 이력으로만 조회됩니다.

첫 단계는 안전 확인이며, 규칙 엔진이 첫 일반 중재 유형을 선택합니다. 이후 대화는 필수 질문 순서나 완료율 없이 진행합니다. 한 턴에는 필요할 때 짧은 질문 하나만 사용하고 답변했거나 거부한 내용을 반복 질문하지 않습니다.

턴 수 제한은 없고 수동 종료 버튼과 server의 동적 비활동 timeout을 사용합니다. 수동 종료는 `completed`, timeout은 `abandoned`로 저장합니다. 종료 시 근거 기반 상태 추론과 비동기 보고서를 생성하되 현재 앱은 보고서 상태만 표시합니다.

안전 위험 문맥에서는 별도 배너 대신 채팅 안에 119/자살예방 상담전화 109 안내와 “관리자에게 도움 요청 사실을 기록할지” 질문을 한 번 표시합니다. 수락/거절 후 대화는 계속되며 실시간 관리자 연결이나 즉각적 연락을 보장하지 않는다는 문구를 유지합니다.

## 화면 상태

- 인증: 가입, 로그인, 임시 비밀번호 변경, 로그아웃
- 동의: 필수/선택 항목과 현재 처리 가능 상태
- Dashboard: Watch 연결, sensor/monitoring, prediction/alert
- Session: 선택형 AUQ, 안전 확인, 자유 중재 chat, 수동 종료, 동적 timeout
- Dashboard: 24h/7d/30d class·AUQ·event, 상태 요약, live/과거 PPG
- Report: `generating|ready|failed` 상태만 표시하고 본문은 미노출
- 오류: `401` 재인증, `403` 동의 부족, `409` 충돌/active session, `502` agent 일시 실패, `503` readiness/model 실패를 구분해 표시

## 카메라 rPPG 확장

- `biosignal`, `aiAnalysis`, `cameraRppg`, `faceVideoRetention` 동의가 모두 있을 때만 시작합니다.
- 전면 카메라에서 한 얼굴이 1초간 안정되면 720p/30fps 무음 영상을 10초 촬영합니다. 얼굴이 1초 이상 사라지거나 앱이 background로 이동하면 취소합니다.
- 상태는 `얼굴 찾기 → 안정화 → 촬영 → 업로드 → 분석 대기 → 결과/재촬영/실패`로 표시합니다.
- HTTP 202 이후 로컬 MP4를 삭제하고 job/capture 및 pause 상태를 저장해 앱 재시작 후 polling을 재개합니다. 품질 미달은 갈망 없음으로 표시하지 않고 새 촬영을 요구합니다.
- 촬영 시작부터 terminal job까지 Watch 수신은 유지하되 기존 sensor upload와 prediction SSE 반영을 pause하고 모든 종료 경로에서 이전 상태를 복원합니다.
- 카메라 결과는 별도 Phone 카드에만 표시하며 Watch에 전달하지 않습니다. 알림 동의가 있으면 기존 cooldown에 따라 Phone alert/AUQ/chat이 실행될 수 있습니다.
- 수락된 성공·품질 미달·기술 실패 영상은 backend에서 AES-256-GCM 암호화해 관리자 감사 삭제 전까지 영구 보존합니다.

음성/STT/TTS는 현재 범위에 없습니다. 카메라 rPPG는 기본 OFF인 실험 확장이며 실제 Phone-DGX 종단 검증 전에는 release-ready가 아닙니다. Backend bulk download 기능도 제공하지 않습니다.
