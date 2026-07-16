package com.example.healthsensor

import android.Manifest
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
import org.json.JSONObject
import java.util.Locale

/**
 * 폰 앱이 서버에서 받은 갈망 단계를 워치로 전달하면 여기서 수신한다.
 * 실제 UI는 SensorState Flow를 구독하고 있으므로, 값만 갱신하면 워치 화면이 즉시 바뀐다.
 */
class PredictionListenerService : WearableListenerService() {

    override fun onMessageReceived(event: MessageEvent) {
        if (event.path != PREDICTION_PATH) return

        runCatching {
            val json = JSONObject(String(event.data, Charsets.UTF_8))
            val cravingClass = BinaryPredictionContract.requireClass(json.getInt("class"))

            SensorState.cravingClass.value = cravingClass
            SensorState.cravingText.value = when (cravingClass) {
                0 -> "낮음"
                else -> "높음"
            }
            val hasAlertMetadata = json.optBoolean(
                "hasAlertMetadata",
                ALERT_METADATA_KEYS.any(json::has)
            )
            val action = AlertActionPolicy.resolve(
                alertAction = json.optString("alertAction").takeIf(String::isNotBlank),
                alertLevel = json.optString("alertLevel").takeIf(String::isNotBlank),
                hasAlertMetadata = hasAlertMetadata,
                cravingClass = cravingClass
            )
            val alertLevel = json.optString("alertLevel").lowercase(Locale.US)
            SensorState.alertLevel.value = when (alertLevel) {
                "recommend", "recommendation" -> "recommend"
                "required" -> "required"
                else -> if (hasAlertMetadata) {
                    "none"
                } else {
                    when (action) {
                        AlertAction.RECOMMEND -> "recommend"
                        AlertAction.REQUIRED -> "required"
                        AlertAction.NONE, AlertAction.COOLDOWN -> "none"
                    }
                }
            }
            SensorState.alertText.value = when (SensorState.alertLevel.value) {
                "recommend" -> "중재 권장"
                "required" -> "중재 필요"
                else -> "알림 없음"
            }
            SensorState.cravingUpdatedAt.value =
                json.optLong("timestampMs", System.currentTimeMillis())

            when (action) {
                AlertAction.RECOMMEND -> showCravingAlert(
                    action = action,
                    title = "잠시 확인이 필요해요",
                    body = "갈망 신호가 올라왔습니다. 잠시 상태를 확인하세요.",
                    pattern = longArrayOf(0, 140)
                )
                AlertAction.REQUIRED -> showCravingAlert(
                    action = action,
                    title = "지금 상태를 확인해 주세요",
                    body = "폰에서 짧은 상태 확인을 진행하세요.",
                    pattern = longArrayOf(0, 220, 120, 220, 120, 320)
                )
                AlertAction.NONE, AlertAction.COOLDOWN -> Unit
            }
        }.onFailure {
            Log.w(TAG, "예측 단계 파싱 실패: ${it.message}")
        }
    }

    private fun showCravingAlert(
        action: AlertAction,
        title: String,
        body: String,
        pattern: LongArray
    ) {
        val now = System.currentTimeMillis()
        val lastAlertAt = if (action == AlertAction.RECOMMEND) {
            lastRecommendAlertAtMs
        } else {
            lastRequiredAlertAtMs
        }
        if (now - lastAlertAt < ALERT_COOLDOWN_MS) return
        if (action == AlertAction.RECOMMEND) {
            lastRecommendAlertAtMs = now
        } else {
            lastRequiredAlertAtMs = now
        }

        vibrate(pattern)
        showNotification(action, title, body)
    }

    @Suppress("DEPRECATION")
    private fun vibrate(pattern: LongArray) {
        val vibrator = getSystemService(Vibrator::class.java) ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator.vibrate(VibrationEffect.createWaveform(pattern, -1))
        } else {
            vibrator.vibrate(pattern, -1)
        }
    }

    private fun showNotification(action: AlertAction, title: String, body: String) {
        ensureChannel()
        if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }

        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = android.app.Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle(title)
            .setContentText(body)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .build()

        getSystemService(NotificationManager::class.java)
            .notify(if (action == AlertAction.RECOMMEND) NOTIFICATION_RECOMMEND else NOTIFICATION_REQUIRED, notification)
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "상태 확인 알림",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "폰에서 전달한 상태 확인 알림"
                enableVibration(true)
            }
        )
    }

    companion object {
        private const val TAG = "PredictionListener"
        private const val PREDICTION_PATH = "/prediction/class"
        private const val CHANNEL_ID = "watch_craving_alerts"
        private const val NOTIFICATION_RECOMMEND = 2001
        private const val NOTIFICATION_REQUIRED = 2002
        private const val ALERT_COOLDOWN_MS = 30_000L
        private val ALERT_METADATA_KEYS = listOf(
            "alertLevel",
            "alertAction",
            "windowMean",
            "classOneRatio",
            "triggerReason",
            "alertRequired"
        )
        private var lastRecommendAlertAtMs = 0L
        private var lastRequiredAlertAtMs = 0L
    }
}
