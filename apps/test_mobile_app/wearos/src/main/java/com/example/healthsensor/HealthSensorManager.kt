/**
 * HealthSensorManager.kt — Samsung Health Sensor SDK v1.4.1 래퍼 (워치 측)
 *
 * 기본 실행 시 필요한 continuous 센서만 병렬로 시작한다:
 *   - HEART_RATE_CONTINUOUS      : 심박수 (~1 Hz)
 *   - PPG_CONTINUOUS             : PPG Raw Green·IR·Red (~25 Hz)
 *   - EDA_CONTINUOUS             : 피부 전기 전도도 (~1 Hz)
 *   - ACCELEROMETER_CONTINUOUS   : 3축 가속도 (~25 Hz)
 *   - SKIN_TEMPERATURE_CONTINUOUS: 피부 표면 온도 (~0.1 Hz)
 *
 * on-demand 센서 코드는 남아 있지만 ENABLE_ON_DEMAND_TRACKERS=false가 기본값이다.
 *
 * ※ 온디맨드 센서 제약:
 *   ECG_ON_DEMAND, SPO2_ON_DEMAND, BIA_ON_DEMAND 는 SDK 정책상 동시에 하나만
 *   실행 가능하다. 아래 코드에서는 각각 try-catch로 감싸 하나가 실패해도
 *   나머지 센서 동작에 영향을 주지 않도록 처리한다.
 *
 * 사전 조건: 갤럭시 워치 Samsung Health 개발자 모드 활성화 + 런타임 권한 허용
 */
package com.example.healthsensor

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

// ── 센서별 데이터 모델 ──────────────────────────────────────────────────────
data class HrData(val timestamp: Long, val bpm: Int, val status: Int)
data class PpgData(val timestamp: Long, val green: Int, val status: Int)
data class PpgIrData(val timestamp: Long, val ir: Int, val status: Int)
data class PpgRedData(val timestamp: Long, val red: Int, val status: Int)
data class EdaData(val timestamp: Long, val skinConductance: Float, val status: Int)
data class AccelData(val timestamp: Long, val x: Float, val y: Float, val z: Float)
data class SkinTempData(val timestamp: Long, val objectTemp: Float, val ambientTemp: Float, val status: Int)
// ECG: Samsung 정책 승인 전에는 SDK_POLICY_ERROR 발생 (코드는 준비 완료)
data class EcgData(val timestamp: Long, val ecg: Float, val status: Int)
// SPO2: 온디맨드 혈중 산소포화도
data class Spo2Data(val timestamp: Long, val spo2: Int, val status: Int)
// BIA: 온디맨드 체성분 (지방률·BMI·골격근량·체수분)
data class BiaData(
    val timestamp: Long,
    val bodyFat: Float,
    val bmr: Float,           // 기초대사량 (kcal) — SDK: BASAL_METABOLIC_RATE
    val skeletalMuscleMass: Float,
    val bodyWaterMass: Float, // 체수분 — SDK: TOTAL_BODY_WATER
    val status: Int
)
// 발한량
data class SweatLossData(val timestamp: Long, val sweatLoss: Float, val status: Int)

class HealthSensorManager(private val context: Context) {

    private val TAG = "HealthSensorManager"

    private var service: HealthTrackingService? = null
    private var hrTracker: HealthTracker? = null
    private var ppgTracker: HealthTracker? = null
    private var edaTracker: HealthTracker? = null
    private var accelTracker: HealthTracker? = null
    private var skinTempTracker: HealthTracker? = null
    private var ecgTracker: HealthTracker? = null
    private var spo2Tracker: HealthTracker? = null
    private var biaTracker: HealthTracker? = null
    private var sweatLossTracker: HealthTracker? = null
    private var trackersStarted = false

    private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    val connectionState: StateFlow<ConnectionState> = _connectionState

    // extraBufferCapacity: PPG/Accel SDK 배치(~300샘플)를 버퍼 오버플로 없이 수용
    private val _hrFlow       = MutableSharedFlow<HrData>(extraBufferCapacity = 200)
    val hrFlow: SharedFlow<HrData> = _hrFlow

    // PPG_CONTINUOUS 단일 트래커에서 Green / IR / Red를 각각 Flow로 emit
    private val _ppgFlow      = MutableSharedFlow<PpgData>(extraBufferCapacity = 500)
    val ppgFlow: SharedFlow<PpgData> = _ppgFlow

    private val _ppgIrFlow    = MutableSharedFlow<PpgIrData>(extraBufferCapacity = 500)
    val ppgIrFlow: SharedFlow<PpgIrData> = _ppgIrFlow

    private val _ppgRedFlow   = MutableSharedFlow<PpgRedData>(extraBufferCapacity = 500)
    val ppgRedFlow: SharedFlow<PpgRedData> = _ppgRedFlow

    private val _edaFlow      = MutableSharedFlow<EdaData>(extraBufferCapacity = 200)
    val edaFlow: SharedFlow<EdaData> = _edaFlow

    private val _accelFlow    = MutableSharedFlow<AccelData>(extraBufferCapacity = 500)
    val accelFlow: SharedFlow<AccelData> = _accelFlow

    private val _skinTempFlow = MutableSharedFlow<SkinTempData>(extraBufferCapacity = 50)
    val skinTempFlow: SharedFlow<SkinTempData> = _skinTempFlow

    // ECG ~500 Hz → 배치 버퍼 크게 확보
    private val _ecgFlow      = MutableSharedFlow<EcgData>(extraBufferCapacity = 2000)
    val ecgFlow: SharedFlow<EcgData> = _ecgFlow

    // SPO2 온디맨드
    private val _spo2Flow     = MutableSharedFlow<Spo2Data>(extraBufferCapacity = 50)
    val spo2Flow: SharedFlow<Spo2Data> = _spo2Flow

    // BIA 온디맨드 체성분
    private val _biaFlow      = MutableSharedFlow<BiaData>(extraBufferCapacity = 20)
    val biaFlow: SharedFlow<BiaData> = _biaFlow

    // 발한량
    private val _sweatLossFlow = MutableSharedFlow<SweatLossData>(extraBufferCapacity = 20)
    val sweatLossFlow: SharedFlow<SweatLossData> = _sweatLossFlow

    enum class ConnectionState { DISCONNECTED, CONNECTING, CONNECTED, ERROR }

    private val connectionListener = object : ConnectionListener {
        override fun onConnectionSuccess() {
            Log.i(TAG, "HealthTrackingService 연결 성공")
            _connectionState.value = ConnectionState.CONNECTED
        }
        override fun onConnectionEnded() {
            Log.i(TAG, "HealthTrackingService 연결 종료")
            _connectionState.value = ConnectionState.DISCONNECTED
        }
        override fun onConnectionFailed(e: HealthTrackerException) {
            // SDK_POLICY_ERROR: 갤럭시 워치 Samsung Health 개발자 모드 미활성화
            Log.e(TAG, "HealthTrackingService 연결 실패: ${e.message}")
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
        service?.disconnectService()
        service = null
        _connectionState.value = ConnectionState.DISCONNECTED
    }

    /**
     * 지원 센서 목록을 확인한 후 사용 가능한 트래커만 시작한다.
     *
     * ※ 온디맨드 센서 제약:
     *   ECG_ON_DEMAND, SPO2_ON_DEMAND, BIA_ON_DEMAND 는 SDK 정책상 동시에
     *   하나만 실행 가능하다. 각 startXxx()는 try-catch로 감싸져 있으므로
     *   하나가 실패해도 나머지 연속 센서 동작에 영향을 주지 않는다.
     */
    fun startTracking() {
        if (trackersStarted) return
        val svc = service ?: return
        val supported = svc.getTrackingCapability().getSupportHealthTrackerTypes()
        Log.i(TAG, "지원 센서: $supported")
        trackersStarted = true

        if (HealthTrackerType.HEART_RATE_CONTINUOUS       in supported) startHr(svc)
        else Log.w(TAG, "HEART_RATE_CONTINUOUS 미지원")

        if (HealthTrackerType.PPG_CONTINUOUS in supported) startPpg(svc)
        else Log.w(TAG, "PPG_CONTINUOUS 미지원")

        if (HealthTrackerType.EDA_CONTINUOUS              in supported) startEda(svc)
        else Log.w(TAG, "EDA_CONTINUOUS 미지원")

        if (HealthTrackerType.ACCELEROMETER_CONTINUOUS    in supported) startAccel(svc)
        else Log.w(TAG, "ACCELEROMETER_CONTINUOUS 미지원")

        if (HealthTrackerType.SKIN_TEMPERATURE_CONTINUOUS in supported) startSkinTemp(svc)
        else Log.w(TAG, "SKIN_TEMPERATURE_CONTINUOUS 미지원")

        if (ENABLE_ON_DEMAND_TRACKERS) {
            if (HealthTrackerType.ECG_ON_DEMAND in supported) startEcg(svc)
            if (HealthTrackerType.SPO2_ON_DEMAND in supported) startSpo2(svc)
            if (HealthTrackerType.BIA_ON_DEMAND in supported) startBia(svc)
            if (HealthTrackerType.SWEAT_LOSS in supported) startSweatLoss(svc)
        }
    }

    fun stopTracking() {
        listOf(
            hrTracker, ppgTracker,
            edaTracker, accelTracker,
            skinTempTracker, ecgTracker, spo2Tracker, biaTracker, sweatLossTracker
        ).forEach { runCatching { it?.unsetEventListener() } }
        hrTracker = null
        ppgTracker = null
        edaTracker = null
        accelTracker = null; skinTempTracker = null; ecgTracker = null
        spo2Tracker = null; biaTracker = null; sweatLossTracker = null
        trackersStarted = false
    }

    /**
     * SDK 내부 배치 버퍼를 즉시 flush하여 onDataReceived를 강제 호출한다.
     * PPG/Accel은 SDK가 ~12초치를 버퍼링 후 전달할 수 있는데, 200ms 주기로 flush하면
     * 실시간에 가까운 수신이 가능하다.
     */
    fun flushAllTrackers() {
        listOf(
            hrTracker, ppgTracker,
            edaTracker, accelTracker,
            skinTempTracker, ecgTracker, spo2Tracker, biaTracker, sweatLossTracker
        ).forEach { runCatching { it?.flush() } }
    }

    // ── 각 센서 트래커 시작 ────────────────────────────────────────────────

    private fun startHr(svc: HealthTrackingService) {
        try {
            hrTracker = svc.getHealthTracker(HealthTrackerType.HEART_RATE_CONTINUOUS)
            hrTracker?.setEventListener(hrListener)
            Log.i(TAG, "심박수 트래커 시작")
        } catch (e: Exception) { Log.e(TAG, "심박수 시작 실패: ${e.message}") }
    }

    private fun startPpg(svc: HealthTrackingService) {
        try {
            ppgTracker = svc.getHealthTracker(
                HealthTrackerType.PPG_CONTINUOUS,
                setOf(PpgType.GREEN, PpgType.IR, PpgType.RED)
            )
            ppgTracker?.setEventListener(ppgListener)
            Log.i(TAG, "PPG_CONTINUOUS 트래커 시작")
        } catch (e: Exception) {
            Log.e(TAG, "PPG_CONTINUOUS 시작 실패: ${e.message}")
        }
    }

    private fun startEda(svc: HealthTrackingService) {
        try {
            edaTracker = svc.getHealthTracker(HealthTrackerType.EDA_CONTINUOUS)
            edaTracker?.setEventListener(edaListener)
            Log.i(TAG, "EDA 트래커 시작")
        } catch (e: Exception) { Log.e(TAG, "EDA 시작 실패: ${e.message}") }
    }

    private fun startAccel(svc: HealthTrackingService) {
        try {
            accelTracker = svc.getHealthTracker(HealthTrackerType.ACCELEROMETER_CONTINUOUS)
            accelTracker?.setEventListener(accelListener)
            Log.i(TAG, "가속도 트래커 시작")
        } catch (e: Exception) { Log.e(TAG, "가속도 시작 실패: ${e.message}") }
    }

    private fun startSkinTemp(svc: HealthTrackingService) {
        try {
            skinTempTracker = svc.getHealthTracker(HealthTrackerType.SKIN_TEMPERATURE_CONTINUOUS)
            skinTempTracker?.setEventListener(skinTempListener)
            Log.i(TAG, "피부 온도 트래커 시작")
        } catch (e: Exception) { Log.e(TAG, "피부 온도 시작 실패: ${e.message}") }
    }

    private fun startEcg(svc: HealthTrackingService) {
        try {
            // ECG_ON_DEMAND 사용 (SDK v1.4.1 — ECG 대신 ECG_ON_DEMAND)
            ecgTracker = svc.getHealthTracker(HealthTrackerType.ECG_ON_DEMAND)
            ecgTracker?.setEventListener(ecgListener)
            Log.i(TAG, "ECG 온디맨드 트래커 시작")
        } catch (e: Exception) { Log.e(TAG, "ECG 시작 실패 (정책 승인 필요 또는 다른 온디맨드 실행 중): ${e.message}") }
    }

    private fun startSpo2(svc: HealthTrackingService) {
        try {
            spo2Tracker = svc.getHealthTracker(HealthTrackerType.SPO2_ON_DEMAND)
            spo2Tracker?.setEventListener(spo2Listener)
            Log.i(TAG, "SpO2 온디맨드 트래커 시작")
        } catch (e: Exception) { Log.e(TAG, "SpO2 시작 실패 (다른 온디맨드 실행 중일 수 있음): ${e.message}") }
    }

    private fun startBia(svc: HealthTrackingService) {
        try {
            biaTracker = svc.getHealthTracker(HealthTrackerType.BIA_ON_DEMAND)
            biaTracker?.setEventListener(biaListener)
            Log.i(TAG, "BIA 온디맨드 트래커 시작")
        } catch (e: Exception) { Log.e(TAG, "BIA 시작 실패 (다른 온디맨드 실행 중일 수 있음): ${e.message}") }
    }

    private fun startSweatLoss(svc: HealthTrackingService) {
        try {
            sweatLossTracker = svc.getHealthTracker(HealthTrackerType.SWEAT_LOSS)
            sweatLossTracker?.setEventListener(sweatLossListener)
            Log.i(TAG, "발한량 트래커 시작")
        } catch (e: Exception) { Log.e(TAG, "발한량 시작 실패: ${e.message}") }
    }

    // ── 센서 이벤트 리스너 ─────────────────────────────────────────────────

    private val hrListener = object : HealthTracker.TrackerEventListener {
        override fun onDataReceived(dataPoints: List<DataPoint>) {
            for (dp in dataPoints) {
                val bpm    = dp.getValue(ValueKey.HeartRateSet.HEART_RATE)
                val status = dp.getValue(ValueKey.HeartRateSet.HEART_RATE_STATUS)
                _hrFlow.tryEmit(HrData(dp.timestamp, bpm, status))
            }
        }
        override fun onFlushCompleted() {}
        override fun onError(e: HealthTracker.TrackerError) { Log.e(TAG, "HR 오류: $e") }
    }

    private val ppgListener = object : HealthTracker.TrackerEventListener {
        override fun onDataReceived(dataPoints: List<DataPoint>) {
            for (dp in dataPoints) {
                val ts = dp.timestamp
                _ppgFlow.tryEmit(PpgData(ts, dp.getValue(ValueKey.PpgSet.PPG_GREEN), dp.getValue(ValueKey.PpgSet.GREEN_STATUS)))
                _ppgIrFlow.tryEmit(PpgIrData(ts, dp.getValue(ValueKey.PpgSet.PPG_IR), dp.getValue(ValueKey.PpgSet.IR_STATUS)))
                _ppgRedFlow.tryEmit(PpgRedData(ts, dp.getValue(ValueKey.PpgSet.PPG_RED), dp.getValue(ValueKey.PpgSet.RED_STATUS)))
            }
        }
        override fun onFlushCompleted() {}
        override fun onError(e: HealthTracker.TrackerError) { Log.e(TAG, "PPG 오류: $e") }
    }

    private val edaListener = object : HealthTracker.TrackerEventListener {
        override fun onDataReceived(dataPoints: List<DataPoint>) {
            for (dp in dataPoints) {
                val conductance = dp.getValue(ValueKey.EdaSet.SKIN_CONDUCTANCE)
                val status      = dp.getValue(ValueKey.EdaSet.STATUS)
                _edaFlow.tryEmit(EdaData(dp.timestamp, conductance, status))
            }
        }
        override fun onFlushCompleted() {}
        override fun onError(e: HealthTracker.TrackerError) { Log.e(TAG, "EDA 오류: $e") }
    }

    private val accelListener = object : HealthTracker.TrackerEventListener {
        override fun onDataReceived(dataPoints: List<DataPoint>) {
            for (dp in dataPoints) {
                // AccelerometerSet 값은 Int로 반환되므로 Float 변환 필요
                val x = dp.getValue(ValueKey.AccelerometerSet.ACCELEROMETER_X).toFloat()
                val y = dp.getValue(ValueKey.AccelerometerSet.ACCELEROMETER_Y).toFloat()
                val z = dp.getValue(ValueKey.AccelerometerSet.ACCELEROMETER_Z).toFloat()
                _accelFlow.tryEmit(AccelData(dp.timestamp, x, y, z))
            }
        }
        override fun onFlushCompleted() {}
        override fun onError(e: HealthTracker.TrackerError) { Log.e(TAG, "가속도 오류: $e") }
    }

    private val skinTempListener = object : HealthTracker.TrackerEventListener {
        override fun onDataReceived(dataPoints: List<DataPoint>) {
            for (dp in dataPoints) {
                val obj     = dp.getValue(ValueKey.SkinTemperatureSet.OBJECT_TEMPERATURE)
                val ambient = dp.getValue(ValueKey.SkinTemperatureSet.AMBIENT_TEMPERATURE)
                val status  = dp.getValue(ValueKey.SkinTemperatureSet.STATUS)
                _skinTempFlow.tryEmit(SkinTempData(dp.timestamp, obj, ambient, status))
            }
        }
        override fun onFlushCompleted() {}
        override fun onError(e: HealthTracker.TrackerError) { Log.e(TAG, "피부 온도 오류: $e") }
    }

    private val ecgListener = object : HealthTracker.TrackerEventListener {
        override fun onDataReceived(dataPoints: List<DataPoint>) {
            for (dp in dataPoints) {
                val ecg    = dp.getValue(ValueKey.EcgSet.ECG_MV)
                val status = dp.getValue(ValueKey.EcgSet.LEAD_OFF)
                _ecgFlow.tryEmit(EcgData(dp.timestamp, ecg, status))
            }
        }
        override fun onFlushCompleted() {}
        override fun onError(e: HealthTracker.TrackerError) { Log.e(TAG, "ECG 오류: $e") }
    }

    private val spo2Listener = object : HealthTracker.TrackerEventListener {
        override fun onDataReceived(dataPoints: List<DataPoint>) {
            for (dp in dataPoints) {
                val spo2   = dp.getValue(ValueKey.SpO2Set.SPO2)
                val status = dp.getValue(ValueKey.SpO2Set.STATUS)
                _spo2Flow.tryEmit(Spo2Data(dp.timestamp, spo2, status))
            }
        }
        override fun onFlushCompleted() {}
        override fun onError(e: HealthTracker.TrackerError) { Log.e(TAG, "SpO2 오류: $e") }
    }

    private val biaListener = object : HealthTracker.TrackerEventListener {
        override fun onDataReceived(dataPoints: List<DataPoint>) {
            for (dp in dataPoints) {
                val bodyFat            = dp.getValue(ValueKey.BiaSet.BODY_FAT_RATIO)
                val bmr                = dp.getValue(ValueKey.BiaSet.BASAL_METABOLIC_RATE)
                val skeletalMuscleMass = dp.getValue(ValueKey.BiaSet.SKELETAL_MUSCLE_MASS)
                val bodyWaterMass      = dp.getValue(ValueKey.BiaSet.TOTAL_BODY_WATER)
                val status             = dp.getValue(ValueKey.BiaSet.STATUS)
                _biaFlow.tryEmit(BiaData(dp.timestamp, bodyFat, bmr, skeletalMuscleMass, bodyWaterMass, status))
            }
        }
        override fun onFlushCompleted() {}
        override fun onError(e: HealthTracker.TrackerError) { Log.e(TAG, "BIA 오류: $e") }
    }

    private val sweatLossListener = object : HealthTracker.TrackerEventListener {
        override fun onDataReceived(dataPoints: List<DataPoint>) {
            for (dp in dataPoints) {
                val sweatLoss = dp.getValue(ValueKey.SweatLossSet.SWEAT_LOSS)
                val status    = dp.getValue(ValueKey.SweatLossSet.STATUS)
                _sweatLossFlow.tryEmit(SweatLossData(dp.timestamp, sweatLoss, status))
            }
        }
        override fun onFlushCompleted() {}
        override fun onError(e: HealthTracker.TrackerError) { Log.e(TAG, "발한량 오류: $e") }
    }

    companion object {
        private const val ENABLE_ON_DEMAND_TRACKERS = false
    }
}
