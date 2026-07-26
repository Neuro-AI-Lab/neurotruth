package com.neurotruth.mobile.wear

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.util.Log
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.WearableListenerService
import com.neurotruth.mobile.core.AlertAction
import com.neurotruth.mobile.core.AlertActionPolicy
import org.json.JSONObject

/**
 * Data Layer boundary for display-safe prediction, control, and dashboard messages.
 *
 * Watch never receives a probability, raw biosignal history, backend credential, or enough data to
 * infer an alert. A notification is raised only for a server-issued alert ID and action.
 */
class PredictionListenerService : WearableListenerService() {

    override fun onMessageReceived(event: MessageEvent) {
        when (event.path) {
            PREDICTION_PATH -> handlePrediction(event.data)
            CONTROL_REQUEST_PATH -> handleControl(event.data)
            DASHBOARD_SNAPSHOT_PATH -> handleDashboard(event.data)
        }
    }

    private fun handlePrediction(data: ByteArray) {
        runCatching {
            val json = JSONObject(String(data, Charsets.UTF_8))
            val stageCode = json.getString("stageCode")
            require(stageCode in STAGE_CODES)
            SensorState.stageCode.value = stageCode
            SensorState.cravingText.value = stageLabel(stageCode)
            SensorState.cravingUpdatedAt.value =
                json.optLong("timestampMs", System.currentTimeMillis())

            val action = AlertActionPolicy.resolve(
                json.optString("alertAction").takeIf(String::isNotBlank),
            )
            val alertId = json.optString("alertId").takeIf(String::isNotBlank)
            SensorState.alertLevel.value = when (action) {
                AlertAction.RECOMMEND -> "recommend"
                AlertAction.REQUIRED -> "required"
                AlertAction.NONE, AlertAction.COOLDOWN -> "none"
            }
            SensorState.alertText.value = if (action.suppressesUserFacingActions) {
                "알림 없음"
            } else {
                "대화 권장"
            }
            if (alertId != null && !action.suppressesUserFacingActions && claimAlert(alertId)) {
                vibrate(longArrayOf(0, 220, 120, 220))
                showCravingNotification(alertId)
            }
        }.onFailure { Log.w(TAG, "예측 상태 파싱 실패: ${it.message}") }
    }

    private fun handleControl(data: ByteArray) {
        runCatching {
            val request = parseMeasurementRequest(data) ?: error("invalid_control_request")
            val requestId = request.requestId
            when (request.action) {
                "stop" -> {
                    SensorState.activeRequestId.value = requestId
                    SensorTrackingService.stop(this, requestId)
                }
                "start" -> {
                    SensorState.activeRequestId.value = requestId
                    if (!hasRequiredSensorPermissions()) {
                        requestConfirmation(requestId, "permission_required")
                        return
                    }
                    try {
                        SensorTrackingService.start(this, requestId)
                    } catch (error: RuntimeException) {
                        Log.w(TAG, "원격 측정 시작 제한: ${error.message}")
                        requestConfirmation(requestId, "foreground_start_restricted")
                    }
                }
                else -> sendControlStatus(requestId, "error", "invalid_action")
            }
        }.onFailure {
            Log.w(TAG, "측정 제어 파싱 실패: ${it.message}")
        }
    }

    private fun handleDashboard(data: ByteArray) {
        runCatching {
            val root = JSONObject(String(data, Charsets.UTF_8))
            val stages = root.optJSONArray("stages")
            SensorState.stageTimeline.value = buildList {
                if (stages != null) {
                    for (index in 0 until stages.length()) {
                        val point = stages.getJSONObject(index)
                        val code = point.getString("stageCode")
                        if (code in STAGE_CODES) {
                            add(StageSample(point.getLong("timestampMs"), code))
                        }
                    }
                }
            }
            SensorState.todayEventCount.value =
                root.optInt("todayEventCount").takeIf { root.has("todayEventCount") }
            SensorState.todayAuqScore.value =
                root.optDouble("todayAuqScore").takeIf { root.has("todayAuqScore") }?.toFloat()
        }.onFailure { Log.w(TAG, "대시보드 요약 파싱 실패: ${it.message}") }
    }

    private fun hasRequiredSensorPermissions(): Boolean =
        checkSelfPermission(Manifest.permission.BODY_SENSORS) == PackageManager.PERMISSION_GRANTED &&
            (
                Build.VERSION.SDK_INT !in Build.VERSION_CODES.TIRAMISU..35 ||
                    checkSelfPermission(Manifest.permission.BODY_SENSORS_BACKGROUND) ==
                    PackageManager.PERMISSION_GRANTED
                ) &&
            checkSelfPermission(READ_ADDITIONAL_HEALTH_DATA) == PackageManager.PERMISSION_GRANTED

    private fun requestConfirmation(requestId: String, errorCode: String) {
        sendControlStatus(requestId, "confirmation_required", errorCode)
        ensureControlChannel()
        if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) return
        val intent = Intent(this, MainActivity::class.java)
            .putExtra(MainActivity.EXTRA_CONFIRM_START, true)
            .putExtra(MainActivity.EXTRA_REQUEST_ID, requestId)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val pending = PendingIntent.getActivity(
            this,
            requestId.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        getSystemService(NotificationManager::class.java).notify(
            CONTROL_NOTIFICATION_ID,
            Notification.Builder(this, CONTROL_CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_media_play)
                .setContentTitle("측정을 시작할까요?")
                .setContentText("Watch에서 한 번 확인하면 측정을 시작합니다.")
                .setContentIntent(pending)
                .setAutoCancel(true)
                .build(),
        )
    }

    private fun sendControlStatus(requestId: String, status: String, errorCode: String? = null) {
        SensorTrackingService.sendControlStatus(this, requestId, status, errorCode)
    }

    private fun claimAlert(alertId: String): Boolean {
        val preferences = getSharedPreferences(ALERT_PREFS, MODE_PRIVATE)
        val claimed = preferences.getString(ALERT_IDS_KEY, "")
            .orEmpty()
            .split(',')
            .filter(String::isNotBlank)
            .toMutableList()
        if (alertId in claimed) return false
        claimed += alertId
        while (claimed.size > MAX_ALERT_IDS) claimed.removeAt(0)
        preferences.edit().putString(ALERT_IDS_KEY, claimed.joinToString(",")).apply()
        return true
    }

    private fun showCravingNotification(alertId: String) {
        ensureCravingChannel()
        if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) return
        val pending = PendingIntent.getActivity(
            this,
            alertId.hashCode(),
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        getSystemService(NotificationManager::class.java).notify(
            alertId.hashCode(),
            Notification.Builder(this, CRAVING_CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle("갈망이 높게 감지됐어요")
                .setContentText("휴대폰에서 챗봇과 대화를 시작할 수 있어요.")
                .setContentIntent(pending)
                .setAutoCancel(true)
                .build(),
        )
    }

    @Suppress("DEPRECATION")
    private fun vibrate(pattern: LongArray) {
        getSystemService(Vibrator::class.java)
            ?.vibrate(VibrationEffect.createWaveform(pattern, -1))
    }

    private fun ensureControlChannel() = ensureChannel(
        CONTROL_CHANNEL_ID,
        "측정 확인",
        NotificationManager.IMPORTANCE_HIGH,
    )

    private fun ensureCravingChannel() = ensureChannel(
        CRAVING_CHANNEL_ID,
        "갈망 알림",
        NotificationManager.IMPORTANCE_HIGH,
    )

    private fun ensureChannel(id: String, name: String, importance: Int) {
        val manager = getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(id) == null) {
            manager.createNotificationChannel(NotificationChannel(id, name, importance))
        }
    }

    private companion object {
        const val TAG = "PredictionListener"
        const val PREDICTION_PATH = "/prediction/class"
        const val CONTROL_REQUEST_PATH = "/control/measurement/request"
        const val DASHBOARD_SNAPSHOT_PATH = "/dashboard/snapshot"
        const val READ_ADDITIONAL_HEALTH_DATA =
            "com.samsung.android.hardware.sensormanager.permission.READ_ADDITIONAL_HEALTH_DATA"
        const val CONTROL_CHANNEL_ID = "watch_measurement_control"
        const val CRAVING_CHANNEL_ID = "watch_craving_alerts"
        const val CONTROL_NOTIFICATION_ID = 2100
        const val ALERT_PREFS = "watch_alert_dedup"
        const val ALERT_IDS_KEY = "claimed_alert_ids"
        const val MAX_ALERT_IDS = 200
        val STAGE_CODES = setOf("low", "observe", "caution", "high")

        fun stageLabel(code: String): String = when (code) {
            "low" -> "안정"
            "observe" -> "관찰"
            "caution" -> "주의"
            "high" -> "위험"
            else -> SensorState.NO_VALUE
        }
    }
}

internal data class MeasurementRequest(val action: String, val requestId: String)

internal fun parseMeasurementRequest(data: ByteArray): MeasurementRequest? = runCatching {
    val json = JSONObject(String(data, Charsets.UTF_8))
    measurementRequest(json.getString("action"), json.getString("requestId"))
}.getOrNull()

internal fun measurementRequest(action: String, requestId: String): MeasurementRequest? =
    if (action in setOf("start", "stop") && requestId.isNotBlank()) {
        MeasurementRequest(action, requestId)
    } else {
        null
    }

internal fun shouldPresentServerAlert(
    alertId: String?,
    alertAction: String?,
    alreadyClaimed: Boolean,
): Boolean =
    !alertId.isNullOrBlank() &&
        !AlertActionPolicy.resolve(alertAction).suppressesUserFacingActions &&
        !alreadyClaimed
