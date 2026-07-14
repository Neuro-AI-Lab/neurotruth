/**
 * SensorState.kt — SensorTrackingService ↔ MainActivity 공유 상태
 *
 * Foreground Service는 Activity와 다른 생명주기를 가지므로,
 * StateFlow 기반 싱글톤으로 상태를 공유한다.
 */
package com.example.healthsensor

import kotlinx.coroutines.flow.MutableStateFlow

object SensorState {
    val isTracking    = MutableStateFlow(false)
    val status        = MutableStateFlow("연결 중...")

    // 워치 화면 표시용 최신값 (센서별 마지막 수신값)
    val hrText        = MutableStateFlow("—")
    val ppgText       = MutableStateFlow("—")
    val ppgIrText     = MutableStateFlow("—")
    val ppgRedText    = MutableStateFlow("—")
    val edaText       = MutableStateFlow("—")
    val accelXText    = MutableStateFlow("—")
    val accelYText    = MutableStateFlow("—")
    val accelZText    = MutableStateFlow("—")
    val skinTempText  = MutableStateFlow("—")

    val cravingClass  = MutableStateFlow<Int?>(null)
    val cravingText   = MutableStateFlow("CRAVE —")
    val cravingUpdatedAt = MutableStateFlow(0L)
    val alertLevel    = MutableStateFlow("none")
    val alertText     = MutableStateFlow("알림 없음")

    val ecgText       = MutableStateFlow("—")
    val spo2Text      = MutableStateFlow("—")   // "XX%" 형식
    val biaText       = MutableStateFlow("—")   // "지방 XX.X% / BMI XX.X" 형식
    val sweatLossText = MutableStateFlow("—")   // "XXX ml" 형식
}
