package com.neurotruth.mobile.wear

import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Shared state between [SensorTrackingService] and [MainActivity].
 *
 * The foreground service outlives the activity, so the two communicate through this singleton
 * rather than through a binder.
 *
 * Only display-safe values live here. The phone never relays `cravingProbability` or
 * `classProbabilities` to the watch, so there is deliberately no field for either.
 */
object SensorState {
    const val NO_VALUE: String = "—"

    val isTracking = MutableStateFlow(false)
    val status = MutableStateFlow("연결 중...")

    val hrText = MutableStateFlow(NO_VALUE)
    val ppgText = MutableStateFlow(NO_VALUE)
    val ppgIrText = MutableStateFlow(NO_VALUE)
    val ppgRedText = MutableStateFlow(NO_VALUE)
    val edaText = MutableStateFlow(NO_VALUE)
    val accelXText = MutableStateFlow(NO_VALUE)
    val accelYText = MutableStateFlow(NO_VALUE)
    val accelZText = MutableStateFlow(NO_VALUE)
    val skinTempText = MutableStateFlow(NO_VALUE)

    /** The coarse binary state the phone relays. Never a probability. */
    val cravingClass = MutableStateFlow<Int?>(null)
    val cravingText = MutableStateFlow(NO_VALUE)
    val cravingUpdatedAt = MutableStateFlow(0L)
    val alertLevel = MutableStateFlow("none")
    val alertText = MutableStateFlow("알림 없음")

    fun resetSensorValues() {
        hrText.value = NO_VALUE
        ppgText.value = NO_VALUE
        ppgIrText.value = NO_VALUE
        ppgRedText.value = NO_VALUE
        edaText.value = NO_VALUE
        accelXText.value = NO_VALUE
        accelYText.value = NO_VALUE
        accelZText.value = NO_VALUE
        skinTempText.value = NO_VALUE
    }
}
