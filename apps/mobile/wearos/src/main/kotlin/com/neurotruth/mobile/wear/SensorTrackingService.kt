package com.neurotruth.mobile.wear

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import java.util.UUID

/**
 * Foreground service that keeps sensor collection alive with the screen off.
 *
 * Flow: `startForeground` + a partial wake lock → connect `HealthTrackingService` → start the
 * trackers on CONNECTED → run the flush loop.
 *
 * The flush loop is the reason this is a service and not a coroutine in a ViewModel: it waits
 * [FLUSH_INTERVAL_MS], drains the SDK batch buffer, waits [CALLBACK_GRACE_MS] for the resulting
 * callbacks, then ships one batch per non-empty channel. Without the flush the SDK holds PPG and
 * accelerometer for ~12 seconds, which would make the phone's 20-second window stale. A two-second
 * cadence keeps each 10-second inference window current without overwhelming the Data Layer with
 * dozens of tiny messages per second.
 *
 * The watch buffers only within a flush cycle. If the phone app is not running the samples are
 * dropped rather than queued — this is a research prototype and gapped data beats stale data
 * presented as current.
 */
class SensorTrackingService : Service() {

    private lateinit var sensorManager: HealthSensorManager
    private lateinit var phoneSender: PhoneDataSender

    // SupervisorJob keeps one failing collector from tearing down the others.
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var wakeLock: PowerManager.WakeLock? = null
    private var trackingStarted = false
    private var stopping = false
    private var currentRequestId: String? = null

    // Dispatchers.Main is single-threaded here, so these need no further synchronization.
    private val hrBuf = mutableListOf<Pair<Long, Float>>()
    private val ppgBuf = mutableListOf<Pair<Long, Float>>()
    private val ppgIrBuf = mutableListOf<Pair<Long, Float>>()
    private val ppgRedBuf = mutableListOf<Pair<Long, Float>>()
    private val edaBuf = mutableListOf<Pair<Long, Float>>()
    private val accelXBuf = mutableListOf<Pair<Long, Float>>()
    private val accelYBuf = mutableListOf<Pair<Long, Float>>()
    private val accelZBuf = mutableListOf<Pair<Long, Float>>()
    private val skinTempBuf = mutableListOf<Pair<Long, Float>>()

    override fun onCreate() {
        super.onCreate()
        sensorManager = HealthSensorManager(this)
        phoneSender = PhoneDataSender(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int =
        when (intent?.action) {
            ACTION_START -> {
                currentRequestId = intent.getStringExtra(EXTRA_REQUEST_ID)
                    ?: SensorState.activeRequestId.value
                    ?: UUID.randomUUID().toString()
                SensorState.activeRequestId.value = currentRequestId
                startTracking()
                // REDELIVER, not STICKY: after a low-memory kill the last start intent is
                // redelivered so tracking resumes, instead of a null intent hitting the else branch
                // below and immediately stopping the service.
                START_REDELIVER_INTENT
            }

            ACTION_STOP -> {
                currentRequestId = intent.getStringExtra(EXTRA_REQUEST_ID)
                    ?: currentRequestId
                    ?: SensorState.activeRequestId.value
                stopAndClean()
                START_NOT_STICKY
            }

            else -> {
                stopSelf()
                START_NOT_STICKY
            }
        }

    private fun startTracking() {
        // Always call startForeground before the guard: startForegroundService arms the ~5s
        // "must call startForeground" watchdog on every ACTION_START, so a second start while already
        // tracking would otherwise skip it and crash with ForegroundServiceDidNotStartInTime.
        startForeground(NOTIFICATION_ID, buildNotification())
        if (trackingStarted) {
            // Phone may have restarted and lost its in-memory acknowledgement state while this
            // foreground service kept collecting. A new explicit start request must receive its
            // own ack without reconnecting trackers or creating a second collection loop.
            currentRequestId?.let { sendControlStatus(this, it, "started") }
            return
        }
        trackingStarted = true

        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_LOCK_TAG)
            .apply { acquire(WAKE_LOCK_TIMEOUT_MS) }

        SensorState.isTracking.value = true
        SensorState.status.value = "연결 중..."
        currentRequestId?.let { sendControlStatus(this, it, "started") }

        serviceScope.launch {
            // Tear down after an *established* connection drops, so the wake lock and foreground
            // service never keep running idle with no data flow. The initial DISCONNECTED (before
            // the first connect) must not trigger this, hence the wasConnected latch.
            var wasConnected = false
            sensorManager.connectionState.collect { state ->
                SensorState.status.value = when (state) {
                    HealthSensorManager.ConnectionState.CONNECTING -> "연결 중..."
                    HealthSensorManager.ConnectionState.CONNECTED -> {
                        wasConnected = true
                        sensorManager.startTracking()
                        "측정 중"
                    }

                    HealthSensorManager.ConnectionState.DISCONNECTED -> "연결 끊김"
                    HealthSensorManager.ConnectionState.ERROR -> "연결 오류"
                }
                val lost = state == HealthSensorManager.ConnectionState.DISCONNECTED ||
                    state == HealthSensorManager.ConnectionState.ERROR
                if (wasConnected && lost) {
                    wasConnected = false
                    stopAndClean(
                        terminalStatus = "error",
                        terminalErrorCode = "sensor_disconnected",
                    )
                }
            }
        }

        serviceScope.launch {
            sensorManager.hrFlow.collect { data ->
                hrBuf.add(data.timestamp to data.bpm.toFloat())
                SensorState.hrText.value = "${data.bpm} bpm"
            }
        }
        serviceScope.launch {
            sensorManager.ppgFlow.collect { data ->
                ppgBuf.add(data.timestamp to data.green.toFloat())
            }
        }
        serviceScope.launch {
            sensorManager.ppgIrFlow.collect { data ->
                ppgIrBuf.add(data.timestamp to data.ir.toFloat())
            }
        }
        serviceScope.launch {
            sensorManager.ppgRedFlow.collect { data ->
                ppgRedBuf.add(data.timestamp to data.red.toFloat())
            }
        }
        serviceScope.launch {
            sensorManager.edaFlow.collect { data ->
                edaBuf.add(data.timestamp to data.skinConductance)
                SensorState.edaText.value = "%.3f μS".format(data.skinConductance)
            }
        }
        serviceScope.launch {
            sensorManager.accelFlow.collect { data ->
                // Each axis is its own Data Layer channel.
                accelXBuf.add(data.timestamp to data.x)
                accelYBuf.add(data.timestamp to data.y)
                accelZBuf.add(data.timestamp to data.z)
            }
        }
        serviceScope.launch {
            sensorManager.skinTempFlow.collect { data ->
                skinTempBuf.add(data.timestamp to data.objectTemp)
                SensorState.skinTempText.value = "%.1f °C".format(data.objectTemp)
            }
        }

        serviceScope.launch {
            while (isActive) {
                delay(FLUSH_INTERVAL_MS)
                // The tracker flush() calls are Samsung binder IPC; run them off the collection
                // thread so the flush cadence never blocks it. The buffers are not touched here —
                // flushChannel below reads them on the confined thread — so no race is introduced.
                withContext(Dispatchers.IO) { sensorManager.flushAllTrackers() }
                delay(CALLBACK_GRACE_MS)
                flushChannel(WatchSensorPaths.HR, hrBuf)
                flushChannel(WatchSensorPaths.PPG, ppgBuf)
                flushChannel(WatchSensorPaths.PPG_IR, ppgIrBuf)
                flushChannel(WatchSensorPaths.PPG_RED, ppgRedBuf)
                flushChannel(WatchSensorPaths.EDA, edaBuf)
                flushChannel(WatchSensorPaths.ACCEL_X, accelXBuf)
                flushChannel(WatchSensorPaths.ACCEL_Y, accelYBuf)
                flushChannel(WatchSensorPaths.ACCEL_Z, accelZBuf)
                flushChannel(WatchSensorPaths.SKIN_TEMP, skinTempBuf)
            }
        }

        phoneSender.findPhoneNode()
        sensorManager.connect()
    }

    private fun flushChannel(path: String, buffer: MutableList<Pair<Long, Float>>) {
        if (buffer.isEmpty()) return
        val batch = buffer.toList()
        buffer.clear()
        phoneSender.sendBatch(path, batch)
    }

    private fun stopAndClean(
        terminalStatus: String = "stopped",
        terminalErrorCode: String? = null,
    ) {
        if (stopping) return
        stopping = true
        trackingStarted = false
        if (wakeLock?.isHeld == true) wakeLock?.release()
        wakeLock = null
        runCatching { sensorManager.stopTracking() }
        runCatching { sensorManager.disconnect() }
        SensorState.isTracking.value = false
        SensorState.status.value = "중지됨"
        SensorState.resetSensorValues()
        serviceScope.coroutineContext.cancelChildren()

        val requestId = currentRequestId
        if (requestId == null) {
            finishStop()
            return
        }

        // Keep the service alive until exactly one terminal acknowledgement is handed to Google
        // Play services. An explicit stop reports "stopped"; a sensor disconnect reports "error"
        // and must not be overwritten by a later stop acknowledgement.
        serviceScope.launch(Dispatchers.IO) {
            runCatching {
                sendControlStatusAndWait(
                    context = this@SensorTrackingService,
                    requestId = requestId,
                    status = terminalStatus,
                    errorCode = terminalErrorCode,
                )
            }.onFailure {
                Log.w(TAG, "측정 종료 상태 전달 실패: ${it.message}")
            }
            withContext(Dispatchers.Main) { finishStop() }
        }
    }

    private fun finishStop() {
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        super.onDestroy()
        runCatching {
            if (wakeLock?.isHeld == true) wakeLock?.release()
            sensorManager.stopTracking()
            sensorManager.disconnect()
            phoneSender.close()
        }
        SensorState.isTracking.value = false
        serviceScope.cancel()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun buildNotification(): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL_ID) == null) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "센서 측정", NotificationManager.IMPORTANCE_LOW),
            )
        }
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("측정 중")
            .setContentText("생체 신호를 수집하고 있어요")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val TAG = "SensorTrackingService"
        const val ACTION_START = "com.neurotruth.mobile.wear.action.START"
        const val ACTION_STOP = "com.neurotruth.mobile.wear.action.STOP"
        const val EXTRA_REQUEST_ID = "measurementRequestId"

        private const val NOTIFICATION_ID = 1
        private const val CHANNEL_ID = "sensor_ch"
        private const val WAKE_LOCK_TAG = "NeuroTruthWear:tracking"
        private const val WAKE_LOCK_TIMEOUT_MS = 60L * 60L * 1000L

        /** Two seconds total per cycle: flush, then let the callbacks land before shipping. */
        private const val FLUSH_INTERVAL_MS = 1_800L
        private const val CALLBACK_GRACE_MS = 200L

        fun start(context: Context, requestId: String) {
            context.startForegroundService(
                Intent(context, SensorTrackingService::class.java).apply {
                    action = ACTION_START
                    putExtra(EXTRA_REQUEST_ID, requestId)
                },
            )
        }

        fun stop(context: Context, requestId: String? = SensorState.activeRequestId.value) {
            context.startService(
                Intent(context, SensorTrackingService::class.java).apply {
                    action = ACTION_STOP
                    requestId?.let { putExtra(EXTRA_REQUEST_ID, it) }
                },
            )
        }

        fun sendControlStatus(
            context: Context,
            requestId: String,
            status: String,
            errorCode: String? = null,
        ) {
            val payload = controlStatusPayload(requestId, status, errorCode)
            Wearable.getNodeClient(context).connectedNodes
                .addOnSuccessListener { nodes ->
                    nodes.forEach { node ->
                        Wearable.getMessageClient(context)
                            .sendMessage(node.id, CONTROL_STATUS_PATH, payload)
                    }
                }
                .addOnFailureListener {
                    Log.w("SensorTrackingService", "측정 상태 전달 실패: ${it.message}")
                }
        }

        private fun sendControlStatusAndWait(
            context: Context,
            requestId: String,
            status: String,
            errorCode: String? = null,
        ) {
            val payload = controlStatusPayload(requestId, status, errorCode)
            val nodes = Tasks.await(
                Wearable.getNodeClient(context).connectedNodes,
                CONTROL_ACK_TIMEOUT_SECONDS,
                TimeUnit.SECONDS,
            )
            nodes.forEach { node ->
                Tasks.await(
                    Wearable.getMessageClient(context)
                        .sendMessage(node.id, CONTROL_STATUS_PATH, payload),
                    CONTROL_ACK_TIMEOUT_SECONDS,
                    TimeUnit.SECONDS,
                )
            }
        }

        private fun controlStatusPayload(
            requestId: String,
            status: String,
            errorCode: String?,
        ): ByteArray =
            JSONObject()
                .put("requestId", requestId)
                .put("status", status)
                .apply { errorCode?.let { put("errorCode", it) } }
                .toString()
                .toByteArray(Charsets.UTF_8)

        private const val CONTROL_STATUS_PATH = "/control/measurement/status"
        private const val CONTROL_ACK_TIMEOUT_SECONDS = 5L
    }
}
