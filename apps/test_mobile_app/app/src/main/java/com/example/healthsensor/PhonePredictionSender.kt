package com.example.healthsensor

import android.content.Context
import android.util.Log
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.Wearable
import org.json.JSONObject

class PhonePredictionSender(context: Context) {

    private val appContext = context.applicationContext

    fun sendPrediction(
        prediction: CravingPrediction,
        suppressAlertPresentation: Boolean = false
    ): Boolean {
        return runCatching {
            val nodes = Tasks.await(Wearable.getNodeClient(appContext).connectedNodes)
            if (nodes.isEmpty()) return@runCatching false

            val payload = JSONObject().apply {
                buildPayloadFields(prediction, suppressAlertPresentation).forEach { (key, value) ->
                    put(key, value)
                }
            }
                .toString()
                .toByteArray(Charsets.UTF_8)

            nodes.forEach { node ->
                Tasks.await(
                    Wearable.getMessageClient(appContext)
                        .sendMessage(node.id, PREDICTION_PATH, payload)
                )
            }
            true
        }.onFailure {
            Log.e(TAG, "워치 예측 class 전송 실패: ${it.message}")
        }.getOrDefault(false)
    }

    companion object {
        const val PREDICTION_PATH = "/prediction/class"
        private const val TAG = "PhonePredictionSender"

        internal fun buildPayloadFields(
            prediction: CravingPrediction,
            suppressAlertPresentation: Boolean = false
        ): Map<String, Any> = buildMap {
            put("class", prediction.cravingClass)
            put("timestampMs", prediction.timestampMs)
            put("hasAlertMetadata", prediction.alert.isPresent)
            prediction.sessionId?.let { put("sessionId", it) }
            prediction.score?.let { put("score", it.toDouble()) }
            prediction.confidence?.let { put("confidence", it.toDouble()) }
            prediction.sequence?.let { put("sequence", it) }
            if (prediction.alert.isPresent) {
                put("alertLevel", prediction.alert.colorLevel)
                put("alertRequired", prediction.alert.alertRequired)
                prediction.alert.alertAction?.let { put("alertAction", it) }
                prediction.alert.windowMean?.let { put("windowMean", it.toDouble()) }
                prediction.alert.triggerReason?.let { put("triggerReason", it) }
            }
            if (suppressAlertPresentation) {
                put("alertAction", "none")
            }
        }
    }
}
