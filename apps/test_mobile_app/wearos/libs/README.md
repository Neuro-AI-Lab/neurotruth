# Samsung Health Sensor SDK AAR

최종 업데이트: 2026-07-10

이 폴더에는 Samsung Health Sensor SDK AAR 파일을 둡니다.

현재 NeuroTruth private repo 운용에서는 필요한 경우 AAR/JAR 파일을 함께 추적할 수 있습니다. Public repository나 외부 배포로 전환할 때는 Samsung Developer 약관을 다시 확인하세요.

```text
apps/mobile/wearos/libs/samsung-health-sensor-api-1.4.1.aar
```

`wearos/build.gradle`은 이 폴더의 `*.aar`, `*.jar` 파일을 로컬 dependency로 읽습니다.

최신 로컬 검증에서는 `samsung-health-sensor-api-1.4.1.aar`가 이 위치에 있고 `apps/mobile/gradlew.bat assembleDebug testDebugUnitTest lintDebug`가 성공했습니다. Lint 오류는 0개이며 최신 APK 설치/실행은 `SM-L320`에서 성공했습니다. 실제 센서 장시간 측정은 별도 확인이 필요합니다.

