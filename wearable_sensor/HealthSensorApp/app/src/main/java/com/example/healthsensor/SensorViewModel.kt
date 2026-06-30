/**
 * SensorViewModel.kt — 폰 앱 차트 데이터 및 CSV 원시 데이터 관리
 *
 * SensorRepository의 Flow를 구독하고 Compose UI에 필요한 상태를 제공한다.
 * - 차트용: 최근 MAX_POINTS(500)개 포인트를 슬라이딩 윈도우로 유지
 * - CSV용: 세션 시작부터 모든 원시 데이터를 누적 저장
 *
 * 기본 채널: HR, PPG Green/IR/Red, EDA, Accel X/Y/Z, Skin Temp
 *
 * timestamp 기반 공통 x축:
 *   모든 센서가 같은 워치 timestamp 기준으로 표시되도록 100ms 단위 x축을 쓴다.
 *   서버 전송은 최근 10초 원시 샘플을 1초마다 POST한다.
 */
package com.example.healthsensor

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.github.mikephil.charting.data.Entry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 차트 한 점: 시작 시각으로부터의 경과 시간(초, Float)을 x축으로 사용한다. */
data class SensorPoint(val timestamp: Long, val value: Float, val index: Float = 0f)

class SensorViewModel(application: Application) : AndroidViewModel(application) {

    private val MAX_POINTS = 500   // 차트에 표시할 최대 포인트 수
    private val SERVER_WINDOW_MS = 10_000L
    private val SERVER_UPLOAD_INTERVAL_MS = 1_000L

    // 서버 모델 입력 계약: PPG는 25Hz fixed grid, EDA는 1Hz fixed grid로 맞춘다.
    // timestamp를 맞추는 것이 목적이므로 샘플 gap이 있어도 전송을 멈추지 않고 값을 채운다.
    private val PPG_SAMPLE_INTERVAL_MS = 40L
    private val PPG_SAMPLE_COUNT = 250
    private val EDA_SAMPLE_INTERVAL_MS = 1_000L
    private val EDA_SAMPLE_COUNT = 10
    private val AUTO_COMMUNICATION_DELAY_MS = 10_000L
    private val PREDICTION_RECONNECT_DELAY_MS = 2_000L
    private val serverConfig = ServerConfig.load(application)
    private val serverUploader = ServerUploader()
    private val predictionSender = PhonePredictionSender(application)
    private val sessionStartedAtMs = System.currentTimeMillis()
    private var uploadSequence = 0L
    private var lastPayloadSkipReason = "수신 샘플 없음"
    private var autoCommunicationStarted = false
    private var autoCommunicationJob: Job? = null
    private var predictionReceiverJob: Job? = null

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

    private val _serverUrl = MutableStateFlow(serverConfig.sensorPostUrl)
    val serverUrl: StateFlow<String> = _serverUrl

    private val _isUploadEnabled = MutableStateFlow(false)
    val isUploadEnabled: StateFlow<Boolean> = _isUploadEnabled

    private val _uploadStatus = MutableStateFlow("서버 URL 입력 후 전송을 시작하세요")
    val uploadStatus: StateFlow<String> = _uploadStatus

    private val _predictionUrl = MutableStateFlow(serverConfig.predictionSseUrl)
    val predictionUrl: StateFlow<String> = _predictionUrl

    private val _isPredictionReceiverEnabled = MutableStateFlow(false)
    val isPredictionReceiverEnabled: StateFlow<Boolean> = _isPredictionReceiverEnabled

    private val _predictionStatus = MutableStateFlow("Prediction SSE URL 입력 후 수신을 시작하세요")
    val predictionStatus: StateFlow<String> = _predictionStatus

    private val _latestPrediction = MutableStateFlow<CravingPrediction?>(null)
    val latestPrediction: StateFlow<CravingPrediction?> = _latestPrediction

    private val _cravingClassPoints = MutableStateFlow<List<SensorPoint>>(emptyList())
    val cravingClassPoints: StateFlow<List<SensorPoint>> = _cravingClassPoints

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
    val allCravingClassRaw = mutableListOf<Pair<Long, Float>>()

    // 첫 샘플 수신 시각 — 모든 센서의 공통 x축 기준점 (100ms 단위)
    // 센서마다 독립 카운터를 쓰면 배치 크기 차이로 스케일이 달라지므로 타임스탬프 기반 공통축 사용
    @Volatile private var startTimestamp: Long = 0L

    private fun tsToX(ts: Long): Float {
        if (startTimestamp == 0L) {
            startTimestamp = ts
            scheduleAutoCommunicationStart()
        }
        return ((ts - startTimestamp) / 100f)   // 100ms 단위 (x=10 → 1초 경과)
    }

    private fun predictionToX(ts: Long): Float =
        if (startTimestamp == 0L) 0f else ((ts - startTimestamp) / 100f)

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
        startServerUploadLoop()
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
        allCravingClassRaw.clear()
        _isReceiving.value = false
        startTimestamp = 0L
        uploadSequence = 0L
        _latestPrediction.value = null
        _cravingClassPoints.value = emptyList()
        autoCommunicationJob?.cancel()
        autoCommunicationJob = null
        autoCommunicationStarted = false
    }

    /** SensorPoint 리스트를 MPAndroidChart Entry 리스트로 변환한다. */
    fun toMpEntries(points: List<SensorPoint>): List<Entry> =
        points.map { p -> Entry(p.index, p.value) }

    fun updateServerUrl(url: String) {
        _serverUrl.value = url.trim()
        if (autoCommunicationStarted && _serverUrl.value.isNotBlank() && !_isUploadEnabled.value) {
            setUploadEnabled(true)
        }
    }

    fun setUploadEnabled(enabled: Boolean) {
        if (enabled && _serverUrl.value.isBlank()) {
            _uploadStatus.value = "서버 URL이 비어 있습니다"
            _isUploadEnabled.value = false
            return
        }
        _isUploadEnabled.value = enabled
        _uploadStatus.value = if (enabled) "전송 대기 중" else "전송 중지됨"
    }

    fun updatePredictionUrl(url: String) {
        _predictionUrl.value = url.trim()
        if (_isPredictionReceiverEnabled.value) {
            startPredictionReceiver()
        } else if (autoCommunicationStarted && _predictionUrl.value.isNotBlank()) {
            setPredictionReceiverEnabled(true)
        }
    }

    fun setPredictionReceiverEnabled(enabled: Boolean) {
        if (enabled && _predictionUrl.value.isBlank()) {
            _predictionStatus.value = "Prediction SSE URL이 비어 있습니다"
            _isPredictionReceiverEnabled.value = false
            return
        }
        _isPredictionReceiverEnabled.value = enabled
        if (enabled) {
            startPredictionReceiver()
        } else {
            predictionReceiverJob?.cancel()
            predictionReceiverJob = null
            _predictionStatus.value = "예측 수신 중지됨"
        }
    }

    /**
     * 센서 취득이 시작된 뒤 10초가 지나면 통신을 자동으로 켠다.
     *
     * 첫 10초는 서버 모델에 필요한 window를 확보하는 준비 구간이다. URL이 비어 있으면
     * 사용자가 나중에 입력할 수 있도록 상태만 보류로 바꾸고, updateServerUrl/updatePredictionUrl에서
     * 이어서 시작한다.
     */
    private fun scheduleAutoCommunicationStart() {
        if (autoCommunicationJob != null || autoCommunicationStarted) return

        _uploadStatus.value = "취득 시작 감지: 10초 후 자동 전송"
        _predictionStatus.value = "취득 시작 감지: 10초 후 자동 수신"
        autoCommunicationJob = viewModelScope.launch {
            delay(AUTO_COMMUNICATION_DELAY_MS)
            autoCommunicationStarted = true

            if (_serverUrl.value.isNotBlank()) {
                if (!_isUploadEnabled.value) setUploadEnabled(true)
                _uploadStatus.value = "자동 전송 시작: 취득 10초 경과"
            } else {
                _uploadStatus.value = "자동 전송 보류: 서버 POST URL이 비어 있음"
            }

            if (_predictionUrl.value.isNotBlank()) {
                if (!_isPredictionReceiverEnabled.value) setPredictionReceiverEnabled(true)
            } else {
                _predictionStatus.value = "자동 수신 보류: Prediction SSE URL이 비어 있음"
            }
        }
    }

    /** 서버에서 받은 class를 폰 차트와 CSV에 남긴다. 워치 리포트와 독립적으로 누적된다. */
    private fun appendCravingClassPoint(prediction: CravingPrediction) {
        val value = prediction.cravingClass.toFloat()
        allCravingClassRaw.add(prediction.timestampMs to value)
        _cravingClassPoints.update { list ->
            (list + SensorPoint(prediction.timestampMs, value, predictionToX(prediction.timestampMs)))
                .takeLast(MAX_POINTS)
        }
    }

    /** 1초마다 최신 10초 window를 만들어 서버에 POST한다. */
    private fun startServerUploadLoop() {
        viewModelScope.launch {
            while (true) {
                delay(SERVER_UPLOAD_INTERVAL_MS)
                if (!_isUploadEnabled.value) continue

                val url = _serverUrl.value
                val payload = buildServerPayload()
                if (payload == null) {
                    _uploadStatus.value = "전송 대기: $lastPayloadSkipReason"
                    continue
                }

                _uploadStatus.value = "전송 중: ${payload.samples.size} samples"
                val result = runCatching {
                    withContext(Dispatchers.IO) {
                        serverUploader.postWindow(url, payload)
                    }
                }.getOrElse { e ->
                    UploadResult(false, -1, e.message ?: "unknown error")
                }

                _uploadStatus.value = if (result.success) {
                    "전송 완료: #${payload.sequence}, ${payload.samples.size} samples"
                } else {
                    "전송 실패: ${result.statusCode} ${result.message}"
                }
            }
        }
    }

    /**
     * 서버 SSE stream을 장기 연결로 유지한다.
     * stream이 닫히거나 timeout되면 2초 뒤 재연결하여 예측 수신을 계속한다.
     */
    private fun startPredictionReceiver() {
        predictionReceiverJob?.cancel()
        predictionReceiverJob = viewModelScope.launch {
            while (isActive && _isPredictionReceiverEnabled.value) {
                val url = _predictionUrl.value
                val activeJob = coroutineContext[Job]
                _predictionStatus.value = "SSE 연결 중"

                val result = withContext(Dispatchers.IO) {
                    runCatching {
                        serverUploader.listenPredictions(
                            url = url,
                            shouldContinue = {
                                _isPredictionReceiverEnabled.value && activeJob?.isActive == true
                            }
                        ) { prediction ->
                            _latestPrediction.value = prediction
                            appendCravingClassPoint(prediction)
                            val sentToWatch = predictionSender.sendPrediction(prediction)
                            _predictionStatus.value = if (sentToWatch) {
                                "워치 리포트 완료: class ${prediction.label}"
                            } else {
                                "예측 수신됨, 워치 미연결: class ${prediction.label}"
                            }
                        }
                    }
                }

                if (!_isPredictionReceiverEnabled.value) break

                val error = result.exceptionOrNull()?.message ?: "stream closed"
                _predictionStatus.value = "SSE 재연결 대기: $error"
                delay(PREDICTION_RECONNECT_DELAY_MS)
            }
        }
    }

    override fun onCleared() {
        autoCommunicationJob?.cancel()
        predictionReceiverJob?.cancel()
        super.onCleared()
    }

    /**
     * 서버로 보낼 10초 payload를 만든다.
     *
     * PPG/EDA는 같은 windowStartMs 기준 grid에 맞춘다.
     * - PPG_*: 40ms 간격, 채널당 250개
     * - EDA: 1000ms 간격, 10개
     *
     * 원시 샘플이 target timestamp를 정확히 갖지 않아도 보간/hold로 채워 전송 주기를 유지한다.
     */
    private fun buildServerPayload(): ServerWindowPayload? {
        val windowEndMs = synchronizedWindowEnd() ?: return null
        val windowStartMs = windowEndMs - SERVER_WINDOW_MS
        val ppgGreenSamples = resampleFixedGrid(
            sensor = "PPG_GREEN",
            source = allPpgRaw,
            windowStartMs = windowStartMs,
            intervalMs = PPG_SAMPLE_INTERVAL_MS,
            count = PPG_SAMPLE_COUNT
        )
        val ppgIrSamples = resampleFixedGrid(
            sensor = "PPG_IR",
            source = allPpgIrRaw,
            windowStartMs = windowStartMs,
            intervalMs = PPG_SAMPLE_INTERVAL_MS,
            count = PPG_SAMPLE_COUNT
        )
        val ppgRedSamples = resampleFixedGrid(
            sensor = "PPG_RED",
            source = allPpgRedRaw,
            windowStartMs = windowStartMs,
            intervalMs = PPG_SAMPLE_INTERVAL_MS,
            count = PPG_SAMPLE_COUNT
        )
        val edaSamples = resampleFixedGrid(
            sensor = "EDA",
            source = allEdaRaw,
            windowStartMs = windowStartMs,
            intervalMs = EDA_SAMPLE_INTERVAL_MS,
            count = EDA_SAMPLE_COUNT
        )

        val samples = buildList {
            addWindowSamples("HR", allHrRaw, windowStartMs, windowEndMs)
            addAll(ppgGreenSamples)
            addAll(ppgIrSamples)
            addAll(ppgRedSamples)
            addAll(edaSamples)
            addWindowSamples("ACCEL_X", allAccelXRaw, windowStartMs, windowEndMs)
            addWindowSamples("ACCEL_Y", allAccelYRaw, windowStartMs, windowEndMs)
            addWindowSamples("ACCEL_Z", allAccelZRaw, windowStartMs, windowEndMs)
            addWindowSamples("SKIN_TEMP", allSkinTempRaw, windowStartMs, windowEndMs)
        }.sortedBy { it.timestampMs }

        if (samples.isEmpty()) return null
        uploadSequence += 1
        return ServerWindowPayload(
            sessionStartedAtMs = sessionStartedAtMs,
            sequence = uploadSequence,
            sentAtMs = System.currentTimeMillis(),
            windowStartMs = windowStartMs,
            windowEndMs = windowEndMs,
            windowMs = SERVER_WINDOW_MS,
            samples = samples,
            sync = ServerWindowSync(
                mode = "fixed_grid_ppg_25hz_eda_1hz_continuous",
                fillMode = "linear_interpolation_nearest_edge_hold",
                ppgHz = 25,
                ppgSamplesPerChannel = PPG_SAMPLE_COUNT,
                edaHz = 1,
                edaSamples = EDA_SAMPLE_COUNT
            )
        )
    }

    /**
     * fixed-grid payload의 windowEnd를 정한다.
     * 여러 센서 중 최신 timestamp를 1초 경계로 내림해서 window가 안정적으로 전진하게 한다.
     */
    private fun synchronizedWindowEnd(): Long? {
        if (allPpgRaw.isEmpty() || allPpgIrRaw.isEmpty() || allPpgRedRaw.isEmpty() || allEdaRaw.isEmpty()) {
            lastPayloadSkipReason = "PPG/EDA 첫 샘플 대기 중"
            return null
        }

        val latest = latestTimestamp() ?: return null
        return latest - (latest % EDA_SAMPLE_INTERVAL_MS)
    }

    /**
     * 원시 샘플을 target timestamp grid로 재샘플링한다.
     *
     * 양쪽 샘플이 가까우면 선형 보간하고, window 가장자리나 큰 gap에서는 가까운 값/마지막 값을
     * 유지한다. 이 정책 덕분에 일시적인 센서 callback 지연이 있어도 POST가 끊기지 않는다.
     */
    private fun resampleFixedGrid(
        sensor: String,
        source: List<Pair<Long, Float>>,
        windowStartMs: Long,
        intervalMs: Long,
        count: Int
    ): List<ServerSensorSample> {
        val sorted = source.asSequence()
            .distinctBy { it.first }
            .sortedBy { it.first }
            .toList()
        if (sorted.isEmpty()) return emptyList()

        val samples = ArrayList<ServerSensorSample>(count)
        var cursor = 0
        repeat(count) { index ->
            val targetTs = windowStartMs + index * intervalMs
            while (cursor < sorted.lastIndex && sorted[cursor + 1].first <= targetTs) {
                cursor += 1
            }

            val before = sorted[cursor]
            val after = sorted.getOrNull(cursor + 1)
            val value = when {
                before.first == targetTs -> before.second
                before.first > targetTs -> before.second
                after == null -> before.second
                after.first == before.first -> before.second
                after.first - before.first > intervalMs * 3 -> {
                    val beforeDistance = targetTs - before.first
                    val afterDistance = after.first - targetTs
                    if (beforeDistance <= afterDistance) before.second else after.second
                }
                else -> {
                    val ratio = (targetTs - before.first).toFloat() / (after.first - before.first).toFloat()
                    before.second + (after.second - before.second) * ratio
                }
            }
            samples += ServerSensorSample(sensor, targetTs, value)
        }
        return samples
    }

    private fun MutableList<ServerSensorSample>.addWindowSamples(
        sensor: String,
        source: List<Pair<Long, Float>>,
        windowStartMs: Long,
        windowEndMs: Long
    ) {
        source.asReversed().asSequence()
            .takeWhile { (ts, _) -> ts >= windowStartMs }
            .filter { (ts, _) -> ts <= windowEndMs }
            .toList()
            .asReversed()
            .forEach { (ts, value) -> add(ServerSensorSample(sensor, ts, value)) }
    }

    private fun latestTimestamp(): Long? =
        listOfNotNull(
            allHrRaw.lastOrNull()?.first,
            allPpgRaw.lastOrNull()?.first,
            allPpgIrRaw.lastOrNull()?.first,
            allPpgRedRaw.lastOrNull()?.first,
            allEdaRaw.lastOrNull()?.first,
            allAccelXRaw.lastOrNull()?.first,
            allAccelYRaw.lastOrNull()?.first,
            allAccelZRaw.lastOrNull()?.first,
            allSkinTempRaw.lastOrNull()?.first
        ).maxOrNull()
}
