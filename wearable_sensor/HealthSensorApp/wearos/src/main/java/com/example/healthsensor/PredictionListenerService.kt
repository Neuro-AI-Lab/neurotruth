package com.example.healthsensor

import android.util.Log
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.WearableListenerService
import org.json.JSONObject

/**
 * 폰 앱이 서버에서 받은 갈망 class를 워치로 전달하면 여기서 수신한다.
 * 실제 UI는 SensorState Flow를 구독하고 있으므로, 값만 갱신하면 워치 화면이 즉시 바뀐다.
 */
class PredictionListenerService : WearableListenerService() {

    override fun onMessageReceived(event: MessageEvent) {
        if (event.path != PREDICTION_PATH) return

        runCatching {
            val json = JSONObject(String(event.data, Charsets.UTF_8))
            val cravingClass = json.getInt("class")
            require(cravingClass in 0..2) { "class must be 0, 1, or 2" }

            SensorState.cravingClass.value = cravingClass
            SensorState.cravingText.value = when (cravingClass) {
                0 -> "0 낮음"
                1 -> "1 중간"
                else -> "2 높음"
            }
            SensorState.cravingUpdatedAt.value =
                json.optLong("timestampMs", System.currentTimeMillis())
        }.onFailure {
            Log.w(TAG, "예측 class 파싱 실패: ${it.message}")
        }
    }

    companion object {
        private const val TAG = "PredictionListener"
        private const val PREDICTION_PATH = "/prediction/class"
    }
}
