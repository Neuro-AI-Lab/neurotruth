package com.neurotruth.mobile.wear

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.os.PowerManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Foreground service that keeps sensor collection alive with the screen off.
 *
 * Flow: `startForeground` + a partial wake lock → connect `HealthTrackingService` → start the
 * trackers on CONNECTED → run the flush loop.
 *
 * The flush loop is the reason this is a service and not a coroutine in a ViewModel: it waits
 * [FLUSH_INTERVAL_MS], drains the SDK batch buffer, waits [CALLBACK_GRACE_MS] for the resulting
 * callbacks, then ships one batch per non-empty channel. Without the flush the SDK holds PPG and
 * accelerometer for ~12 seconds, which would make the phone's 20-second window permanently stale.
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
                startTracking()
                START_STICKY
            }

            ACTION_STOP -> {
                stopAndClean()
                START_NOT_STICKY
            }

            else -> {
                stopSelf()
                START_NOT_STICKY
            }
        }

    private fun startTracking() {
        if (trackingStarted) return
        trackingStarted = true
        startForeground(NOTIFICATION_ID, buildNotification())

        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_LOCK_TAG)
            .apply { acquire(WAKE_LOCK_TIMEOUT_MS) }

        SensorState.isTracking.value = true
        SensorState.status.value = "연결 중..."

        serviceScope.launch {
            sensorManager.connectionState.collect { state ->
                SensorState.status.value = when (state) {
                    HealthSensorManager.ConnectionState.CONNECTING -> "연결 중..."
                    HealthSensorManager.ConnectionState.CONNECTED -> {
                        sensorManager.startTracking()
                        "측정 중"
                    }

                    HealthSensorManager.ConnectionState.DISCONNECTED -> "연결 끊김"
                    HealthSensorManager.ConnectionState.ERROR -> "연결 오류"
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
                SensorState.ppgText.value = "${data.green}"
            }
        }
        serviceScope.launch {
            sensorManager.ppgIrFlow.collect { data ->
                ppgIrBuf.add(data.timestamp to data.ir.toFloat())
                SensorState.ppgIrText.value = "${data.ir}"
            }
        }
        serviceScope.launch {
            sensorManager.ppgRedFlow.collect { data ->
                ppgRedBuf.add(data.timestamp to data.red.toFloat())
                SensorState.ppgRedText.value = "${data.red}"
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
                SensorState.accelXText.value = "%.1f".format(data.x)
                SensorState.accelYText.value = "%.1f".format(data.y)
                SensorState.accelZText.value = "%.1f".format(data.z)
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
                sensorManager.flushAllTrackers()
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

    private fun stopAndClean() {
        trackingStarted = false
        if (wakeLock?.isHeld == true) wakeLock?.release()
        wakeLock = null
        runCatching { sensorManager.stopTracking() }
        runCatching { sensorManager.disconnect() }
        SensorState.isTracking.value = false
        SensorState.status.value = "중지됨"
        SensorState.resetSensorValues()
        serviceScope.coroutineContext.cancelChildren()
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
        const val ACTION_START = "com.neurotruth.mobile.wear.action.START"
        const val ACTION_STOP = "com.neurotruth.mobile.wear.action.STOP"

        private const val NOTIFICATION_ID = 1
        private const val CHANNEL_ID = "sensor_ch"
        private const val WAKE_LOCK_TAG = "NeuroTruthWear:tracking"
        private const val WAKE_LOCK_TIMEOUT_MS = 60L * 60L * 1000L

        /** ~200 ms total per cycle: flush, then let the callbacks land before shipping. */
        private const val FLUSH_INTERVAL_MS = 180L
        private const val CALLBACK_GRACE_MS = 20L

        fun start(context: Context) {
            context.startForegroundService(
                Intent(context, SensorTrackingService::class.java).apply { action = ACTION_START },
            )
        }

        fun stop(context: Context) {
            context.startService(
                Intent(context, SensorTrackingService::class.java).apply { action = ACTION_STOP },
            )
        }
    }
}
