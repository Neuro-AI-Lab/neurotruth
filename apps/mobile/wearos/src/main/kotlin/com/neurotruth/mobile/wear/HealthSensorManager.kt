package com.neurotruth.mobile.wear

import android.content.Context
import android.util.Log
import com.samsung.android.service.health.tracking.ConnectionListener
import com.samsung.android.service.health.tracking.HealthTracker
import com.samsung.android.service.health.tracking.HealthTrackerException
import com.samsung.android.service.health.tracking.HealthTrackingService
import com.samsung.android.service.health.tracking.data.DataPoint
import com.samsung.android.service.health.tracking.data.HealthTrackerType
import com.samsung.android.service.health.tracking.data.PpgType
import com.samsung.android.service.health.tracking.data.ValueKey
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

data class HrData(val timestamp: Long, val bpm: Int, val status: Int)
data class PpgData(val timestamp: Long, val green: Int, val status: Int)
data class PpgIrData(val timestamp: Long, val ir: Int, val status: Int)
data class PpgRedData(val timestamp: Long, val red: Int, val status: Int)
data class EdaData(val timestamp: Long, val skinConductance: Float, val status: Int)
data class AccelData(val timestamp: Long, val x: Float, val y: Float, val z: Float)
data class SkinTempData(
    val timestamp: Long,
    val objectTemp: Float,
    val ambientTemp: Float,
    val status: Int,
)

/**
 * Samsung Health Sensor SDK v1.4.1 wrapper.
 *
 * Only the continuous trackers the upload contract needs are started:
 *
 * | Tracker | Rate |
 * |---|---|
 * | `HEART_RATE_CONTINUOUS` | ~1 Hz |
 * | `PPG_CONTINUOUS` (green, IR, red) | ~25 Hz |
 * | `EDA_CONTINUOUS` | ~1 Hz |
 * | `ACCELEROMETER_CONTINUOUS` | ~25 Hz |
 * | `SKIN_TEMPERATURE_CONTINUOUS` | ~0.1 Hz |
 *
 * The on-demand trackers (ECG, SpO2, BIA) are excluded: the SDK permits only one of them at a time
 * and none of them feeds the craving model.
 *
 * Requires Samsung Health developer mode on the watch plus the runtime sensor permissions;
 * otherwise the connection fails with `SDK_POLICY_ERROR`.
 */
class HealthSensorManager(private val context: Context) {

    private var service: HealthTrackingService? = null
    private var hrTracker: HealthTracker? = null
    private var ppgTracker: HealthTracker? = null
    private var edaTracker: HealthTracker? = null
    private var accelTracker: HealthTracker? = null
    private var skinTempTracker: HealthTracker? = null
    private var trackersStarted = false

    private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    val connectionState: StateFlow<ConnectionState> = _connectionState

    // extraBufferCapacity absorbs an SDK batch (~300 samples) without dropping emissions.
    private val _hrFlow = MutableSharedFlow<HrData>(extraBufferCapacity = 200)
    val hrFlow: SharedFlow<HrData> = _hrFlow

    // A single PPG_CONTINUOUS tracker emits green, IR and red; each becomes its own channel.
    private val _ppgFlow = MutableSharedFlow<PpgData>(extraBufferCapacity = 500)
    val ppgFlow: SharedFlow<PpgData> = _ppgFlow

    private val _ppgIrFlow = MutableSharedFlow<PpgIrData>(extraBufferCapacity = 500)
    val ppgIrFlow: SharedFlow<PpgIrData> = _ppgIrFlow

    private val _ppgRedFlow = MutableSharedFlow<PpgRedData>(extraBufferCapacity = 500)
    val ppgRedFlow: SharedFlow<PpgRedData> = _ppgRedFlow

    private val _edaFlow = MutableSharedFlow<EdaData>(extraBufferCapacity = 200)
    val edaFlow: SharedFlow<EdaData> = _edaFlow

    private val _accelFlow = MutableSharedFlow<AccelData>(extraBufferCapacity = 500)
    val accelFlow: SharedFlow<AccelData> = _accelFlow

    private val _skinTempFlow = MutableSharedFlow<SkinTempData>(extraBufferCapacity = 50)
    val skinTempFlow: SharedFlow<SkinTempData> = _skinTempFlow

    enum class ConnectionState { DISCONNECTED, CONNECTING, CONNECTED, ERROR }

    private val connectionListener = object : ConnectionListener {
        override fun onConnectionSuccess() {
            _connectionState.value = ConnectionState.CONNECTED
        }

        override fun onConnectionEnded() {
            // Reset the tracker state so a later reconnect actually re-registers. Without this,
            // trackersStarted stays true and startTracking() would early-return, registering nothing.
            stopTracking()
            _connectionState.value = ConnectionState.DISCONNECTED
        }

        override fun onConnectionFailed(e: HealthTrackerException) {
            // SDK_POLICY_ERROR means Samsung Health developer mode is off on the watch.
            Log.e(TAG, "HealthTrackingService 연결 실패: ${e.message}")
            stopTracking()
            _connectionState.value = ConnectionState.ERROR
        }
    }

    fun connect() {
        _connectionState.value = ConnectionState.CONNECTING
        service = HealthTrackingService(connectionListener, context)
        service?.connectService()
    }

    fun disconnect() {
        stopTracking()
        runCatching { service?.disconnectService() }
        service = null
        _connectionState.value = ConnectionState.DISCONNECTED
    }

    /** Starts only the trackers this watch actually reports as supported. */
    fun startTracking() {
        if (trackersStarted) return
        val svc = service ?: return
        val supported = svc.getTrackingCapability().getSupportHealthTrackerTypes()
        trackersStarted = true

        if (HealthTrackerType.HEART_RATE_CONTINUOUS in supported) startHr(svc)
        else Log.w(TAG, "HEART_RATE_CONTINUOUS 미지원")

        if (HealthTrackerType.PPG_CONTINUOUS in supported) startPpg(svc)
        else Log.w(TAG, "PPG_CONTINUOUS 미지원")

        if (HealthTrackerType.EDA_CONTINUOUS in supported) startEda(svc)
        else Log.w(TAG, "EDA_CONTINUOUS 미지원")

        if (HealthTrackerType.ACCELEROMETER_CONTINUOUS in supported) startAccel(svc)
        else Log.w(TAG, "ACCELEROMETER_CONTINUOUS 미지원")

        if (HealthTrackerType.SKIN_TEMPERATURE_CONTINUOUS in supported) startSkinTemp(svc)
        else Log.w(TAG, "SKIN_TEMPERATURE_CONTINUOUS 미지원")
    }

    fun stopTracking() {
        trackers().forEach { tracker -> runCatching { tracker?.unsetEventListener() } }
        hrTracker = null
        ppgTracker = null
        edaTracker = null
        accelTracker = null
        skinTempTracker = null
        trackersStarted = false
    }

    /**
     * Drains the SDK's internal batch buffer so `onDataReceived` fires now.
     *
     * PPG and accelerometer are otherwise buffered for up to ~12 seconds. Flushing on the ~200 ms
     * cadence turns that into near-real-time delivery, which is what keeps the phone's 20-second
     * rolling window actually current.
     */
    fun flushAllTrackers() {
        trackers().forEach { tracker -> runCatching { tracker?.flush() } }
    }

    private fun trackers(): List<HealthTracker?> =
        listOf(hrTracker, ppgTracker, edaTracker, accelTracker, skinTempTracker)

    private fun startHr(svc: HealthTrackingService) {
        runCatching {
            hrTracker = svc.getHealthTracker(HealthTrackerType.HEART_RATE_CONTINUOUS)
            hrTracker?.setEventListener(hrListener)
        }.onFailure { Log.e(TAG, "심박수 시작 실패: ${it.message}") }
    }

    private fun startPpg(svc: HealthTrackingService) {
        runCatching {
            ppgTracker = svc.getHealthTracker(
                HealthTrackerType.PPG_CONTINUOUS,
                setOf(PpgType.GREEN, PpgType.IR, PpgType.RED),
            )
            ppgTracker?.setEventListener(ppgListener)
        }.onFailure { Log.e(TAG, "PPG_CONTINUOUS 시작 실패: ${it.message}") }
    }

    private fun startEda(svc: HealthTrackingService) {
        runCatching {
            edaTracker = svc.getHealthTracker(HealthTrackerType.EDA_CONTINUOUS)
            edaTracker?.setEventListener(edaListener)
        }.onFailure { Log.e(TAG, "EDA 시작 실패: ${it.message}") }
    }

    private fun startAccel(svc: HealthTrackingService) {
        runCatching {
            accelTracker = svc.getHealthTracker(HealthTrackerType.ACCELEROMETER_CONTINUOUS)
            accelTracker?.setEventListener(accelListener)
        }.onFailure { Log.e(TAG, "가속도 시작 실패: ${it.message}") }
    }

    private fun startSkinTemp(svc: HealthTrackingService) {
        runCatching {
            skinTempTracker = svc.getHealthTracker(HealthTrackerType.SKIN_TEMPERATURE_CONTINUOUS)
            skinTempTracker?.setEventListener(skinTempListener)
        }.onFailure { Log.e(TAG, "피부 온도 시작 실패: ${it.message}") }
    }

    private val hrListener = object : HealthTracker.TrackerEventListener {
        override fun onDataReceived(dataPoints: List<DataPoint>) {
            for (dp in dataPoints) {
                _hrFlow.tryEmit(
                    HrData(
                        dp.timestamp,
                        dp.getValue(ValueKey.HeartRateSet.HEART_RATE),
                        dp.getValue(ValueKey.HeartRateSet.HEART_RATE_STATUS),
                    ),
                )
            }
        }

        override fun onFlushCompleted() = Unit
        override fun onError(e: HealthTracker.TrackerError) {
            Log.e(TAG, "HR 오류: $e")
        }
    }

    private val ppgListener = object : HealthTracker.TrackerEventListener {
        override fun onDataReceived(dataPoints: List<DataPoint>) {
            for (dp in dataPoints) {
                val ts = dp.timestamp
                _ppgFlow.tryEmit(
                    PpgData(
                        ts,
                        dp.getValue(ValueKey.PpgSet.PPG_GREEN),
                        dp.getValue(ValueKey.PpgSet.GREEN_STATUS),
                    ),
                )
                _ppgIrFlow.tryEmit(
                    PpgIrData(
                        ts,
                        dp.getValue(ValueKey.PpgSet.PPG_IR),
                        dp.getValue(ValueKey.PpgSet.IR_STATUS),
                    ),
                )
                _ppgRedFlow.tryEmit(
                    PpgRedData(
                        ts,
                        dp.getValue(ValueKey.PpgSet.PPG_RED),
                        dp.getValue(ValueKey.PpgSet.RED_STATUS),
                    ),
                )
            }
        }

        override fun onFlushCompleted() = Unit
        override fun onError(e: HealthTracker.TrackerError) {
            Log.e(TAG, "PPG 오류: $e")
        }
    }

    private val edaListener = object : HealthTracker.TrackerEventListener {
        override fun onDataReceived(dataPoints: List<DataPoint>) {
            for (dp in dataPoints) {
                _edaFlow.tryEmit(
                    EdaData(
                        dp.timestamp,
                        dp.getValue(ValueKey.EdaSet.SKIN_CONDUCTANCE),
                        dp.getValue(ValueKey.EdaSet.STATUS),
                    ),
                )
            }
        }

        override fun onFlushCompleted() = Unit
        override fun onError(e: HealthTracker.TrackerError) {
            Log.e(TAG, "EDA 오류: $e")
        }
    }

    private val accelListener = object : HealthTracker.TrackerEventListener {
        override fun onDataReceived(dataPoints: List<DataPoint>) {
            for (dp in dataPoints) {
                // AccelerometerSet returns Int, so the conversion is not redundant.
                _accelFlow.tryEmit(
                    AccelData(
                        dp.timestamp,
                        dp.getValue(ValueKey.AccelerometerSet.ACCELEROMETER_X).toFloat(),
                        dp.getValue(ValueKey.AccelerometerSet.ACCELEROMETER_Y).toFloat(),
                        dp.getValue(ValueKey.AccelerometerSet.ACCELEROMETER_Z).toFloat(),
                    ),
                )
            }
        }

        override fun onFlushCompleted() = Unit
        override fun onError(e: HealthTracker.TrackerError) {
            Log.e(TAG, "가속도 오류: $e")
        }
    }

    private val skinTempListener = object : HealthTracker.TrackerEventListener {
        override fun onDataReceived(dataPoints: List<DataPoint>) {
            for (dp in dataPoints) {
                _skinTempFlow.tryEmit(
                    SkinTempData(
                        dp.timestamp,
                        dp.getValue(ValueKey.SkinTemperatureSet.OBJECT_TEMPERATURE),
                        dp.getValue(ValueKey.SkinTemperatureSet.AMBIENT_TEMPERATURE),
                        dp.getValue(ValueKey.SkinTemperatureSet.STATUS),
                    ),
                )
            }
        }

        override fun onFlushCompleted() = Unit
        override fun onError(e: HealthTracker.TrackerError) {
            Log.e(TAG, "피부 온도 오류: $e")
        }
    }

    private companion object {
        const val TAG = "HealthSensorManager"
    }
}
