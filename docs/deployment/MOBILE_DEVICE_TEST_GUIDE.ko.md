# NeuroTruth 모바일 — 실기기(폰 + Galaxy Watch) 연동·테스트 가이드

최종 업데이트: 2026-07-25
대상: `apps/mobile` (신규 환자 Android 앱 + Wear OS relay)

이 문서는 에뮬레이터로는 검증할 수 없는 기능 — **Watch 생체신호 수집·업로드, 예측 SSE·갈망 알림, 카메라 rPPG** — 을 실제 기기에서 세팅하고 테스트하는 절차입니다. 에뮬레이터에서 확인 가능한 흐름(온보딩·챗봇·대시보드·설정)도 함께 체크리스트로 정리합니다.

> 관련 문서: 화면·계약은 [`docs/prd/PRD_neurotruth_mobile.ko.md`], 통신 계약은 [`apps/test_mobile_app/SERVER_API_SPEC.md`], 백엔드 배포는 [`docs/deployment/DGX_SPARK_DEPLOYMENT.ko.md`].

---

## 1. 준비물

### 하드웨어

| 항목 | 요구 | 비고 |
|---|---|---|
| Android 폰 | Android 8.0(API 26) 이상 | 앱 `minSdk 26`. FGS·rPPG 검증은 Android 14(API 34)+ 권장 |
| Galaxy Watch | Wear OS 3+ (Watch4 이상), Samsung Health Sensor 지원 | PPG/GSR/가속도/피부온도 트래커 필요 |
| 카메라 | 폰 전면 카메라 | rPPG 20초 촬영 |
| USB 케이블 / 동일 Wi‑Fi | adb 및 Data Layer 연결 | 폰·워치가 같은 삼성 계정으로 페어링돼야 함 |

### 소프트웨어

| 항목 | 버전 | 비고 |
|---|---|---|
| Android Studio | Koala(2024.1.1) 이상 | AGP 8.5.2 요구 |
| JDK | 17 또는 21 | Gradle JDK로 지정 |
| Gradle | 8.7 (wrapper 고정) | — |
| Android SDK Platform | **35** (compileSdk/targetSdk 35) | 첫 빌드 시 AGP가 자동 다운로드 |
| Wear OS 앱 | Galaxy Wearable + Samsung Health | 페어링·트래커 권한용 |

### 백엔드

- 기본 주소: `http://223.194.33.26:58441` (`apps/mobile/app/src/main/assets/server_config.properties`)
- `GET /health`, `GET /ready`, `GET /model/status`가 `ready:true`여야 예측이 동작합니다.
- ⚠️ **현재 평문 HTTP입니다.** 실제 환자 데이터 테스트라면 백엔드를 HTTPS로 전환하고 `network_security_config.xml`의 도메인 예외를 제거하세요(아래 §10, C2).

---

## 2. 빌드 & 설치

프로젝트 루트는 `apps/mobile` 입니다(여기에 `settings.gradle`). Android Studio에서 **`apps/mobile`** 을 여세요(상위 `neurotruth`가 아님).

```bash
cd apps/mobile

# 폰 앱
./gradlew :app:assembleDebug
adb -s <PHONE_SERIAL> install -r app/build/outputs/apk/debug/app-debug.apk

# 워치 앱 (워치를 adb로 연결한 뒤)
./gradlew :wearos:assembleDebug
adb -s <WATCH_SERIAL> install -r wearos/build/outputs/apk/debug/wearos-debug.apk
```

- 폰·워치 `applicationId`가 **`com.neurotruth.mobile`로 동일**해야 Data Layer가 두 앱을 짝지어 줍니다.
- Samsung Health Sensor API는 Maven 좌표가 없어 `wearos/libs/samsung-health-sensor-api-1.4.1.aar` 파일로 포함됩니다. 이 파일이 있어야 워치 빌드가 됩니다.
- 여러 기기가 붙어 있으면 `adb devices`로 시리얼을 확인하고 `-s`로 지정하세요.

### 워치를 adb로 연결하기

1. 워치: 설정 → 정보 → 소프트웨어 → 빌드번호 7회 탭 → 개발자 옵션 활성화
2. 개발자 옵션 → **ADB 디버깅**, **무선 디버깅** 켜기
3. `adb connect <WATCH_IP>:<PORT>` (무선 디버깅 화면의 IP·포트)

---

## 3. 페어링 & Samsung Health Sensor 개발자 모드 (필수)

워치 센서 API는 **Samsung Health 개발자 모드**가 켜져 있어야 트래커를 시작할 수 있습니다. 꺼져 있으면 `HealthTrackerException`(SDK_POLICY_ERROR) → 앱 상태가 "연결 오류"로 표시됩니다.

1. 폰·워치를 **같은 삼성 계정**으로 Galaxy Wearable에서 페어링.
2. 워치 Samsung Health 앱을 최소 1회 실행(초기화).
3. **Samsung Health 개발자 모드 활성화** — 워치 Samsung Health → 설정 진입점에서 개발자 모드 ON (기기·버전에 따라 진입 방식이 다르며, 삼성 Health Sensor SDK 문서의 "developer mode" 절차를 따르세요).
4. 설치 직후 **워치 앱을 한 번 직접 실행**하고, 누락된 신체 센서·백그라운드 센서·활동 인식·알림 권한을 모두 허용(§4). 이 준비가 되어야 Phone `[측정 시작]`과 원격 시작 제한 시 한 번 탭하는 확인 알림이 동작합니다.

---

## 4. 권한 부여

### 워치 (`:wearos`)

매니페스트가 요구하는 런타임 권한을 워치에서 허용해야 트래커가 시작됩니다.

| 권한 | 용도 |
|---|---|
| `BODY_SENSORS` | PPG/GSR/피부온도 |
| `BODY_SENSORS_BACKGROUND` | 화면 꺼짐 상태 수집 (API 33+) |
| `ACTIVITY_RECOGNITION` | 가속도 |
| `POST_NOTIFICATIONS` | 갈망 알림(워치 진동/알림) |
| `com.samsung.android.hardware.sensormanager.permission.READ_ADDITIONAL_HEALTH_DATA` | 삼성 추가 건강 데이터 |

### 폰 (`:app`)

동의(앱 내 NT-03)와 OS 권한은 별개입니다. OS 권한은 **기능을 처음 열 때** 또는 **설정 화면의 "허용" 버튼**으로 요청합니다.

| 권한 | 트리거 | 검증 지점 |
|---|---|---|
| 알림(POST_NOTIFICATIONS) | 알림 동의 ON / 설정 | 갈망 알림 표시 |
| 카메라 | rPPG 촬영 진입 / 설정 | NT-04R |
| 마이크(RECORD_AUDIO) | STT 마이크 / 설정 | NT-07 STT |
| `HIGH_SAMPLING_RATE_SENSORS` | 설치 시 자동(프롬프트 없음) | 포그라운드 서비스 시작 |

> 폰 앱은 로컬 센서를 직접 읽지 않습니다(데이터는 워치→Data Layer 경유). `HIGH_SAMPLING_RATE_SENSORS`는 Android 14+에서 `health` 포그라운드 서비스 타입을 허용하기 위한 설치형 권한입니다.

---

## 5. 데이터 흐름 (무엇을 관찰하는가)

```
Galaxy Watch (SensorTrackingService, FGS)
  ↑ Phone /control/measurement/request (start/stop + requestId)
  └─ Watch /control/measurement/status (started/stopped/confirmation_required/error)
  └─ Samsung HealthTracker → 채널 버퍼 → PhoneDataSender
        └─ Wear Data Layer (배치 바이너리)  paths: /sensor/{hr,ppg,ppg_ir,ppg_red,eda,accel_x,accel_y,accel_z,skin_temp}
             └─ 폰 MonitoringService (MessageClient + WearSensorMessageHandler) → SensorSampleRepository
                  └─ SensorWindowScheduler  (20초 warm-up → 10초마다 최신 20초 window)
                       └─ POST /api/sensor-windows  (PPG 25Hz×500, EDA 1Hz×20)
                            └─ 예측 응답 + 알림 메타
  폰 PredictionStreamClient  ←  GET /api/predictions/stream (SSE)
       └─ 중복 제거 → 홈/대시보드 갱신 + 갈망 알림
  폰 → 워치  /prediction/class + /dashboard/snapshot
               (단계·시각·요약·alertId만; 확률·원시 신호·credential 미전송)
```

핵심 계약(검증 기준):
- 센서 window: `windowMs=20000`(허용 19500~20500), 첫 전송 전 **20초 warm-up**, 이후 **10초 cadence**, PPG 채널별 500개(25Hz), EDA 20개(1Hz).
- 같은 `(session, sequence)`는 같은 `clientWindowId`로 재전송(byte-identical) → 서버 idempotent.
- rPPG: MP4, `durationMs` 19500~20500, 최대 40MiB, HTTP 202 후 로컬 파일 삭제.

---

## 6. 테스트 체크리스트

### A. 하드웨어 불필요 (에뮬레이터/폰만)

| # | 시나리오 | 기대 |
|---|---|---|
| A1 | 최초 실행 → 제품 안내(NT-01) | 뒤로가기로 못 건너뜀, "확인하고 계속" → 가입 |
| A2 | 가입(NT-02) → 동의(NT-03) → 홈 | 가입은 동의 저장 시 `POST /api/auth/patient/signup` 1회, 비밀번호는 메모리에만 |
| A3 | 로그인 → 홈 | `POST /api/auth/login` 후 `GET /api/me`로 최신 동의 확인 |
| A4 | 챗봇(NT-07) 진입 | 새 세션이면 자기설문(NT-06) → 결과 → 대화, 활성 세션이면 바로 재개(중복 자기설문 없음) |
| A5 | 메시지 전송 → AI 응답 | 502면 말풍선 유지 + "응답 다시 받기"로 **1회** 재시도 |
| A6 | 자기설문 8문항 / 건너뛰기 | 저장 성공 시 `총점 X/48` 결과 후 대화, 건너뛰기는 바로 대화 |
| A7 | STT 마이크 | transcript가 기존 draft를 보존한 채 즉시 voice 메시지로 전송되고 해당 AI 답변만 자동 TTS |
| A8 | 대시보드(NT-08) | 1시간 4단계 timeline, 일·월 선택, 데이터 없음과 "유효한 0건" 구분 |
| A9 | 설정(NT-09) 권한 "허용" | OS 권한 다이얼로그가 뜨고 상태가 "허용됨"으로 갱신 |
| A10 | 로그아웃 | SSE·업로드·녹음·TTS·polling 정리, 다른 계정 로그인 시 이전 캐시 없음 |

### B. Galaxy Watch 필요

| # | 시나리오 | 기대 | 확인 방법 |
|---|---|---|---|
| B1 | 폰 홈 `[측정 시작]` | Watch `started` ack 뒤 Phone MonitoringService와 센서 수집 시작 | 폰 홈 / 워치 화면 / logcat |
| B2 | 20초 warm-up 후 첫 업로드 | 첫 `POST /api/sensor-windows` ~20초 후, 이후 10초마다 | `adb logcat` / 서버 로그 |
| B3 | 갈망 가능성 갱신 | 홈·Watch가 `안정·관찰·주의·위험`으로 갱신, 위험 문구는 `갈망이 높게 감지됐어요. 챗봇과 대화를 시작할까요?` | 양쪽 화면 |
| B4 | 예측 SSE | `GET /api/predictions/stream` 유지, 예측 반영 | logcat `PredictionStream` |
| B5 | Watch 위험 3회(각 간격 ≤20초) | Phone·Watch에 같은 `alertId` 알림 1회, 이후 15분 추가 알림 없음 | 양쪽 알림창 / 서버 DB |
| B6 | "지금 대화하기" | 챗봇으로 진입, 세션이 `alert_checkin`으로 생성 | 폰 / 서버 |
| B7 | "나중에" | 세션 생성 안 됨, 현재 화면 유지 | 폰 |
| B8 | 워치 연결 중 카메라 버튼 | **비활성** + "Watch 연결을 해제해야…" 안내 | 폰 홈 |
| B9 | 워치 연결 끊기(앱 종료) | 확립된 연결이 끊기면 폰 측정 배너, 워치 FGS·wake lock 정리 | 폰 홈 / 워치 |
| B10 | 재시도 idempotency | 네트워크 순단 후 같은 window가 같은 `clientWindowId`로 재전송 | 서버(409 없이 1회 저장) |
| B11 | 원격 시작 제한 fallback | Watch가 `confirmation_required`를 보내고 Watch 알림 1회 확인 후 `started` | 양쪽 상태 / 워치 알림 |
| B12 | 폰·Watch에서 각각 중지 | 어느 쪽에서 중지해도 Watch FGS와 Phone 업로드가 종료되고 `stopped` 표시 | 양쪽 화면 / 로그 |
| B13 | Watch 3개 요약 화면 | 측정·현재 단계 / 최근 1시간 timeline / 오늘 이벤트·AUQ를 스와이프 확인 | 워치 화면 |

### C. 카메라 rPPG 필요 (Watch 미연결 상태)

| # | 시나리오 | 기대 |
|---|---|---|
| C1 | Watch 미연결 + 동의 4종 + 서버 ready | 홈 "카메라로 측정" 활성 |
| C2 | 촬영 진입 시 카메라 권한 | 권한 요청 → 허용 시 미리보기 |
| C3 | 얼굴 1초 안정화 → 20초 촬영 | 자동 시작, 1초 이상 이탈 시 취소, 깜빡임은 유지 |
| C4 | 업로드·분석 | 202 후 로컬 MP4 삭제, polling(2초, 3분 상한) |
| C5 | 완료 → 챗봇 | job당 1회만 세션 생성·이동 |
| C6 | 앱 강제종료 후 재실행 | job 중복 생성 없이 기존 결과 이어받음 |
| C7 | 품질 미달(`retry_required`) | 같은 job 재시도가 아니라 **새 촬영** 유도 |
| C8 | rPPG 위험 결과 | 상태·DB·챗봇 흐름에는 남지만 Phone/Watch 갈망 알림은 생성되지 않음 |

---

## 7. 로그·상태 확인

```bash
# 폰: 크래시/센서/스트림
adb -s <PHONE> logcat -c && adb -s <PHONE> logcat | grep -iE "neurotruth|FATAL|SensorWindow|PredictionStream|Rppg"

# 워치: 트래커/연결
adb -s <WATCH> logcat | grep -iE "SensorTracking|HealthSensor|PhoneDataSender|PredictionListener"

# 런타임 권한 상태(폰)
adb -s <PHONE> shell dumpsys package com.neurotruth.mobile | grep -A6 "runtime permissions"

# 백엔드 준비 상태
curl -s http://223.194.33.26:58441/ready
curl -s http://223.194.33.26:58441/model/status
```

---

## 8. 인수 기준(요약)

PRD 인수 시나리오와 매핑됩니다. 아래가 모두 통과하면 P0/P1이 실기기에서 동작하는 것으로 봅니다.

- [ ] 신규 사용자 안내→가입→동의→홈 (A1–A3)
- [ ] Phone 시작 → Watch ack·센서 → Phone 자동 업로드(20초/10초) → 서버 예측 (B1–B3)
- [ ] 위험 3회에서 Phone·Watch 동시 알림, 같은 ID 중복 제거와 15분 cooldown (B5)
- [ ] 알림에서 자기설문 작성·결과/건너뛰기 후 같은 대화 진입 (B5–B6, A4–A6)
- [ ] STT 즉시 전송과 해당 음성 답변 1회 자동 TTS (A5, A7)
- [ ] AI 실패 시 중복 없이 1회 재시도 (A5)
- [ ] 얼굴 1초 안정화 후 20초 촬영, 완료 시 챗봇 이동 (C3–C5)
- [ ] 앱 재시작 후 대화·rPPG job 중복 생성 없이 복구 (C6)
- [ ] 대시보드에서 데이터 없음과 유효한 0건 구분 (A8)
- [ ] 로그아웃 시 SSE·센서·녹음·TTS·polling·캐시 정리 (A10)
- [ ] Watch 연결 중 카메라 비활성, 확정 미연결 시 활성 (B8, C1)
- [ ] 원격 시작 확인 fallback과 Phone·Watch 양쪽 중지 (B11–B12)
- [ ] rPPG 결과가 갈망 알림을 만들지 않음 (C8)

---

## 9. 트러블슈팅

| 증상 | 원인 | 조치 |
|---|---|---|
| 워치 앱 "연결 오류", 트래커 미시작 | Samsung Health 개발자 모드 OFF (SDK_POLICY_ERROR) | §3 개발자 모드 활성화 |
| 워치 데이터가 폰에 안 옴 | 페어링 불일치 / applicationId 상이 / 폰 앱 미실행 | 같은 계정 페어링, 두 앱 모두 `com.neurotruth.mobile`, 폰 앱 포그라운드 |
| 폰 측정이 시작 안 됨(FGS) | `HIGH_SAMPLING_RATE_SENSORS` 누락 또는 알림 권한 회수 | 매니페스트 권한 확인, 알림 권한 허용 |
| 카메라 버튼이 계속 비활성 | Watch 연결됨 / 동의 미충족 / 서버 미ready / 권한 미허용 | 홈 안내 문구대로 조건 충족 |
| 통신은 되는데 예측이 없음 | `aiAnalysis` 동의 OFF 또는 모델 미ready | 동의 확인, `GET /model/status` |
| HTTP 차단/평문 오류 | 백엔드 호스트가 `network_security_config` 예외와 불일치 | `server_config.properties`와 XML 호스트 일치 |

---

## 10. 실기기에서 반드시 확인/결정해야 할 항목

코드 리뷰로는 검증 못 하고 **실기기·인프라 결정이 필요한** 잔여 항목입니다.

| ID | 항목 | 내용 |
|---|---|---|
| **C2 (보안)** | 백엔드 HTTPS | 현재 평문 HTTP로 토큰·PHI 전송. 클라이언트는 호스트 한정 완화만 적용됨. **출시 전 백엔드 TLS 필수**, 이후 `network_security_config` 도메인 예외 제거 |
| **wearos A1** | Samsung SDK 스레딩 | flush 바인더 호출은 IO로 옮겼으나, SDK setup/callback 스레딩 전면 이동은 실기기 검증 후 결정(ANR 여부 관찰) |
| **O-005** | 백그라운드 SSE 신뢰성 | doze·화면 꺼짐 장시간에서 예측 수신 유지 여부 측정 |
| **O-006** | 배터리 | 상시 Watch 수집 + SSE 유지 시 시간당 소모 8시간 실사용 측정 |
| **O-010** | AGP vs compileSdk | AGP 8.5.2 + compileSdk 35 경고. 출시 전 AGP 8.6+로 올리거나 compileSdk 34로 조정 |
| **sessionStartedAtMs** | 계약 확인 | 센서 업로드의 `sessionStartedAtMs`가 모니터링 세션 UUID임. 서버가 대화 `sessionId`를 기대하는지 `/openapi.json`으로 확인 |
| **16KB 정렬** | 릴리스 게이트 | 릴리스 빌드에서 APK zip·ELF 정렬 검증 |

---

## 부록 · 빠른 시작 요약

```bash
# 0) 백엔드 살아있는지
curl -s http://223.194.33.26:58441/ready         # {"ready":true,...}

# 1) 폰 앱
cd apps/mobile
./gradlew :app:assembleDebug
adb -s <PHONE> install -r app/build/outputs/apk/debug/app-debug.apk

# 2) 워치 앱 (워치 adb 연결 후)
./gradlew :wearos:assembleDebug
adb -s <WATCH> install -r wearos/build/outputs/apk/debug/wearos-debug.apk

# 3) 워치: Samsung Health 개발자 모드 ON → 워치 앱 1회 직접 실행
#          → 신체센서·백그라운드 센서·활동 인식·알림 권한 허용
# 4) 폰: 가입/로그인 → 동의(생체신호·AI분석·알림 ON) → 홈 Watch `[측정 시작]`
# 5) Watch started ack → 20초 후 첫 업로드 → 이후 10초마다 갈망 갱신 (§6-B, §7 로그)
```
