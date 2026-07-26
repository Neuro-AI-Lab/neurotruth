package com.neurotruth.mobile.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.os.SystemClock
import android.util.Log
import androidx.core.app.ServiceCompat
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.MessageClient
import com.google.android.gms.wearable.Wearable
import com.neurotruth.mobile.NeuroTruthApp
import com.neurotruth.mobile.core.ConsentGates
import com.neurotruth.mobile.core.CravingPrediction
import com.neurotruth.mobile.core.LatestPredictionPolicy
import com.neurotruth.mobile.core.SOURCE_WATCH
import com.neurotruth.mobile.core.WatchConnectionState
import com.neurotruth.mobile.core.WatchConnectionTracker
import com.neurotruth.mobile.data.DashboardRepository
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
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

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

enum class MeasurementControlStatus {
    STOPPED,
    REQUESTING_START,
    STARTED,
    REQUESTING_STOP,
    CONFIRMATION_REQUIRED,
    ERROR,
}

data class MeasurementControlSnapshot(
    val status: MeasurementControlStatus = MeasurementControlStatus.STOPPED,
    val requestId: String? = null,
    val errorCode: String? = null,
)

/**
 * Process-wide Phone view of the acknowledged Watch measurement state.
 *
 * A connected node is not the same thing as an active sensor stream. The Phone foreground service
 * starts only after [acceptStatus] receives a matching `started` acknowledgement.
 */
object MeasurementControlState {
    private val _snapshot = MutableStateFlow(MeasurementControlSnapshot())
    val snapshot: StateFlow<MeasurementControlSnapshot> = _snapshot.asStateFlow()

    internal fun begin(start: Boolean, requestId: String) {
        _snapshot.value = MeasurementControlSnapshot(
            status = if (start) {
                MeasurementControlStatus.REQUESTING_START
            } else {
                MeasurementControlStatus.REQUESTING_STOP
            },
            requestId = requestId,
        )
    }

    internal fun fail(requestId: String, errorCode: String) {
        if (_snapshot.value.requestId != requestId) return
        _snapshot.value = MeasurementControlSnapshot(
            status = MeasurementControlStatus.ERROR,
            requestId = requestId,
            errorCode = errorCode,
        )
    }

    /** Returns true only for a well-formed status belonging to the current request/run. */
    fun acceptStatus(path: String, data: ByteArray): Boolean {
        if (path != WATCH_CONTROL_STATUS_PATH) return false
        return runCatching {
            val json = JSONObject(String(data, Charsets.UTF_8))
            val requestId = json.getString("requestId")
            val current = _snapshot.value
            // A process with no active request must not accept a delayed/stale "started" message
            // and silently recreate the old auto-start behavior.
            if (current.requestId == null || current.requestId != requestId) return false
            val status = when (json.getString("status")) {
                "started" -> MeasurementControlStatus.STARTED
                "stopped" -> MeasurementControlStatus.STOPPED
                "confirmation_required" -> MeasurementControlStatus.CONFIRMATION_REQUIRED
                "error" -> MeasurementControlStatus.ERROR
                else -> return false
            }
            _snapshot.value = MeasurementControlSnapshot(
                status = status,
                requestId = requestId,
                errorCode = json.optString("errorCode").takeIf(String::isNotBlank),
            )
            true
        }.getOrDefault(false)
    }

    internal fun reset() {
        _snapshot.value = MeasurementControlSnapshot()
    }
}

internal const val WATCH_CONTROL_REQUEST_PATH = "/control/measurement/request"
internal const val WATCH_CONTROL_STATUS_PATH = "/control/measurement/status"
internal const val WATCH_DASHBOARD_SNAPSHOT_PATH = "/dashboard/snapshot"

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
 * [SensorWindowScheduler], [PredictionStreamClient] and the live Watch MessageClient listener.
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
    private lateinit var dashboardRepository: DashboardRepository

    // Written on the main thread, read from the watchdog on Dispatchers.Default — needs a
    // happens-before so the watchdog observes a stop promptly.
    @Volatile
    private var started = false

    @Volatile
    private var shuttingDown = false

    @Volatile
    private var wearListenerRegistered = false

    @Volatile
    private var lastReceiptLogElapsedMs = 0L

    private val wearMessageListener = MessageClient.OnMessageReceivedListener { event ->
        if (MeasurementControlState.acceptStatus(event.path, event.data)) {
            when (MeasurementControlState.snapshot.value.status) {
                MeasurementControlStatus.STOPPED,
                MeasurementControlStatus.ERROR,
                -> shutdown(MonitoringBlocker.NONE)
                else -> Unit
            }
            return@OnMessageReceivedListener
        }
        val receipt = WearSensorMessageHandler.record(
            path = event.path,
            data = event.data,
            nowMs = System.currentTimeMillis(),
        ) ?: return@OnMessageReceivedListener

        // Metadata-only heartbeat: never log physiological values or the wire payload.
        val elapsedMs = SystemClock.elapsedRealtime()
        if (elapsedMs - lastReceiptLogElapsedMs >= RECEIPT_LOG_INTERVAL_MS) {
            lastReceiptLogElapsedMs = elapsedMs
            Log.i(
                TAG,
                "센서 배치 수신 path=${receipt.path} count=${receipt.count} " +
                    "newestTimestampMs=${receipt.newestTimestampMs}",
            )
        }
    }

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
        dashboardRepository = DashboardRepository(app.apiClient, app.endpoints)
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
        shuttingDown = false
        MonitoringState.setBlocker(MonitoringBlocker.NONE)
        MonitoringState.setRunning(true)

        // A new monitoring run starts with a fresh rolling window. Historical Data Layer messages
        // are rejected by WearSensorMessageHandler as a second line of defence.
        SensorSampleRepository.clear()
        registerWearListener()
        scheduler.start()
        streamClient.start()
        serviceScope.launch { watchWatchdog() }
        serviceScope.launch { relayDashboardSnapshots() }
        return true
    }

    private fun registerWearListener() {
        if (wearListenerRegistered) return
        wearListenerRegistered = true
        Wearable.getMessageClient(this)
            .addListener(wearMessageListener)
            .addOnFailureListener { error ->
                wearListenerRegistered = false
                Log.w(TAG, "워치 센서 listener 등록 실패: ${error.message}")
            }
    }

    private fun unregisterWearListener() {
        if (!wearListenerRegistered) return
        wearListenerRegistered = false
        Wearable.getMessageClient(this).removeListener(wearMessageListener)
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

    /**
     * Relays a display-safe recent-hour/today snapshot. Exact probabilities and raw sensor samples
     * stay on Phone; Watch receives only stage codes, timestamps, event count, and AUQ score.
     */
    private suspend fun relayDashboardSnapshots() {
        while (serviceScope.isActive && started) {
            runCatching {
                val timezone = ZoneId.systemDefault().id
                val series = dashboardRepository.recentHourSeries()
                val dashboard = dashboardRepository.cravingDashboard(
                    timezone = timezone,
                    eventRange = DashboardRepository.EVENT_RANGE_7D,
                    auqRange = DashboardRepository.AUQ_RANGE_TODAY,
                )
                val today = LocalDate.now(ZoneId.of(timezone)).toString()
                val eventCount = dashboard.events
                    .firstOrNull { it.localDate == today }
                    ?.totalCount
                    ?: 0
                val weightedAuq = dashboard.auq
                    .filter { it.hasData }
                    .let { buckets ->
                        val samples = buckets.sumOf { it.sampleCount }
                        if (samples == 0) {
                            null
                        } else {
                            buckets.sumOf {
                                (it.averageScore ?: 0f).toDouble() * it.sampleCount
                            }.toFloat() / samples
                        }
                    }
                val payload = JSONObject()
                    .put(
                        "stages",
                        JSONArray().apply {
                            series.points.forEach { point ->
                                put(
                                    JSONObject()
                                        .put("timestampMs", point.atMs)
                                        .put("stageCode", point.stage.stageCountsKey),
                                )
                            }
                        },
                    )
                    .put("todayEventCount", eventCount)
                    .apply {
                        if (weightedAuq != null) put("todayAuqScore", weightedAuq)
                    }
                    .toString()
                    .toByteArray(Charsets.UTF_8)
                sendToWatch(WATCH_DASHBOARD_SNAPSHOT_PATH, payload)
            }.onFailure {
                Log.w(TAG, "워치 대시보드 요약 전달 실패: ${it.message}")
            }
            delay(DASHBOARD_RELAY_INTERVAL_MS)
        }
    }

    private suspend fun sendToWatch(path: String, payload: ByteArray) {
        val nodes = Tasks.await(Wearable.getNodeClient(this).connectedNodes)
        nodes.forEach { node ->
            Tasks.await(Wearable.getMessageClient(this).sendMessage(node.id, path, payload))
        }
    }

    private fun consentGates(): ConsentGates = ConsentGates(app.apiClient.consent())

    private fun shutdown(blocker: MonitoringBlocker) {
        // Reachable concurrently from the watchdog, the scheduler/stream callbacks and onStartCommand;
        // run the teardown once.
        if (shuttingDown) return
        shuttingDown = true
        started = false
        unregisterWearListener()
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
        unregisterWearListener()
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
        private const val RECEIPT_LOG_INTERVAL_MS = 5_000L
        private const val DASHBOARD_RELAY_INTERVAL_MS = 60_000L

        /**
         * Starts only when the Watch is connected and `biosignal` consent is granted. The service
         * re-checks both, so a stale caller cannot keep it alive.
         */
        fun start(context: Context, watchState: WatchConnectionState, gates: ConsentGates) {
            if (watchState != WatchConnectionState.CONNECTED) return
            if (!gates.canUploadBiosignal) return
            try {
                context.startForegroundService(Intent(context, MonitoringService::class.java))
            } catch (error: RuntimeException) {
                // Android 12+ can reject an FGS start if the activity lost foreground status
                // between the lifecycle callback and this call. Surface the pause; never crash.
                Log.w(TAG, "Foreground monitoring start was rejected", error)
                MonitoringState.setRunning(false)
                MonitoringState.setBlocker(MonitoringBlocker.SERVICE_START_FAILED)
            }
        }

        /** Called on logout, on consent withdrawal, and on confirmed disconnection. */
        fun stop(context: Context) {
            // Consent withdrawal and a confirmed watch disconnect can fire while the app is
            // backgrounded, where startService is disallowed on API 26+/31+; never let that crash.
            runCatching {
                context.startService(
                    Intent(context, MonitoringService::class.java).apply { action = ACTION_STOP },
                )
            }
        }

        /** Sends a user-requested start/stop command. It does not start Phone monitoring. */
        suspend fun requestMeasurement(context: Context, start: Boolean): String {
            val requestId = UUID.randomUUID().toString()
            MeasurementControlState.begin(start, requestId)
            val payload = JSONObject()
                .put("action", if (start) "start" else "stop")
                .put("requestId", requestId)
                .toString()
                .toByteArray(Charsets.UTF_8)
            runCatching {
                val nodes = Tasks.await(Wearable.getNodeClient(context).connectedNodes)
                require(nodes.isNotEmpty()) { "watch_disconnected" }
                nodes.forEach { node ->
                    Tasks.await(
                        Wearable.getMessageClient(context)
                            .sendMessage(node.id, WATCH_CONTROL_REQUEST_PATH, payload),
                    )
                }
            }.onFailure {
                MeasurementControlState.fail(requestId, "control_delivery_failed")
            }
            return requestId
        }
    }
}
