package com.example.healthsensor

import android.content.Context
import android.util.Log
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.Wearable
import org.json.JSONObject

class PhonePredictionSender(context: Context) {

    private val appContext = context.applicationContext

    fun sendPrediction(prediction: CravingPrediction): Boolean {
        return runCatching {
            val nodes = Tasks.await(Wearable.getNodeClient(appContext).connectedNodes)
            if (nodes.isEmpty()) return@runCatching false

            val payload = JSONObject()
                .put("class", prediction.cravingClass)
                .put("timestampMs", prediction.timestampMs)
                .apply {
                    prediction.score?.let { put("score", it.toDouble()) }
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
    }
}
