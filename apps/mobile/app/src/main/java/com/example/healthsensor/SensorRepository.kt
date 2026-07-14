/**
 * SensorRepository.kt — 폰 앱 센서 데이터 허브 (싱글톤)
 *
 * WearDataListenerService(수신) → SensorRepository → SensorViewModel(표시/저장)
 * 로 이어지는 단방향 데이터 흐름의 중간 계층.
 *
 * 기본 채널: HR, PPG Green/IR/Red, EDA, Accel X/Y/Z, Skin Temp.
 * on-demand 채널 Flow는 호환성 때문에 남겨두지만 현재 UI/서버 전송 대상은 아니다.
 *
 * extraBufferCapacity = 500: 워치에서 PPG/Accel 배치(~300샘플)가 한꺼번에 도착할 때
 * ViewModel이 처리하기 전에 Flow 버퍼가 넘치지 않도록 충분한 여유를 확보한다.
 */
package com.example.healthsensor

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow

object SensorRepository {

    private val _hrFlow       = MutableSharedFlow<Pair<Long, Float>>(extraBufferCapacity = 500)
    val hrFlow: SharedFlow<Pair<Long, Float>> = _hrFlow

    private val _ppgFlow      = MutableSharedFlow<Pair<Long, Float>>(extraBufferCapacity = 500)
    val ppgFlow: SharedFlow<Pair<Long, Float>> = _ppgFlow

    private val _ppgIrFlow    = MutableSharedFlow<Pair<Long, Float>>(extraBufferCapacity = 500)
    val ppgIrFlow: SharedFlow<Pair<Long, Float>> = _ppgIrFlow

    private val _ppgRedFlow   = MutableSharedFlow<Pair<Long, Float>>(extraBufferCapacity = 500)
    val ppgRedFlow: SharedFlow<Pair<Long, Float>> = _ppgRedFlow

    private val _edaFlow      = MutableSharedFlow<Pair<Long, Float>>(extraBufferCapacity = 500)
    val edaFlow: SharedFlow<Pair<Long, Float>> = _edaFlow

    private val _accelXFlow   = MutableSharedFlow<Pair<Long, Float>>(extraBufferCapacity = 500)
    val accelXFlow: SharedFlow<Pair<Long, Float>> = _accelXFlow

    private val _accelYFlow   = MutableSharedFlow<Pair<Long, Float>>(extraBufferCapacity = 500)
    val accelYFlow: SharedFlow<Pair<Long, Float>> = _accelYFlow

    private val _accelZFlow   = MutableSharedFlow<Pair<Long, Float>>(extraBufferCapacity = 500)
    val accelZFlow: SharedFlow<Pair<Long, Float>> = _accelZFlow

    private val _skinTempFlow = MutableSharedFlow<Pair<Long, Float>>(extraBufferCapacity = 50)
    val skinTempFlow: SharedFlow<Pair<Long, Float>> = _skinTempFlow

    // ECG ~500 Hz → 버퍼 크게 확보
    private val _ecgFlow    = MutableSharedFlow<Pair<Long, Float>>(extraBufferCapacity = 2000)
    val ecgFlow: SharedFlow<Pair<Long, Float>> = _ecgFlow

    // SpO2 및 BIA 복합 채널, 발한량
    private val _spo2Flow      = MutableSharedFlow<Pair<Long, Float>>(extraBufferCapacity = 50)
    val spo2Flow: SharedFlow<Pair<Long, Float>> = _spo2Flow

    private val _biaFatFlow    = MutableSharedFlow<Pair<Long, Float>>(extraBufferCapacity = 50)
    val biaFatFlow: SharedFlow<Pair<Long, Float>> = _biaFatFlow

    private val _biaBmiFlow    = MutableSharedFlow<Pair<Long, Float>>(extraBufferCapacity = 50)
    val biaBmiFlow: SharedFlow<Pair<Long, Float>> = _biaBmiFlow

    private val _biaMuscleFlow = MutableSharedFlow<Pair<Long, Float>>(extraBufferCapacity = 50)
    val biaMuscleFlow: SharedFlow<Pair<Long, Float>> = _biaMuscleFlow

    private val _biaWaterFlow  = MutableSharedFlow<Pair<Long, Float>>(extraBufferCapacity = 50)
    val biaWaterFlow: SharedFlow<Pair<Long, Float>> = _biaWaterFlow

    private val _sweatLossFlow = MutableSharedFlow<Pair<Long, Float>>(extraBufferCapacity = 50)
    val sweatLossFlow: SharedFlow<Pair<Long, Float>> = _sweatLossFlow

    fun onHr(ts: Long, bpm: Float)           { _hrFlow.tryEmit(Pair(ts, bpm)) }
    fun onPpg(ts: Long, green: Float)        { _ppgFlow.tryEmit(Pair(ts, green)) }
    fun onPpgIr(ts: Long, ir: Float)         { _ppgIrFlow.tryEmit(Pair(ts, ir)) }
    fun onPpgRed(ts: Long, red: Float)       { _ppgRedFlow.tryEmit(Pair(ts, red)) }
    fun onEda(ts: Long, conductance: Float)  { _edaFlow.tryEmit(Pair(ts, conductance)) }
    fun onAccelX(ts: Long, x: Float)         { _accelXFlow.tryEmit(Pair(ts, x)) }
    fun onAccelY(ts: Long, y: Float)         { _accelYFlow.tryEmit(Pair(ts, y)) }
    fun onAccelZ(ts: Long, z: Float)         { _accelZFlow.tryEmit(Pair(ts, z)) }
    fun onSkinTemp(ts: Long, temp: Float)    { _skinTempFlow.tryEmit(Pair(ts, temp)) }
    fun onEcg(ts: Long, ecg: Float)          { _ecgFlow.tryEmit(Pair(ts, ecg)) }
    fun onSpo2(ts: Long, v: Float)           { _spo2Flow.tryEmit(Pair(ts, v)) }
    fun onBiaFat(ts: Long, v: Float)         { _biaFatFlow.tryEmit(Pair(ts, v)) }
    fun onBiaBmi(ts: Long, v: Float)         { _biaBmiFlow.tryEmit(Pair(ts, v)) }
    fun onBiaMuscle(ts: Long, v: Float)      { _biaMuscleFlow.tryEmit(Pair(ts, v)) }
    fun onBiaWater(ts: Long, v: Float)       { _biaWaterFlow.tryEmit(Pair(ts, v)) }
    fun onSweatLoss(ts: Long, v: Float)      { _sweatLossFlow.tryEmit(Pair(ts, v)) }
}
