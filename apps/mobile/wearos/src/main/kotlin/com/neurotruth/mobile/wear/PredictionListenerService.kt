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
 * Receives the coarse craving state the phone relays on `/prediction/class`.
 *
 * The payload carries `class`, `timestampMs` and `hasAlertMetadata` always, plus `sessionId`,
 * `confidence`, `sequence` and the alert fields when available. `cravingProbability` and
 * `classProbabilities` are deliberately absent — the watch shows a coarse state, not a probability —
 * and camera rPPG results are never relayed at all.
 *
 * Presentation is decided by [AlertActionPolicy] from `:core`, the same class the phone uses. Only
 * the server's `alertAction` may raise an alert: a class of 1 on its own must never vibrate, because
 * the binary predictions are overlapping windows and the backend owns rolling history, downtrend and
 * cooldown.
 */
class PredictionListenerService : WearableListenerService() {

    override fun onMessageReceived(event: MessageEvent) {
        if (event.path != PREDICTION_PATH) return

        runCatching {
            val json = JSONObject(String(event.data, Charsets.UTF_8))
            val cravingClass = json.getInt("class")
            require(cravingClass == 0 || cravingClass == 1) {
                "binary prediction class must be 0 or 1 but was $cravingClass"
            }

            SensorState.cravingClass.value = cravingClass
            SensorState.cravingText.value = if (cravingClass == 0) "낮음" else "높음"
            SensorState.cravingUpdatedAt.value =
                json.optLong("timestampMs", System.currentTimeMillis())

            val action = AlertActionPolicy.resolve(
                json.optString("alertAction").takeIf(String::isNotBlank),
            )
            SensorState.alertLevel.value = when (action) {
                AlertAction.RECOMMEND -> "recommend"
                AlertAction.REQUIRED -> "required"
                AlertAction.NONE, AlertAction.COOLDOWN -> "none"
            }
            SensorState.alertText.value = when (action) {
                AlertAction.RECOMMEND -> "대화 권장"
                AlertAction.REQUIRED -> "지금 확인"
                AlertAction.NONE, AlertAction.COOLDOWN -> "알림 없음"
            }

            when (action) {
                AlertAction.RECOMMEND -> raise(
                    action = action,
                    title = "잠시 확인해 볼까요?",
                    body = "지금 상황을 편하게 이야기해 볼 수 있어요.",
                    pattern = longArrayOf(0, 140),
                )

                AlertAction.REQUIRED -> raise(
                    action = action,
                    title = "지금 이야기해 볼까요?",
                    body = "폰에서 대화를 이어갈 수 있어요.",
                    pattern = longArrayOf(0, 220, 120, 220, 120, 320),
                )

                AlertAction.NONE, AlertAction.COOLDOWN -> Unit
            }
        }.onFailure {
            Log.w(TAG, "예측 상태 파싱 실패: ${it.message}")
        }
    }

    private fun raise(action: AlertAction, title: String, body: String, pattern: LongArray) {
        val now = System.currentTimeMillis()
        val lastAlertAtMs =
            if (action == AlertAction.RECOMMEND) lastRecommendAtMs else lastRequiredAtMs
        if (now - lastAlertAtMs < ALERT_COOLDOWN_MS) return
        if (action == AlertAction.RECOMMEND) lastRecommendAtMs = now else lastRequiredAtMs = now

        vibrate(pattern)
        showNotification(action, title, body)
    }

    @Suppress("DEPRECATION")
    private fun vibrate(pattern: LongArray) {
        val vibrator = getSystemService(Vibrator::class.java) ?: return
        vibrator.vibrate(VibrationEffect.createWaveform(pattern, -1))
    }

    private fun showNotification(action: AlertAction, title: String, body: String) {
        ensureChannel()
        if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
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
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .setContentText(body)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .build()

        getSystemService(NotificationManager::class.java).notify(
            if (action == AlertAction.RECOMMEND) NOTIFICATION_RECOMMEND else NOTIFICATION_REQUIRED,
            notification,
        )
    }

    private fun ensureChannel() {
        val manager = getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "대화 제안", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "폰에서 전달한 대화 제안"
                enableVibration(true)
            },
        )
    }

    private companion object {
        const val TAG = "PredictionListener"
        const val PREDICTION_PATH = "/prediction/class"
        const val CHANNEL_ID = "watch_craving_alerts"
        const val NOTIFICATION_RECOMMEND = 2001
        const val NOTIFICATION_REQUIRED = 2002
        const val ALERT_COOLDOWN_MS = 30_000L

        @Volatile
        var lastRecommendAtMs = 0L

        @Volatile
        var lastRequiredAtMs = 0L
    }
}
