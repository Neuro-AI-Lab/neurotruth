package com.example.healthsensor

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat

class CravingAlertNotifier(private val context: Context) {

    private val appContext = context.applicationContext

    fun showClassOneAlert() {
        vibrate(longArrayOf(0, 140))
        notify(
            id = NOTIFICATION_CLASS_ONE,
            title = "잠시 확인이 필요해요",
            body = "평소와 다른 신호일 가능성이 있습니다. 원하면 지금 상태를 함께 확인할 수 있어요.",
            priority = NotificationCompat.PRIORITY_DEFAULT
        )
    }

    fun openStateCheckScreen() {
        startStateCheckActivity()
    }

    fun showClassTwoAlert(launchScreen: Boolean = true) {
        vibrate(longArrayOf(0, 220, 120, 220, 120, 320))
        notify(
            id = NOTIFICATION_CLASS_TWO,
            title = "지금 상태를 확인해 주세요",
            body = "갈망과 관련된 변화일 가능성이 있습니다. 지금 대화하거나 나중에 확인할 수 있어요.",
            priority = NotificationCompat.PRIORITY_MAX,
            fullScreen = true,
            launchScreen = launchScreen
        )
    }

    private fun notify(
        id: Int,
        title: String,
        body: String,
        priority: Int,
        fullScreen: Boolean = false,
        launchScreen: Boolean = fullScreen
    ) {
        ensureChannel()
        if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            appContext.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            if (fullScreen && launchScreen) startStateCheckActivity()
            return
        }

        val intent = Intent(appContext, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            if (fullScreen) {
                putExtra(MainActivity.EXTRA_FORCE_STATE_CHECK_LOCK_SCREEN, true)
            }
        }
        val pendingIntent = PendingIntent.getActivity(
            appContext,
            if (fullScreen) REQUEST_FULL_SCREEN_STATE_CHECK else REQUEST_DEFAULT,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val builder = NotificationCompat.Builder(appContext, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setContentIntent(pendingIntent)
            .setPriority(priority)
            .setCategory(if (fullScreen) NotificationCompat.CATEGORY_ALARM else NotificationCompat.CATEGORY_STATUS)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setAutoCancel(true)

        if (fullScreen) {
            builder
                .setFullScreenIntent(pendingIntent, true)
                .setOngoing(true)
            if (launchScreen) startStateCheckActivity()
        }

        NotificationManagerCompat.from(appContext).notify(id, builder.build())
    }

    private fun startStateCheckActivity() {
        runCatching {
            appContext.startActivity(
                Intent(appContext, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
                    putExtra(MainActivity.EXTRA_FORCE_STATE_CHECK_LOCK_SCREEN, true)
                }
            )
        }
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = appContext.getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "상태 확인 알림",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "갈망 신호 변화와 상태 확인 알림"
                enableVibration(true)
            }
        )
    }

    @Suppress("DEPRECATION")
    private fun vibrate(pattern: LongArray) {
        val vibrator = appContext.getSystemService(Vibrator::class.java) ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator.vibrate(VibrationEffect.createWaveform(pattern, -1))
        } else {
            vibrator.vibrate(pattern, -1)
        }
    }

    companion object {
        private const val CHANNEL_ID = "craving_alerts"
        private const val NOTIFICATION_CLASS_ONE = 1001
        private const val NOTIFICATION_CLASS_TWO = 1002
        private const val REQUEST_DEFAULT = 1
        private const val REQUEST_FULL_SCREEN_STATE_CHECK = 2
    }
}
