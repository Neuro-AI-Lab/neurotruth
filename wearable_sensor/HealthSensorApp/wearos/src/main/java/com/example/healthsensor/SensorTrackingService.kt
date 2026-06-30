/**
 * SensorTrackingService.kt — 센서 측정 Foreground Service
 *
 * Activity 생명주기와 독립적으로 동작하는 포그라운드 서비스.
 * 워치 화면이 꺼지거나 앱이 백그라운드로 전환되어도 센서 측정을 유지한다.
 *
 * 동작 흐름:
 *  1. MainActivity에서 start() 호출
 *  2. 포그라운드 알림 표시 + PARTIAL_WAKE_LOCK 획득
 *  3. HealthSensorManager 연결 → CONNECTED 시 트래커 시작
 *  4. 180ms 대기 → flushAllTrackers() → 20ms 대기(콜백 수신) → 배치 전송
 *  5. stop() 호출 시 서비스 종료 및 리소스 해제
 *
 * 배치 전송 형식: [count:Int(4)] + count × [timestamp:Long(8) + value:Float(4)]
 *
 * 지원 센서 채널 (9개):
 *   /sensor/hr          — 심박수 BPM
 *   /sensor/ppg         — PPG Green Raw
 *   /sensor/ppg_ir      — PPG IR Raw
 *   /sensor/ppg_red     — PPG Red Raw
 *   /sensor/eda         — 피부 전기 전도도 (μS)
 *   /sensor/accel_x     — 가속도 X축 (m/s²)
 *   /sensor/accel_y     — 가속도 Y축 (m/s²)
 *   /sensor/accel_z     — 가속도 Z축 (m/s²)
 *   /sensor/skin_temp   — 피부 표면 온도 (°C)
 */
package com.example.healthsensor

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.os.PowerManager
import kotlinx.coroutines.*

class SensorTrackingService : Service() {

    private lateinit var sensorManager: HealthSensorManager
    private lateinit var phoneSender: PhoneDataSender

    // SupervisorJob: 하나의 코루틴 실패가 다른 코루틴에 영향을 주지 않도록 격리
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var wakeLock: PowerManager.WakeLock? = null
    private var trackingStarted = false

    // Dispatchers.Main 단일 스레드 환경이므로 별도 동기화 불필요
    private val hrBuf        = mutableListOf<Pair<Long, Float>>()
    private val ppgBuf       = mutableListOf<Pair<Long, Float>>()
    private val ppgIrBuf     = mutableListOf<Pair<Long, Float>>()
    private val ppgRedBuf    = mutableListOf<Pair<Long, Float>>()
    private val edaBuf       = mutableListOf<Pair<Long, Float>>()
    private val accelXBuf    = mutableListOf<Pair<Long, Float>>()
    private val accelYBuf    = mutableListOf<Pair<Long, Float>>()
    private val accelZBuf    = mutableListOf<Pair<Long, Float>>()
    private val skinTempBuf  = mutableListOf<Pair<Long, Float>>()

    override fun onCreate() {
        super.onCreate()
        sensorManager = HealthSensorManager(this)
        phoneSender   = PhoneDataSender(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return when (intent?.action) {
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
    }

    private fun startTracking() {
        if (trackingStarted) return
        trackingStarted = true
        startForeground(NOTIF_ID, buildNotification())

        // 화면 꺼짐 상태에서도 CPU가 슬립하지 않도록 WakeLock 획득 (최대 1시간)
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "HealthSensor:svc")
            .apply { acquire(60 * 60 * 1000L) }

        SensorState.isTracking.value = true
        SensorState.status.value = "연결 중..."

        // 연결 상태 감시: CONNECTED 상태가 되면 트래커를 시작
        serviceScope.launch {
            sensorManager.connectionState.collect { state ->
                SensorState.status.value = when (state) {
                    HealthSensorManager.ConnectionState.CONNECTING   -> "연결 중..."
                    HealthSensorManager.ConnectionState.CONNECTED    -> { sensorManager.startTracking(); "측정 중" }
                    HealthSensorManager.ConnectionState.DISCONNECTED -> "연결 끊김"
                    HealthSensorManager.ConnectionState.ERROR        -> "연결 오류"
                }
            }
        }

        // 각 센서 Flow 수집 → 버퍼 적재 + 워치 화면 표시값 갱신
        serviceScope.launch {
            sensorManager.hrFlow.collect { d ->
                hrBuf.add(d.timestamp to d.bpm.toFloat())
                SensorState.hrText.value = "${d.bpm} bpm"
            }
        }
        serviceScope.launch {
            sensorManager.ppgFlow.collect { d ->
                ppgBuf.add(d.timestamp to d.green.toFloat())
                SensorState.ppgText.value = "${d.green}"
            }
        }
        serviceScope.launch {
            sensorManager.ppgIrFlow.collect { d ->
                ppgIrBuf.add(d.timestamp to d.ir.toFloat())
                SensorState.ppgIrText.value = "${d.ir}"
            }
        }
        serviceScope.launch {
            sensorManager.ppgRedFlow.collect { d ->
                ppgRedBuf.add(d.timestamp to d.red.toFloat())
                SensorState.ppgRedText.value = "${d.red}"
            }
        }
        serviceScope.launch {
            sensorManager.edaFlow.collect { d ->
                edaBuf.add(d.timestamp to d.skinConductance)
                SensorState.edaText.value = "%.3f μS".format(d.skinConductance)
            }
        }
        serviceScope.launch {
            sensorManager.accelFlow.collect { d ->
                // X/Y/Z를 별도 채널로 각각 전송
                accelXBuf.add(d.timestamp to d.x)
                accelYBuf.add(d.timestamp to d.y)
                accelZBuf.add(d.timestamp to d.z)
                SensorState.accelXText.value = "%.1f".format(d.x)
                SensorState.accelYText.value = "%.1f".format(d.y)
                SensorState.accelZText.value = "%.1f".format(d.z)
            }
        }
        serviceScope.launch {
            sensorManager.skinTempFlow.collect { d ->
                skinTempBuf.add(d.timestamp to d.objectTemp)
                SensorState.skinTempText.value = "%.1f °C".format(d.objectTemp)
            }
        }

        // 핵심 타이머: 180ms 대기 → SDK flush → 20ms 대기(콜백 수신) → 배치 전송
        // flush()로 SDK 내부 버퍼를 비워 PPG/Accel의 ~12초 지연을 0.2초로 단축
        serviceScope.launch {
            while (true) {
                delay(180L)
                sensorManager.flushAllTrackers()
                delay(20L)
                if (hrBuf.isNotEmpty())        phoneSender.sendBatch("/sensor/hr",         hrBuf.toList().also        { hrBuf.clear() })
                if (ppgBuf.isNotEmpty())       phoneSender.sendBatch("/sensor/ppg",        ppgBuf.toList().also       { ppgBuf.clear() })
                if (ppgIrBuf.isNotEmpty())     phoneSender.sendBatch("/sensor/ppg_ir",     ppgIrBuf.toList().also     { ppgIrBuf.clear() })
                if (ppgRedBuf.isNotEmpty())    phoneSender.sendBatch("/sensor/ppg_red",    ppgRedBuf.toList().also    { ppgRedBuf.clear() })
                if (edaBuf.isNotEmpty())       phoneSender.sendBatch("/sensor/eda",        edaBuf.toList().also       { edaBuf.clear() })
                if (accelXBuf.isNotEmpty())    phoneSender.sendBatch("/sensor/accel_x",    accelXBuf.toList().also    { accelXBuf.clear() })
                if (accelYBuf.isNotEmpty())    phoneSender.sendBatch("/sensor/accel_y",    accelYBuf.toList().also    { accelYBuf.clear() })
                if (accelZBuf.isNotEmpty())    phoneSender.sendBatch("/sensor/accel_z",    accelZBuf.toList().also    { accelZBuf.clear() })
                if (skinTempBuf.isNotEmpty())  phoneSender.sendBatch("/sensor/skin_temp",  skinTempBuf.toList().also  { skinTempBuf.clear() })
            }
        }

        phoneSender.findPhoneNode()
        sensorManager.connect()
    }

    private fun stopAndClean() {
        trackingStarted = false
        if (wakeLock?.isHeld == true) wakeLock?.release()
        wakeLock = null
        runCatching { sensorManager.stopTracking() }
        runCatching { sensorManager.disconnect() }
        SensorState.isTracking.value   = false
        SensorState.status.value       = "중지됨"
        SensorState.hrText.value       = "—"
        SensorState.ppgText.value      = "—"
        SensorState.ppgIrText.value    = "—"
        SensorState.ppgRedText.value   = "—"
        SensorState.edaText.value      = "—"
        SensorState.accelXText.value   = "—"
        SensorState.accelYText.value   = "—"
        SensorState.accelZText.value   = "—"
        SensorState.skinTempText.value = "—"
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
        }
        SensorState.isTracking.value = false
        serviceScope.cancel()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun buildNotification(): Notification {
        val channelId = "sensor_ch"
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(channelId) == null) {
            nm.createNotificationChannel(
                NotificationChannel(channelId, "센서 측정", NotificationManager.IMPORTANCE_LOW)
            )
        }
        return Notification.Builder(this, channelId)
            .setContentTitle("센서 측정 중")
            .setContentText("HR · PPG · EDA · Accel · SkinTemp 수집 중")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setOngoing(true)
            .build()
    }

    companion object {
        const val ACTION_START = "START"
        const val ACTION_STOP  = "STOP"
        private const val NOTIF_ID = 1

        fun start(context: Context) {
            context.startForegroundService(
                Intent(context, SensorTrackingService::class.java).apply { action = ACTION_START }
            )
        }

        fun stop(context: Context) {
            context.startService(
                Intent(context, SensorTrackingService::class.java).apply { action = ACTION_STOP }
            )
        }
    }
}
