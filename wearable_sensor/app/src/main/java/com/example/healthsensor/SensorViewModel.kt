/**
 * SensorViewModel.kt — 폰 앱 차트 데이터 및 CSV 원시 데이터 관리
 *
 * SensorRepository의 Flow를 구독하고 Compose UI에 필요한 상태를 제공한다.
 * - 차트용: 최근 MAX_POINTS(500)개 포인트를 슬라이딩 윈도우로 유지
 * - CSV용: 세션 시작부터 모든 원시 데이터를 누적 저장
 *
 * 15개 채널: HR, PPG Green, PPG IR, PPG Red, EDA, Accel X/Y/Z, Skin Temp,
 *            ECG, SpO2, BIA Fat, BIA BMI, BIA Muscle, BIA Water, Sweat Loss
 *
 * index 기반 x축:
 *   타임스탬프를 x축으로 사용하면 PPG(~25Hz)와 HR(~1Hz)의 시간 축이 달라
 *   차트 업데이트가 불규칙해진다. 단조 증가 카운터(index)를 사용하면
 *   MPAndroidChart의 addEntry/removeEntry 동작이 안정적으로 유지된다.
 */
package com.example.healthsensor

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.github.mikephil.charting.data.Entry
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** 차트 한 점: 시작 시각으로부터의 경과 시간(초, Float)을 x축으로 사용한다. */
data class SensorPoint(val timestamp: Long, val value: Float, val index: Float = 0f)

class SensorViewModel : ViewModel() {

    private val MAX_POINTS = 500   // 차트에 표시할 최대 포인트 수

    private val _hrPoints       = MutableStateFlow<List<SensorPoint>>(emptyList())
    val hrPoints: StateFlow<List<SensorPoint>> = _hrPoints

    private val _ppgPoints      = MutableStateFlow<List<SensorPoint>>(emptyList())
    val ppgPoints: StateFlow<List<SensorPoint>> = _ppgPoints

    private val _ppgIrPoints    = MutableStateFlow<List<SensorPoint>>(emptyList())
    val ppgIrPoints: StateFlow<List<SensorPoint>> = _ppgIrPoints

    private val _ppgRedPoints   = MutableStateFlow<List<SensorPoint>>(emptyList())
    val ppgRedPoints: StateFlow<List<SensorPoint>> = _ppgRedPoints

    private val _edaPoints      = MutableStateFlow<List<SensorPoint>>(emptyList())
    val edaPoints: StateFlow<List<SensorPoint>> = _edaPoints

    private val _accelXPoints   = MutableStateFlow<List<SensorPoint>>(emptyList())
    val accelXPoints: StateFlow<List<SensorPoint>> = _accelXPoints

    private val _accelYPoints   = MutableStateFlow<List<SensorPoint>>(emptyList())
    val accelYPoints: StateFlow<List<SensorPoint>> = _accelYPoints

    private val _accelZPoints   = MutableStateFlow<List<SensorPoint>>(emptyList())
    val accelZPoints: StateFlow<List<SensorPoint>> = _accelZPoints

    private val _skinTempPoints = MutableStateFlow<List<SensorPoint>>(emptyList())
    val skinTempPoints: StateFlow<List<SensorPoint>> = _skinTempPoints

    private val _ecgPoints      = MutableStateFlow<List<SensorPoint>>(emptyList())
    val ecgPoints: StateFlow<List<SensorPoint>> = _ecgPoints

    private val _spo2Points      = MutableStateFlow<List<SensorPoint>>(emptyList())
    val spo2Points: StateFlow<List<SensorPoint>> = _spo2Points

    private val _biaFatPoints    = MutableStateFlow<List<SensorPoint>>(emptyList())
    val biaFatPoints: StateFlow<List<SensorPoint>> = _biaFatPoints

    private val _biaBmiPoints    = MutableStateFlow<List<SensorPoint>>(emptyList())
    val biaBmiPoints: StateFlow<List<SensorPoint>> = _biaBmiPoints

    private val _biaMusclePoints = MutableStateFlow<List<SensorPoint>>(emptyList())
    val biaMusclePoints: StateFlow<List<SensorPoint>> = _biaMusclePoints

    private val _biaWaterPoints  = MutableStateFlow<List<SensorPoint>>(emptyList())
    val biaWaterPoints: StateFlow<List<SensorPoint>> = _biaWaterPoints

    private val _sweatLossPoints = MutableStateFlow<List<SensorPoint>>(emptyList())
    val sweatLossPoints: StateFlow<List<SensorPoint>> = _sweatLossPoints

    private val _isReceiving = MutableStateFlow(false)
    val isReceiving: StateFlow<Boolean> = _isReceiving

    // CSV 내보내기용 전체 원시 데이터 (세션 동안 누적)
    val allHrRaw       = mutableListOf<Pair<Long, Float>>()
    val allPpgRaw      = mutableListOf<Pair<Long, Float>>()
    val allPpgIrRaw    = mutableListOf<Pair<Long, Float>>()
    val allPpgRedRaw   = mutableListOf<Pair<Long, Float>>()
    val allEdaRaw      = mutableListOf<Pair<Long, Float>>()
    val allAccelXRaw   = mutableListOf<Pair<Long, Float>>()
    val allAccelYRaw   = mutableListOf<Pair<Long, Float>>()
    val allAccelZRaw   = mutableListOf<Pair<Long, Float>>()
    val allSkinTempRaw = mutableListOf<Pair<Long, Float>>()
    val allEcgRaw        = mutableListOf<Pair<Long, Float>>()
    val allSpo2Raw       = mutableListOf<Pair<Long, Float>>()
    val allBiaFatRaw     = mutableListOf<Pair<Long, Float>>()
    val allBiaBmiRaw     = mutableListOf<Pair<Long, Float>>()
    val allBiaMuscleRaw  = mutableListOf<Pair<Long, Float>>()
    val allBiaWaterRaw   = mutableListOf<Pair<Long, Float>>()
    val allSweatLossRaw  = mutableListOf<Pair<Long, Float>>()

    // 첫 샘플 수신 시각 — 모든 센서의 공통 x축 기준점 (100ms 단위)
    // 센서마다 독립 카운터를 쓰면 배치 크기 차이로 스케일이 달라지므로 타임스탬프 기반 공통축 사용
    @Volatile private var startTimestamp: Long = 0L

    private fun tsToX(ts: Long): Float {
        if (startTimestamp == 0L) startTimestamp = ts
        return ((ts - startTimestamp) / 100f)   // 100ms 단위 (x=10 → 1초 경과)
    }

    init {
        viewModelScope.launch {
            SensorRepository.hrFlow.collect { (ts, v) ->
                _isReceiving.value = true
                allHrRaw.add(ts to v)
                _hrPoints.update { list -> (list + SensorPoint(ts, v, tsToX(ts))).takeLast(MAX_POINTS) }
            }
        }
        viewModelScope.launch {
            SensorRepository.ppgFlow.collect { (ts, v) ->
                _isReceiving.value = true
                allPpgRaw.add(ts to v)
                _ppgPoints.update { list -> (list + SensorPoint(ts, v, tsToX(ts))).takeLast(MAX_POINTS) }
            }
        }
        viewModelScope.launch {
            SensorRepository.ppgIrFlow.collect { (ts, v) ->
                _isReceiving.value = true
                allPpgIrRaw.add(ts to v)
                _ppgIrPoints.update { list -> (list + SensorPoint(ts, v, tsToX(ts))).takeLast(MAX_POINTS) }
            }
        }
        viewModelScope.launch {
            SensorRepository.ppgRedFlow.collect { (ts, v) ->
                _isReceiving.value = true
                allPpgRedRaw.add(ts to v)
                _ppgRedPoints.update { list -> (list + SensorPoint(ts, v, tsToX(ts))).takeLast(MAX_POINTS) }
            }
        }
        viewModelScope.launch {
            SensorRepository.edaFlow.collect { (ts, v) ->
                _isReceiving.value = true
                allEdaRaw.add(ts to v)
                _edaPoints.update { list -> (list + SensorPoint(ts, v, tsToX(ts))).takeLast(MAX_POINTS) }
            }
        }
        viewModelScope.launch {
            SensorRepository.accelXFlow.collect { (ts, v) ->
                _isReceiving.value = true
                allAccelXRaw.add(ts to v)
                _accelXPoints.update { list -> (list + SensorPoint(ts, v, tsToX(ts))).takeLast(MAX_POINTS) }
            }
        }
        viewModelScope.launch {
            SensorRepository.accelYFlow.collect { (ts, v) ->
                _isReceiving.value = true
                allAccelYRaw.add(ts to v)
                _accelYPoints.update { list -> (list + SensorPoint(ts, v, tsToX(ts))).takeLast(MAX_POINTS) }
            }
        }
        viewModelScope.launch {
            SensorRepository.accelZFlow.collect { (ts, v) ->
                _isReceiving.value = true
                allAccelZRaw.add(ts to v)
                _accelZPoints.update { list -> (list + SensorPoint(ts, v, tsToX(ts))).takeLast(MAX_POINTS) }
            }
        }
        viewModelScope.launch {
            SensorRepository.skinTempFlow.collect { (ts, v) ->
                _isReceiving.value = true
                allSkinTempRaw.add(ts to v)
                _skinTempPoints.update { list -> (list + SensorPoint(ts, v, tsToX(ts))).takeLast(MAX_POINTS) }
            }
        }
        viewModelScope.launch {
            SensorRepository.ecgFlow.collect { (ts, v) ->
                _isReceiving.value = true
                allEcgRaw.add(ts to v)
                _ecgPoints.update { list -> (list + SensorPoint(ts, v,
                    tsToX(ts))).takeLast(MAX_POINTS) }
            }
        }
        viewModelScope.launch {
            SensorRepository.spo2Flow.collect { (ts, v) ->
                _isReceiving.value = true
                allSpo2Raw.add(ts to v)
                _spo2Points.update { list -> (list + SensorPoint(ts, v, tsToX(ts))).takeLast(MAX_POINTS) }
            }
        }
        viewModelScope.launch {
            SensorRepository.biaFatFlow.collect { (ts, v) ->
                _isReceiving.value = true
                allBiaFatRaw.add(ts to v)
                _biaFatPoints.update { list -> (list + SensorPoint(ts, v, tsToX(ts))).takeLast(MAX_POINTS) }
            }
        }
        viewModelScope.launch {
            SensorRepository.biaBmiFlow.collect { (ts, v) ->
                _isReceiving.value = true
                allBiaBmiRaw.add(ts to v)
                _biaBmiPoints.update { list -> (list + SensorPoint(ts, v, tsToX(ts))).takeLast(MAX_POINTS) }
            }
        }
        viewModelScope.launch {
            SensorRepository.biaMuscleFlow.collect { (ts, v) ->
                _isReceiving.value = true
                allBiaMuscleRaw.add(ts to v)
                _biaMusclePoints.update { list -> (list + SensorPoint(ts, v, tsToX(ts))).takeLast(MAX_POINTS) }
            }
        }
        viewModelScope.launch {
            SensorRepository.biaWaterFlow.collect { (ts, v) ->
                _isReceiving.value = true
                allBiaWaterRaw.add(ts to v)
                _biaWaterPoints.update { list -> (list + SensorPoint(ts, v, tsToX(ts))).takeLast(MAX_POINTS) }
            }
        }
        viewModelScope.launch {
            SensorRepository.sweatLossFlow.collect { (ts, v) ->
                _isReceiving.value = true
                allSweatLossRaw.add(ts to v)
                _sweatLossPoints.update { list -> (list + SensorPoint(ts, v, tsToX(ts))).takeLast(MAX_POINTS) }
            }
        }
    }

    /** 차트 및 누적 데이터를 모두 초기화한다. */
    fun clearAll() {
        _hrPoints.value = emptyList(); _ppgPoints.value = emptyList()
        _ppgIrPoints.value = emptyList(); _ppgRedPoints.value = emptyList()
        _edaPoints.value = emptyList()
        _accelXPoints.value = emptyList(); _accelYPoints.value = emptyList(); _accelZPoints.value = emptyList()
        _skinTempPoints.value = emptyList()
        _ecgPoints.value = emptyList()
        _spo2Points.value = emptyList()
        _biaFatPoints.value = emptyList(); _biaBmiPoints.value = emptyList()
        _biaMusclePoints.value = emptyList(); _biaWaterPoints.value = emptyList()
        _sweatLossPoints.value = emptyList()
        allHrRaw.clear(); allPpgRaw.clear(); allPpgIrRaw.clear(); allPpgRedRaw.clear()
        allEdaRaw.clear(); allAccelXRaw.clear(); allAccelYRaw.clear(); allAccelZRaw.clear()
        allSkinTempRaw.clear(); allEcgRaw.clear()
        allSpo2Raw.clear()
        allBiaFatRaw.clear(); allBiaBmiRaw.clear()
        allBiaMuscleRaw.clear(); allBiaWaterRaw.clear()
        allSweatLossRaw.clear()
        _isReceiving.value = false
        startTimestamp = 0L
    }

    /** SensorPoint 리스트를 MPAndroidChart Entry 리스트로 변환한다. */
    fun toMpEntries(points: List<SensorPoint>): List<Entry> =
        points.map { p -> Entry(p.index, p.value) }
}
