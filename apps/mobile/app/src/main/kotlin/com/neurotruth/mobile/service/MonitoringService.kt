package com.neurotruth.mobile.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.util.Log
import androidx.core.app.ServiceCompat
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.Wearable
import com.neurotruth.mobile.NeuroTruthApp
import com.neurotruth.mobile.core.ConsentGates
import com.neurotruth.mobile.core.CravingPrediction
import com.neurotruth.mobile.core.LatestPredictionPolicy
import com.neurotruth.mobile.core.SOURCE_WATCH
import com.neurotruth.mobile.core.WatchConnectionState
import com.neurotruth.mobile.core.WatchConnectionTracker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** Why measurement is not running, for the Home banner. Never a craving value. */
enum class MonitoringBlocker {
    NONE,
    NOTIFICATION_PERMISSION_REVOKED,
    WATCH_DISCONNECTED,
    CONSENT_WITHDRAWN,
    UPLOAD_PAUSED,
    AUTHENTICATION_REQUIRED,

    /** startForeground was refused by the OS (e.g. a missing FGS-type prerequisite permission). */
    SERVICE_START_FAILED,
}

/**
 * What [MonitoringService] publishes to the UI.
 *
 * A singleton because the service outlives any screen, and Home has to be able to render the
 * paused-measurement banner even when nothing is bound.
 */
object MonitoringState {
    private val _running = MutableStateFlow(false)
    val running: StateFlow<Boolean> = _running.asStateFlow()

    private val _watchState = MutableStateFlow(WatchConnectionState.CHECKING)
    val watchState: StateFlow<WatchConnectionState> = _watchState.asStateFlow()

    private val _blocker = MutableStateFlow(MonitoringBlocker.NONE)
    val blocker: StateFlow<MonitoringBlocker> = _blocker.asStateFlow()

    private val _latestPrediction = MutableStateFlow<CravingPrediction?>(null)
    val latestPrediction: StateFlow<CravingPrediction?> = _latestPrediction.asStateFlow()

    /** Set when the bounded retry queue evicted windows. */
    private val _droppedNotice = MutableStateFlow<String?>(null)
    val droppedNotice: StateFlow<String?> = _droppedNotice.asStateFlow()

    internal fun setRunning(value: Boolean) {
        _running.value = value
    }

    internal fun setWatchState(value: WatchConnectionState) {
        _watchState.value = value
    }

    internal fun setBlocker(value: MonitoringBlocker) {
        _blocker.value = value
    }

    internal fun setDroppedNotice(value: String?) {
        _droppedNotice.value = value
    }

    /** A late-arriving older result never displaces a newer one; on a tie the Watch wins. */
    internal fun offerPrediction(prediction: CravingPrediction) {
        if (LatestPredictionPolicy.shouldReplace(_latestPrediction.value, prediction)) {
            _latestPrediction.value = prediction
        }
    }

    fun clear() {
        _running.value = false
        _watchState.value = WatchConnectionState.CHECKING
        _blocker.value = MonitoringBlocker.NONE
        _latestPrediction.value = null
        _droppedNotice.value = null
    }
}

/**
 * The foreground service of PRD §2.4.
 *
 * The 10-second upload cadence and the SSE connection are P0 requirements that must survive the
 * screen going off, so they live here rather than in a ViewModel scope. The service owns
 * [SensorWindowScheduler] and [PredictionStreamClient] and nothing else does.
 *
 * | Item | Value |
 * |---|---|
 * | FGS type | `health` |
 * | Channel | Low importance, silent; the text says only that measurement is running |
 * | Start | Watch connected **and** `biosignal` consent granted |
 * | Stop | Watch disconnected, consent withdrawn, or logout |
 *
 * The notification text never carries a craving value — it is visible on the lock screen of a shared
 * device.
 *
 * If `POST_NOTIFICATIONS` is revoked the service cannot show its notification and therefore cannot
 * run at all. That is published as [MonitoringBlocker.NOTIFICATION_PERMISSION_REVOKED] for the Home
 * banner rather than failing silently.
 */
class MonitoringService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val watchTracker = WatchConnectionTracker()
    private val ledger = PredictionLedger()

    private lateinit var app: NeuroTruthApp
    private lateinit var notifier: CravingAlertNotifier
    private lateinit var scheduler: SensorWindowScheduler
    private lateinit var streamClient: PredictionStreamClient

    private var started = false

    override fun onCreate() {
        super.onCreate()
        app = NeuroTruthApp.from(this)
        notifier = CravingAlertNotifier(this, ledger)

        scheduler = SensorWindowScheduler(
            apiClient = app.apiClient,
            endpoints = app.endpoints,
            windowIdStore = PersistentClientWindowIdStore(this),
            listener = schedulerListener,
            scope = serviceScope,
        )
        streamClient = PredictionStreamClient(
            apiClient = app.apiClient,
            endpoints = app.endpoints,
            ledger = ledger,
            listener = streamListener,
            scope = serviceScope,
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int =
        when (intent?.action) {
            ACTION_STOP -> {
                shutdown(MonitoringBlocker.NONE)
                START_NOT_STICKY
            }

            else -> {
                if (startMonitoring()) START_STICKY else START_NOT_STICKY
            }
        }

    private fun startMonitoring(): Boolean {
        if (started) return true

        if (!notifier.hasNotificationPermission()) {
            // Without the permission the mandatory foreground notification cannot be posted, so the
            // service is not allowed to exist. Surface it; never fail silently.
            MonitoringState.setBlocker(MonitoringBlocker.NOTIFICATION_PERMISSION_REVOKED)
            MonitoringState.setRunning(false)
            stopSelf()
            return false
        }

        if (!consentGates().canUploadBiosignal) {
            shutdown(MonitoringBlocker.CONSENT_WITHDRAWN)
            return false
        }

        ensureChannel()
        // Never let a refused startForeground crash the app: on Android 14+ the health FGS type
        // requires a qualifying permission (declared in the manifest), and a background-start can be
        // disallowed. Surface a blocker and stop instead of throwing.
        try {
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                buildNotification(),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_HEALTH,
            )
        } catch (error: Exception) {
            MonitoringState.setBlocker(MonitoringBlocker.SERVICE_START_FAILED)
            MonitoringState.setRunning(false)
            stopSelf()
            return false
        }

        started = true
        MonitoringState.setBlocker(MonitoringBlocker.NONE)
        MonitoringState.setRunning(true)

        scheduler.start()
        streamClient.start()
        serviceScope.launch { watchWatchdog() }
        return true
    }

    /**
     * Connection is decided only by the connected-node list: an empty list twice in a row at least
     * three seconds apart is a confirmed disconnection, and a failed query is ERROR rather than
     * disconnected. [WatchConnectionTracker] owns that rule; this loop only supplies observations.
     */
    private suspend fun watchWatchdog() {
        while (serviceScope.isActive && started) {
            val state = runCatching {
                Tasks.await(Wearable.getNodeClient(this).connectedNodes).size
            }.fold(
                onSuccess = { count -> watchTracker.onNodesQueried(System.currentTimeMillis(), count) },
                onFailure = { watchTracker.onQueryFailed() },
            )
            MonitoringState.setWatchState(state)

            if (state.isConfirmedDisconnected) {
                shutdown(MonitoringBlocker.WATCH_DISCONNECTED)
                return
            }
            if (!consentGates().canUploadBiosignal) {
                shutdown(MonitoringBlocker.CONSENT_WITHDRAWN)
                return
            }
            delay(WATCH_POLL_INTERVAL_MS)
        }
    }

    private val schedulerListener = object : SensorWindowScheduler.Listener {
        override fun onWindowUploaded(responseBody: String) {
            val prediction = PredictionPayloadParser.parse(responseBody) ?: return
            if (!ledger.claimPrediction(prediction)) return
            apply(prediction)
        }

        override fun onWindowsDropped(notice: String) {
            MonitoringState.setDroppedNotice(notice)
        }

        override fun onAuthenticationRequired() {
            shutdown(MonitoringBlocker.AUTHENTICATION_REQUIRED)
        }

        override fun onUploadPaused(statusCode: Int) {
            Log.w(TAG, "센서 업로드 일시 중지 (HTTP $statusCode)")
            MonitoringState.setBlocker(MonitoringBlocker.UPLOAD_PAUSED)
        }
    }

    private val streamListener = object : PredictionStreamClient.Listener {
        override fun onPrediction(prediction: CravingPrediction) = apply(prediction)

        override fun onAuthenticationRequired() {
            shutdown(MonitoringBlocker.AUTHENTICATION_REQUIRED)
        }
    }

    private fun apply(prediction: CravingPrediction) {
        val gates = consentGates()
        if (!gates.canReceiveAiPrediction) return

        MonitoringState.offerPrediction(prediction)
        notifier.present(prediction, gates)
        relayToWatch(prediction)
    }

    /**
     * Relays only the coarse state. `cravingProbability` and `classProbabilities` are never sent,
     * and a camera rPPG result is never relayed at all.
     */
    private fun relayToWatch(prediction: CravingPrediction) {
        if (prediction.source != SOURCE_WATCH) return
        val payload = PredictionPayloadParser.toWatchPayload(prediction).toByteArray(Charsets.UTF_8)
        serviceScope.launch(Dispatchers.IO) {
            runCatching {
                val nodes = Tasks.await(Wearable.getNodeClient(this@MonitoringService).connectedNodes)
                for (node in nodes) {
                    Tasks.await(
                        Wearable.getMessageClient(this@MonitoringService)
                            .sendMessage(node.id, WATCH_PREDICTION_PATH, payload),
                    )
                }
            }.onFailure { Log.w(TAG, "워치 상태 전달 실패: ${it.message}") }
        }
    }

    private fun consentGates(): ConsentGates = ConsentGates(app.apiClient.consent())

    private fun shutdown(blocker: MonitoringBlocker) {
        started = false
        scheduler.stop()
        streamClient.stop()
        MonitoringState.setRunning(false)
        MonitoringState.setBlocker(blocker)
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        super.onDestroy()
        started = false
        scheduler.stop()
        streamClient.stop()
        MonitoringState.setRunning(false)
        serviceScope.cancel()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun ensureChannel() {
        val manager = getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                CHANNEL_NAME,
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "측정이 진행 중임을 알리는 조용한 알림"
                setSound(null, null)
                enableVibration(false)
                setShowBadge(false)
            },
        )
    }

    /** Says only that measurement is running. It must never carry a craving value. */
    private fun buildNotification(): Notification {
        val contentIntent = packageManager.getLaunchIntentForPackage(packageName)?.let {
            android.app.PendingIntent.getActivity(
                this,
                0,
                it,
                android.app.PendingIntent.FLAG_UPDATE_CURRENT or
                    android.app.PendingIntent.FLAG_IMMUTABLE,
            )
        }
        // Silence comes from the channel (IMPORTANCE_LOW, no sound, no vibration) rather than from
        // the builder, so it survives on every supported API level.
        val builder = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setContentTitle(NOTIFICATION_TITLE)
            .setContentText(NOTIFICATION_TEXT)
            .setOngoing(true)
        contentIntent?.let(builder::setContentIntent)
        return builder.build()
    }

    companion object {
        const val ACTION_STOP = "com.neurotruth.mobile.action.STOP_MONITORING"

        private const val TAG = "MonitoringService"
        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "neurotruth_monitoring"
        private const val CHANNEL_NAME = "측정 진행"
        private const val NOTIFICATION_TITLE = "측정 중"
        private const val NOTIFICATION_TEXT = "생체 신호를 측정하고 있어요"
        private const val WATCH_PREDICTION_PATH = "/prediction/class"
        private const val WATCH_POLL_INTERVAL_MS = 3_000L

        /**
         * Starts only when the Watch is connected and `biosignal` consent is granted. The service
         * re-checks both, so a stale caller cannot keep it alive.
         */
        fun start(context: Context, watchState: WatchConnectionState, gates: ConsentGates) {
            if (watchState != WatchConnectionState.CONNECTED) return
            if (!gates.canUploadBiosignal) return
            context.startForegroundService(Intent(context, MonitoringService::class.java))
        }

        /** Called on logout, on consent withdrawal, and on confirmed disconnection. */
        fun stop(context: Context) {
            context.startService(
                Intent(context, MonitoringService::class.java).apply { action = ACTION_STOP },
            )
        }
    }
}
