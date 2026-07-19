# NeuroTruth 앱 디자인·프론트엔드 핸드오프 V16

최종 갱신: 2026-07-19
대상: Android Phone/Wear OS 및 프론트엔드 구현 담당자
대응 자료: [neurotruth_app_design_handoff_v16.pptx](handoff/neurotruth_app_design_handoff_v16.pptx)

## 1. 문서 목적

V16은 현재 코드에 구현된 화면 흐름과 상태 정책을 프론트엔드 구현 기준으로 고정한다. Watch는 선택 장치이며, 연결되지 않은 사용자는 사용자가 직접 시작하는 20초 얼굴 rPPG로 갈망 가능성을 확인할 수 있다.

- 공개 API 경로와 DB·Alembic 계약은 변경하지 않는다.
- 문자와 음성은 같은 대화 세션과 메시지 저장 흐름을 사용한다.
- rPPG는 연속 감지가 아니라 사용자가 실행하는 1회 측정이다.
- DGX 운영 절차는 이 문서가 아니라 [DGX Spark 배포 가이드](deployment/DGX_SPARK_DEPLOYMENT.ko.md)에서 다룬다.

## 2. 최종 사용자 흐름

```text
제품 안내 → 가입·로그인 → 동의·권한 → 홈
                                      ├─ Watch 센서 예측
                                      ├─ 20초 얼굴 rPPG 측정
                                      └─ 갈망 알림 → AUQ 또는 바로 대화
                                                           ↓
                                                  AI 챗봇 → 대시보드
```

| 화면 | 역할 | 핵심 구현 |
|---|---|---|
| NT-01 | 제품 안내 | 대상·한계를 확인한 뒤 가입으로 이동 |
| NT-02 | 가입·로그인 | 환자 계정 생성 및 인증 |
| NT-03 | 동의·권한 | 필수 동의와 선택 동의를 분리하고 실제 사용 시 권한 요청 |
| NT-04 | 홈 | 프로필, 갈망 4단계, Watch 연결 상태, 얼굴 측정 CTA, 최신 결과 |
| NT-04R | 얼굴 측정 | 얼굴 안정화, 20초 촬영, 업로드, 분석 대기, 결과·재시도 |
| NT-05 | 갈망 알림 | `지금 대화하기` 또는 `나중에` |
| NT-06 | AUQ | 8문항을 7단계로 답하거나 건너뛴 뒤 대화 이동 |
| NT-07 | AI 챗봇 | 문자·음성 입력, AI 텍스트, Android TTS, 재시도·종료 |
| NT-08 | 대시보드 | 갈망 단계 분포, 이벤트, AUQ X/56, 시간대별 결과 |
| NT-09 | 설정 | 계정, 동의, 권한 관리 |

## 3. 홈의 Watch·rPPG 상태 정책

워치 미연결 여부는 센서 수신 지연이 아니라 `NodeClient.connectedNodes.isEmpty()`로 판단한다. 센서 수신 상태 `isReceiving`은 연결 상태와 별도이며, 연결이 끊기면 `false`로 초기화한다.

### 3.1 Watch 상태

| 내부 상태 | 사용자 표시 | 준비 완료 시 안내 | CTA 우선순위 |
|---|---|---|---|
| `CHECKING` | 확인 중 | Watch 연결 확인 중이며 얼굴 측정은 별도로 사용 가능 | 보조 |
| `CONNECTED` | 연결됨 | Watch 모니터링 유지, 얼굴 측정은 필요할 때 쓰는 보조 측정 | 보조 |
| `DISCONNECTED` | 연결 안 됨 | “Watch가 연결되지 않았습니다. 20초 얼굴 측정으로 현재 상태를 확인할 수 있습니다.” | 주 CTA |
| `ERROR` | 확인 실패 | 미연결이라고 단정하지 않고 연결 확인 실패를 안내 | 보조·재확인 |

Watch 연결 변경 시 최초 조회와 동일한 `connectedNodes` 조회를 다시 수행한다. ViewModel 종료 시 연결 이벤트 구독을 해제해야 한다.

### 3.2 동의·서버 준비 우선순위

| 조건 | 버튼 문구 | 실행 가능 | 안내 |
|---|---|---:|---|
| 필수 동의 누락 | 동의 후 얼굴 측정 | 아니요 | 생체신호·AI 분석·카메라 rPPG·얼굴 영상 보관 동의 필요 |
| 상태 조회 중 | 상태 다시 확인 | 예 | 서버 준비 상태 확인 중 |
| 기능 비활성·서비스 불가·모델 미준비 | 상태 다시 확인 | 예 | 현재 측정 불가, 상태 재조회 유도 |
| 준비 완료 + Watch 미연결 | 20초 얼굴 측정 시작 | 예 | 주 CTA |
| 준비 완료 + 그 외 Watch 상태 | 20초 얼굴 측정 | 예 | 보조 CTA |

측정 가능 여부는 `enabled && available && modelLoaded`로 판단한다. Watch가 없더라도 서버가 준비되지 않았거나 동의가 없으면 측정 가능하다고 표시하지 않는다.

## 4. 문자·음성 대화는 하나의 메시지 흐름

챗봇 화면에는 다음 조작을 항상 명확히 노출한다.

| 위치 | 버튼·상태 문구 | 동작 |
|---|---|---|
| 입력창 오른쪽 | `마이크` → `정지` → `인식 중` | STT 녹음 시작·정지·전사 진행 상태 표시 |
| AI 말풍선 안 | `듣기` ↔ `정지` | 해당 AI 응답을 Android TTS로 재생하거나 중지 |
| 메시지 목록 아래 | `AI 응답 자동 읽기` 스위치 | 새 AI 응답 도착 시 자동 TTS 재생 |
| 입력창 오른쪽 | `전송` | 편집을 마친 문자 또는 STT 문장 전송 |
| 오류·화면 하단 | `다시 시도`, `대화 종료` | 실패 메시지 재시도와 명시적 세션 종료 |

STT와 TTS는 하나의 버튼으로 합치지 않는다. STT는 사용자 음성을 입력하는 버튼이고, TTS는 AI 답변을 재생하는 버튼이다.

V16 PPT의 프론트엔드 디자인 4·5·8·9·10페이지에는 이 구분이 화면에서 바로 보이도록 `STT · 마이크`와 `TTS · 듣기/정지`를 실제 버튼 형태로 표시한다. 8페이지는 상속된 화면에 챗봇 캡처가 없으므로 화면 흐름 아래의 `공통 챗봇 조작` 스트립으로 표시한다.

### 문자

```http
POST /api/sessions/{sessionId}/messages
Content-Type: application/json

{
  "clientMessageId": "<uuid>",
  "content": "사용자가 입력한 문장",
  "inputModality": "text"
}
```

### 음성

1. 입력창 옆 `마이크` 버튼으로 녹음을 시작하고 `정지` 버튼으로 완료한다.
2. `POST /api/sessions/{sessionId}/transcriptions`에 `m4a` 또는 `wav` 파일과 `language=ko`를 전송한다.
3. STT 결과를 입력창에 채운다. 자동 전송하지 않는다.
4. 사용자가 문장을 확인·수정한 뒤 같은 메시지 API로 전송한다.
5. 이때 `inputModality="voice"`만 다르게 저장한다.

`clientMessageId`는 실패 후 재시도 시 같은 사용자 메시지가 중복 저장되지 않도록 유지한다. AI 응답 텍스트는 항상 표시하며, 음성 재생은 Android 로컬 TTS가 담당한다.

## 5. 20초 얼굴 rPPG

### 5.1 진입과 촬영

1. 필수 동의 4종과 `GET /api/rppg/status` 준비 상태를 확인한다.
2. 사용자가 CTA를 눌러 전면 카메라 화면에 진입한다.
3. 한 얼굴이 안내 영역 안에 1초 유지되면 촬영을 시작한다.
4. 20초 동안 촬영하며, 촬영 중 유효 얼굴이 1초 이상 사라지면 취소한다.
5. 클라이언트는 19,500~20,500ms MP4만 업로드한다.

### 5.2 Job 계약

```http
POST /api/rppg/jobs
Content-Type: multipart/form-data

video=<capture.mp4>
clientCaptureId=<uuid>
capturedAtMs=<unix epoch ms>
durationMs=<19500..20500>
sessionId=<optional uuid>
```

성공 시 HTTP 202와 `jobId`, `captureId`, `status`를 받는다. 이후 다음 API를 사용한다.

- `GET /api/rppg/jobs/{jobId}`: 상태와 결과 조회
- `POST /api/rppg/jobs/{jobId}/retry`: 재분석 가능한 실패의 1회 재시도

클라이언트 및 백엔드의 평문 임시 영상은 업로드·수락 처리 후 삭제한다. 서버 보관이 필요한 데이터는 기존 동의와 AES-GCM 암호화 정책을 따른다.

### 5.3 결과 소유권

- 카메라 예측은 `source="camera_rppg"`를 유지한다.
- 카메라 결과는 Phone의 갈망 카드와 허용된 알림에만 반영한다.
- 카메라 결과를 Watch로 전송하지 않는다.
- 이후 Watch 센서 예측이 도착하면 최신 예측 표시가 자연스럽게 교체된다.

## 6. 프론트엔드 API 매핑

| 기능 | 메서드·경로 | 프론트엔드 책임 |
|---|---|---|
| 세션 생성 | `POST /api/sessions` | 세션 유형과 선택적 알림 ID 전송 |
| 세션 조회 | `GET /api/sessions/{id}` | 메시지·상태 복원 |
| 메시지 | `POST /api/sessions/{id}/messages` | `clientMessageId`, `content`, `inputModality` 유지 |
| STT 상태 | `GET /api/stt/status` | 사용 가능 여부와 실제 장치 표시·진입 제어 |
| STT 변환 | `POST /api/sessions/{id}/transcriptions` | 결과를 편집 상태로 제공, 자동 전송 금지 |
| rPPG 상태 | `GET /api/rppg/status` | CTA 준비 상태와 재조회 동작 결정 |
| rPPG 생성 | `POST /api/rppg/jobs` | 20초 MP4와 캡처 메타데이터 전송 |
| rPPG 조회 | `GET /api/rppg/jobs/{jobId}` | 완료·재시도·실패 화면 전환 |
| rPPG 재시도 | `POST /api/rppg/jobs/{jobId}/retry` | `retryAllowed=true`일 때만 노출 |
| 예측 스트림 | `GET /api/predictions/stream` | Watch 최신 예측 반영 |

내부 패키지의 `api/v1`은 코드 구조일 뿐 공개 URL에 `/v1`을 추가하지 않는다.

## 7. 오류 표시 원칙

- `403`: 필요한 동의가 없음을 안내하고 설정으로 이동한다.
- `409`: 세션 상태 또는 중복·재시도 충돌을 안내하고 최신 상태를 다시 조회한다.
- `413`: 오디오·영상 용량 초과를 안내한다.
- `415`: 지원하지 않는 미디어 형식을 안내한다.
- `422`: 음성 없음, 영상 길이·품질·메타데이터 문제에 맞춰 재녹음 또는 재촬영을 제공한다.
- `502/503`: AI·STT·rPPG 서버 문제로 구분하고 사용자 입력을 보존한 채 재시도한다.

## 8. 프론트엔드 수용 기준

- [ ] Watch 노드 0개에서 앱 재시작 없이 `DISCONNECTED`와 주 CTA가 표시된다.
- [ ] Watch 노드 1개 이상에서 모니터링과 보조 얼굴 측정 버튼이 함께 유지된다.
- [ ] `CHECKING`·`ERROR`에서 거짓 미연결 문구가 표시되지 않는다.
- [ ] 동의 누락·서버 미준비에서 측정이 시작되지 않는다.
- [ ] 얼굴 1초 안정화 → 20초 촬영 → HTTP 202 → polling → 결과 카드가 이어진다.
- [ ] `camera_rppg` 결과가 Watch로 전송되지 않는다.
- [ ] 문자와 음성 메시지가 같은 멱등성·중재 에이전트 흐름을 사용한다.
- [ ] Watch 재연결 후 Watch 예측이 다시 최신 결과로 표시된다.

## 9. 현재 검증 상태

| 항목 | 결과 |
|---|---|
| Android 단위 테스트 | 87개 통과 |
| Android lint 및 Phone/Wear OS Debug 빌드 | 통과 |
| 16KB APK·ELF 정렬 | 통과, 64비트 ELF 4개 `p_align=0x4000` |
| 백엔드 테스트 | 159 passed, 1 skipped |
| OpenAPI | 39 paths, 42 operations, 공개 `/v1` 접두사 없음 |
| SM-L320 Wear 설치·실행 | 확인 완료 |
| SM-S926N Watch 미연결→측정→재연결 실기기 흐름 | 미완료, V16 PPT에는 코드 기준 홈 구성과 CTA 정책으로 표시 |
