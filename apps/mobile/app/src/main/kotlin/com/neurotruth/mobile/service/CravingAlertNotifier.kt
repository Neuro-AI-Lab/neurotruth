package com.neurotruth.mobile.service

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import com.neurotruth.mobile.core.AlertActionPolicy
import com.neurotruth.mobile.core.ConsentGates
import com.neurotruth.mobile.core.CravingPrediction

/**
 * NT-05. Presents the craving alert as a system notification with exactly two actions.
 *
 * The alert is a **conversation offer, not a determination**, so the copy carries no diagnosis, no
 * conclusion and no probability. `지금 대화하기` opens the app to `ensureSession()`;
 * `나중에` dismisses and creates nothing on the server.
 *
 * Four gates must all pass, in this order:
 *
 * 1. [AlertActionPolicy] resolves the server's `alertAction` to RECOMMEND or REQUIRED. Only the
 *    server may raise an alert — a class of 1 on its own never does, because the predictions are
 *    overlapping windows and the backend owns rolling history, downtrend and cooldown.
 * 2. `notification` consent is on. With it off, results are still recorded and no alert is shown.
 * 3. `POST_NOTIFICATIONS` is granted.
 * 4. [PredictionLedger] claims the `alertId`, so the same alert arriving over both the upload
 *    response and SSE notifies exactly once.
 */
class CravingAlertNotifier(
    private val context: Context,
    private val ledger: PredictionLedger,
) {

    // A stable per-alert notification id. String.hashCode() can collide across distinct alertIds,
    // which would share a notification slot and a PendingIntent request code and route a tap into
    // chat with the wrong alert. A monotonic id per distinct alertId can't collide.
    private val notificationIds = java.util.concurrent.ConcurrentHashMap<String, Int>()

    private fun notificationIdFor(alertId: String): Int =
        notificationIds.getOrPut(alertId) { ID_SEQUENCE.incrementAndGet() }

    /** Returns true when a notification was actually posted. */
    fun present(prediction: CravingPrediction, gates: ConsentGates): Boolean {
        if (!AlertActionPolicy.recommendsConversation(prediction.alertAction)) return false
        if (!gates.canNotify) return false
        if (!hasNotificationPermission()) return false
        val alertId = prediction.alertId ?: return false
        if (!ledger.claimAlert(alertId)) return false

        ensureChannel()
        val notificationId = notificationIdFor(alertId)
        context.getSystemService(NotificationManager::class.java)
            .notify(notificationId, build(alertId, notificationId))
        return true
    }

    fun hasNotificationPermission(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED

    private fun build(alertId: String, notificationId: Int): Notification {
        val talkIntent = context.packageManager
            .getLaunchIntentForPackage(context.packageName)
            ?.apply {
                action = ACTION_TALK_NOW
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                putExtra(EXTRA_ALERT_ID, alertId)
            }
        val talkPendingIntent = talkIntent?.let {
            PendingIntent.getActivity(
                context,
                notificationId,
                it,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        }

        val laterPendingIntent = PendingIntent.getBroadcast(
            context,
            notificationId,
            Intent(context, CravingAlertDismissReceiver::class.java)
                .setPackage(context.packageName)
                .putExtra(EXTRA_ALERT_ID, alertId)
                .putExtra(EXTRA_NOTIFICATION_ID, notificationId),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        return Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(TITLE)
            .setContentText(BODY)
            .setStyle(Notification.BigTextStyle().bigText(BODY))
            .setAutoCancel(true)
            .apply {
                talkPendingIntent?.let {
                    setContentIntent(it)
                    addAction(
                        Notification.Action.Builder(null, ACTION_LABEL_TALK_NOW, it).build(),
                    )
                }
                addAction(
                    Notification.Action.Builder(
                        null,
                        ACTION_LABEL_LATER,
                        laterPendingIntent,
                    ).build(),
                )
            }
            .build()
    }

    private fun ensureChannel() {
        val manager = context.getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "대화 제안",
                NotificationManager.IMPORTANCE_HIGH,
            ).apply { description = "지금 이야기를 나눠볼지 묻는 알림" },
        )
    }

    companion object {
        const val CHANNEL_ID = "neurotruth_craving_alerts"

        const val ACTION_TALK_NOW = "com.neurotruth.mobile.action.TALK_NOW"
        const val EXTRA_ALERT_ID = "com.neurotruth.mobile.extra.ALERT_ID"
        const val EXTRA_NOTIFICATION_ID = "com.neurotruth.mobile.extra.NOTIFICATION_ID"

        /** Base for per-alert notification ids, above the watch's 2001/2002 range. */
        private val ID_SEQUENCE = java.util.concurrent.atomic.AtomicInteger(4000)

        const val ACTION_LABEL_TALK_NOW = "지금 대화하기"
        const val ACTION_LABEL_LATER = "나중에"

        /** Offers a conversation. States nothing about the person's condition. */
        const val TITLE = "지금 이야기해 볼까요?"
        const val BODY = "갈망 가능성이 높아진 것으로 보여요. 지금 상황을 편하게 이야기해 볼까요?"
    }
}

/**
 * `나중에`. Dismisses the notification and returns the user to whatever they were doing.
 *
 * It deliberately does nothing else — no session is created on the server, which is exactly what
 * NT-05 requires of this action.
 */
class CravingAlertDismissReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val alertId = intent.getStringExtra(CravingAlertNotifier.EXTRA_ALERT_ID) ?: return
        // Cancel the exact id the notification was posted with, carried through the intent, since it
        // is no longer derivable from alertId.hashCode().
        val notificationId =
            intent.getIntExtra(CravingAlertNotifier.EXTRA_NOTIFICATION_ID, alertId.hashCode())
        context.getSystemService(NotificationManager::class.java).cancel(notificationId)
    }
}
