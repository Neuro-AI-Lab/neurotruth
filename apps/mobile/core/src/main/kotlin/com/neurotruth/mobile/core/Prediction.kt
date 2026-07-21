package com.neurotruth.mobile.core

const val PREDICTION_SCHEMA: String = "binary-craving-v1"
const val SOURCE_WATCH: String = "watch_sensor"
const val SOURCE_CAMERA: String = "camera_rppg"

data class CravingPrediction(
    val cravingProbability: Float,
    val timestampMs: Long,
    val source: String,
    val predictionId: String? = null,
    val alertId: String? = null,
    val alertAction: String? = null,
    val sequence: Long? = null,
) {
    val stage: CravingStage
        get() = CravingStage.of(cravingProbability)
}

/**
 * Decides whether an arriving prediction becomes the Home latest state.
 *
 * A delayed older camera result must not displace a newer Watch result. On an exact timestamp tie
 * the Watch wins, because it is the continuous source.
 *
 * Camera results are kept in dashboard history regardless of what this returns.
 */
object LatestPredictionPolicy {
    fun shouldReplace(current: CravingPrediction?, incoming: CravingPrediction): Boolean {
        if (current == null) return true
        if (incoming.timestampMs > current.timestampMs) return true
        if (incoming.timestampMs < current.timestampMs) return false
        return incoming.source == SOURCE_WATCH && current.source != SOURCE_WATCH
    }
}

/** How the measurement time and device are labelled on the Home craving card. */
object MeasurementOriginLabel {
    const val LIVE_WATCH: String = "실시간 · Watch"

    fun deviceLabel(source: String): String =
        if (source == SOURCE_CAMERA) "카메라" else "Watch"

    fun stored(formattedTimestamp: String, source: String): String =
        "$formattedTimestamp · ${deviceLabel(source)}"
}
