package com.neurotruth.mobile.service

import com.neurotruth.mobile.core.CravingPrediction
import com.neurotruth.mobile.core.SOURCE_WATCH
import org.json.JSONObject

/**
 * Reads a prediction out of either transport.
 *
 * The same prediction can arrive on the `POST /api/sensor-windows` response and again over the SSE
 * stream, so both go through this one parser and are then deduplicated by id.
 *
 * Only fields the app is allowed to act on are read. `classProbabilities` is deliberately ignored:
 * the four-stage display band comes from `cravingProbability` alone, and the watch is never told
 * either value.
 *
 * Holds no Android types, so the parse is covered by JVM unit tests.
 */
object PredictionPayloadParser {

    fun parse(body: String?): CravingPrediction? {
        if (body.isNullOrBlank()) return null
        return runCatching { parseObject(JSONObject(body)) }.getOrNull()
    }

    private fun parseObject(root: JSONObject): CravingPrediction? {
        // The upload response nests the prediction; the SSE frame may deliver it flat.
        val json = root.optJSONObject("prediction") ?: root
        if (!json.has("cravingProbability")) return null

        val probability = json.optDouble("cravingProbability", Double.NaN)
        if (probability.isNaN() || probability < 0.0 || probability > 1.0) return null

        val alert = json.optJSONObject("alert")
        return CravingPrediction(
            cravingProbability = probability.toFloat(),
            timestampMs = json.optLong("timestampMs", 0L),
            source = json.optString("source", SOURCE_WATCH).ifBlank { SOURCE_WATCH },
            predictionId = json.optString("predictionId").takeIf(String::isNotBlank)
                ?: json.optString("id").takeIf(String::isNotBlank),
            alertId = json.optString("alertId").takeIf(String::isNotBlank)
                ?: alert?.optString("alertId")?.takeIf(String::isNotBlank),
            alertAction = json.optString("alertAction").takeIf(String::isNotBlank)
                ?: alert?.optString("alertAction")?.takeIf(String::isNotBlank),
            sequence = json.optLong("sequence", -1L).takeIf { it >= 0L },
        )
    }

    /**
     * The display-safe subset relayed to the watch on `/prediction/class`.
     *
     * `cravingProbability` and `classProbabilities` are never included, and a camera rPPG result is
     * never relayed at all — the caller filters on source before calling this.
     */
    fun toWatchPayload(prediction: CravingPrediction): String = JSONObject()
        .put("stageCode", prediction.stage.stageCountsKey)
        .put("timestampMs", prediction.timestampMs)
        .put("hasAlertMetadata", prediction.alertAction != null || prediction.alertId != null)
        .apply {
            prediction.alertId?.let { put("alertId", it) }
            prediction.alertAction?.let { put("alertAction", it) }
            prediction.sequence?.let { put("sequence", it) }
        }
        .toString()
}
