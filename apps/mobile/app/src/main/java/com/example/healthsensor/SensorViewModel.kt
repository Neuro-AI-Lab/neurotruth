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
import android.content.Context
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
import org.json.JSONObject
import java.net.SocketTimeoutException

/** 차트 한 점: 시작 시각으로부터의 경과 시간(초, Float)을 x축으로 사용한다. */
data class SensorPoint(val timestamp: Long, val value: Float, val index: Float = 0f)

data class StateCheckQuestion(
    val number: Int,
    val text: String,
    val reverseScored: Boolean = false
)

data class StateCheckResult(
    val timestampMs: Long,
    val triggerClass: Int?,
    val responses: List<Int>,
    val scoredItems: List<Int>,
    val rawTotalScore: Int,
    val rawMeanScore: Float,
    val totalScore: Int,
    val meanScore: Float
)

object StateCheckScoring {
    private const val MIN_RESPONSE = 1
    const val MAX_RESPONSE = 7

    fun isValidResponse(value: Int): Boolean = value in MIN_RESPONSE..MAX_RESPONSE

    fun correctedScore(rawScore: Int, reverseScored: Boolean): Int {
        require(isValidResponse(rawScore)) { "response must be between 1 and 7" }
        return if (reverseScored) MIN_RESPONSE + MAX_RESPONSE - rawScore else rawScore
    }

    fun buildResult(
        questions: List<StateCheckQuestion>,
        responsesByQuestion: Map<Int, Int>,
        timestampMs: Long,
        triggerClass: Int?
    ): StateCheckResult? {
        if (questions.isEmpty()) return null
        if (questions.any { question ->
                responsesByQuestion[question.number]?.let(::isValidResponse) != true
            }
        ) return null

        val responses = questions.map { question -> responsesByQuestion.getValue(question.number) }
        val scoredItems = questions.map { question ->
            correctedScore(responsesByQuestion.getValue(question.number), question.reverseScored)
        }
        val rawTotal = responses.sum()
        val scoredTotal = scoredItems.sum()
        return StateCheckResult(
            timestampMs = timestampMs,
            triggerClass = triggerClass,
            responses = responses,
            scoredItems = scoredItems,
            rawTotalScore = rawTotal,
            rawMeanScore = rawTotal / questions.size.toFloat(),
            totalScore = scoredTotal,
            meanScore = scoredTotal / questions.size.toFloat()
        )
    }
}

data class ChatMessage(
    val sender: ChatSender,
    val text: String,
    val timestampMs: Long = System.currentTimeMillis()
)

enum class ChatSender {
    USER,
    BOT
}

object ChatReadTimeoutPolicy {
    const val DEFAULT_MINUTES = 60
    const val MIN_MINUTES = 1
    const val MAX_MINUTES = 1_440

    fun isValid(minutes: Int): Boolean = minutes in MIN_MINUTES..MAX_MINUTES

    fun storedOrDefault(minutes: Int): Int =
        if (isValid(minutes)) minutes else DEFAULT_MINUTES

    fun toMillis(minutes: Int): Int {
        require(isValid(minutes)) { "chat timeout must be between 1 and 1440 minutes" }
        return minutes * 60_000
    }
}

enum class OptionalAuqChoice { COMPLETE, SKIP }

object OptionalAuqPolicy {
    fun shouldPostAssessment(choice: OptionalAuqChoice): Boolean = choice == OptionalAuqChoice.COMPLETE
}

private const val INTERVENTION_PREFS = "intervention_settings"
private const val PREF_CHAT_READ_TIMEOUT_MINUTES = "chat_read_timeout_minutes"

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
    private val serverConfig = ServerConfig.load(application).also { PhoneMonitoringState.ensureConfig(it) }
    private val serverUploader = ServerUploader()
    private val apiClient = MobileApiProvider.get(application)
    private val windowIdStore = StableClientWindowIdStore.from(application)
    private val predictionSender = PhonePredictionSender(application)
    private val alertNotifier = CravingAlertNotifier(application)
    private var uploadSequence = 0L
    private var lastPayloadSkipReason = "수신 샘플 없음"
    private var autoCommunicationStarted = false
    private var autoCommunicationJob: Job? = null
    private var predictionReceiverJob: Job? = null
    private var pendingUpload: ServerWindowPayload? = null
    private var cravingSimulationJob: Job? = null
    private var observedPredictionKey: String? = null
    private var observedAlertActionVersion = 0L
    private var chatRequestJob: Job? = null
    private var interventionEpoch = 0L
    private var bufferedSessionPrompt: String? = null
    private val seenInterventionIds = mutableSetOf<String>()
    private val interventionPreferences = application.getSharedPreferences(
        INTERVENTION_PREFS,
        Context.MODE_PRIVATE
    )

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

    private val _serverUrl = PhoneMonitoringState.serverUrl
    val serverUrl: StateFlow<String> = _serverUrl

    private val _isUploadEnabled = PhoneMonitoringState.isUploadEnabled
    val isUploadEnabled: StateFlow<Boolean> = _isUploadEnabled

    private val _uploadStatus = PhoneMonitoringState.uploadStatus
    val uploadStatus: StateFlow<String> = _uploadStatus

    private val _predictionUrl = PhoneMonitoringState.predictionUrl
    val predictionUrl: StateFlow<String> = _predictionUrl

    private val _isPredictionReceiverEnabled = PhoneMonitoringState.isPredictionReceiverEnabled
    val isPredictionReceiverEnabled: StateFlow<Boolean> = _isPredictionReceiverEnabled

    private val _predictionStatus = PhoneMonitoringState.predictionStatus
    val predictionStatus: StateFlow<String> = _predictionStatus

    private val _uploadLatencyPoints = PhoneMonitoringState.uploadLatencyPoints
    val uploadLatencyPoints: StateFlow<List<SensorPoint>> = _uploadLatencyPoints

    private val _predictionLatencyPoints = PhoneMonitoringState.predictionLatencyPoints
    val predictionLatencyPoints: StateFlow<List<SensorPoint>> = _predictionLatencyPoints

    private val _uploadLatencyStatus = PhoneMonitoringState.uploadLatencyStatus
    val uploadLatencyStatus: StateFlow<String> = _uploadLatencyStatus

    private val _predictionLatencyStatus = PhoneMonitoringState.predictionLatencyStatus
    val predictionLatencyStatus: StateFlow<String> = _predictionLatencyStatus

    private val _latestPrediction = PhoneMonitoringState.latestPrediction
    val latestPrediction: StateFlow<CravingPrediction?> = _latestPrediction

    private val _isBackgroundServiceRunning = PhoneMonitoringState.isServiceRunning
    val isBackgroundServiceRunning: StateFlow<Boolean> = _isBackgroundServiceRunning

    private val _backgroundServiceStatus = PhoneMonitoringState.serviceStatus
    val backgroundServiceStatus: StateFlow<String> = _backgroundServiceStatus

    private val _cravingClassPoints = MutableStateFlow<List<SensorPoint>>(emptyList())
    val cravingClassPoints: StateFlow<List<SensorPoint>> = _cravingClassPoints

    private val _isStateCheckRequired = MutableStateFlow(false)
    val isStateCheckRequired: StateFlow<Boolean> = _isStateCheckRequired

    private val _isTalkChoiceRequired = MutableStateFlow(false)
    val isTalkChoiceRequired: StateFlow<Boolean> = _isTalkChoiceRequired

    private val _isAuqChoiceRequired = MutableStateFlow(false)
    val isAuqChoiceRequired: StateFlow<Boolean> = _isAuqChoiceRequired

    private val _stateCheckResponses = MutableStateFlow<Map<Int, Int>>(emptyMap())
    val stateCheckResponses: StateFlow<Map<Int, Int>> = _stateCheckResponses

    private val _latestStateCheckResult = MutableStateFlow<StateCheckResult?>(null)
    val latestStateCheckResult: StateFlow<StateCheckResult?> = _latestStateCheckResult

    private val _isChatVisible = MutableStateFlow(false)
    val isChatVisible: StateFlow<Boolean> = _isChatVisible

    private val _chatMessages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val chatMessages: StateFlow<List<ChatMessage>> = _chatMessages

    private val _chatStatus = MutableStateFlow("필요할 때 대화를 시작할 수 있습니다")
    val chatStatus: StateFlow<String> = _chatStatus

    private val _isChatSending = MutableStateFlow(false)
    val isChatSending: StateFlow<Boolean> = _isChatSending

    private val _chatReadTimeoutMinutes = MutableStateFlow(
        ChatReadTimeoutPolicy.storedOrDefault(
            interventionPreferences.getInt(
                PREF_CHAT_READ_TIMEOUT_MINUTES,
                ChatReadTimeoutPolicy.DEFAULT_MINUTES
            )
        )
    )
    val chatReadTimeoutMinutes: StateFlow<Int> = _chatReadTimeoutMinutes

    private val conversationSessionManager = ConversationSessionManager(
        api = AuthenticatedSessionApi(apiClient),
        store = SharedPreferencesConversationSessionStore(application),
        timeoutMs = { ChatReadTimeoutPolicy.toMillis(_chatReadTimeoutMinutes.value).toLong() }
    )

    private val _chatTimeoutSettingStatus = MutableStateFlow("")
    val chatTimeoutSettingStatus: StateFlow<String> = _chatTimeoutSettingStatus

    private val _conversationPhase = MutableStateFlow("safety_check")
    val conversationPhase: StateFlow<String> = _conversationPhase

    private val _sessionReportStatus = MutableStateFlow("not_started")
    val sessionReportStatus: StateFlow<String> = _sessionReportStatus

    private val _sessionInactivityTimeoutSeconds = MutableStateFlow<Int?>(null)
    val sessionInactivityTimeoutSeconds: StateFlow<Int?> = _sessionInactivityTimeoutSeconds

    private val _isCravingSimulationRunning = MutableStateFlow(false)
    val isCravingSimulationRunning: StateFlow<Boolean> = _isCravingSimulationRunning

    private val _simulationStatus = MutableStateFlow("테스트 대기")
    val simulationStatus: StateFlow<String> = _simulationStatus

    // 실제 임상/연구 사용 시에는 승인된 상태 확인 문항 전문으로 교체해야 한다.
    val stateCheckQuestions: List<StateCheckQuestion> = listOf(
        StateCheckQuestion(1, "지금 음주 충동이 느껴진다."),
        StateCheckQuestion(2, "지금 술을 마시지 않고 지나가기 어렵다고 느낀다."),
        StateCheckQuestion(3, "술 생각이 머릿속에서 쉽게 떠나지 않는다."),
        StateCheckQuestion(4, "술을 마시면 현재 불편함이 줄어들 것 같다."),
        StateCheckQuestion(5, "지금 술을 구하거나 마시고 싶은 마음이 강하다."),
        StateCheckQuestion(6, "술과 관련된 자극에 끌리는 느낌이 있다."),
        StateCheckQuestion(7, "지금은 음주 욕구를 조절하기 어렵다고 느낀다."),
        StateCheckQuestion(8, "이 순간 술을 마시고 싶다는 갈망이 있다.")
    )

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
    val allStateCheckResults = mutableListOf<StateCheckResult>()

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

    private fun predictionToX(ts: Long): Float {
        if (startTimestamp == 0L) {
            startTimestamp = ts
        }
        return ((ts - startTimestamp) / 100f)
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
        viewModelScope.launch {
            PhoneMonitoringState.latestPrediction.collect { prediction ->
                prediction ?: return@collect
                val key = "${prediction.timestampMs}:${prediction.cravingClass}:${prediction.rawBody.hashCode()}"
                if (key != observedPredictionKey) {
                    observedPredictionKey = key
                    recordPredictionForUi(prediction)
                }
            }
        }
        viewModelScope.launch {
            MobileAuthRuntime.state.collect { state ->
                if (!state.canUploadBiosignal) {
                    _isUploadEnabled.value = false
                    _uploadStatus.value = "생체신호 수집 동의가 철회되어 전송이 중지되었습니다"
                }
                if (!state.canReceiveAiPrediction) {
                    _isPredictionReceiverEnabled.value = false
                    predictionReceiverJob?.cancel()
                    predictionReceiverJob = null
                    _predictionStatus.value = "AI 분석 동의가 없어 예측 수신이 중지되었습니다"
                }
                if (state.canUploadBiosignal) {
                    PhoneMonitoringService.start(application)
                } else {
                    PhoneMonitoringService.stop(application)
                }
                if (!state.authenticated) {
                    chatRequestJob?.cancel()
                    conversationSessionManager.clear()
                    _isChatSending.value = false
                    _isChatVisible.value = false
                    _isTalkChoiceRequired.value = false
                    _isAuqChoiceRequired.value = false
                    PhoneMonitoringState.closeIntervention()
                }
            }
        }
    }

    /** 차트 및 누적 데이터를 모두 초기화한다. */
    fun clearAll() {
        cravingSimulationJob?.cancel()
        cravingSimulationJob = null
        PhoneMonitoringState.startNewSession()
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
        allStateCheckResults.clear()
        _isReceiving.value = false
        startTimestamp = 0L
        uploadSequence = 0L
        pendingUpload = null
        _cravingClassPoints.value = emptyList()
        resetInterventionState()
        observedPredictionKey = null
        observedAlertActionVersion = 0L
        autoCommunicationJob?.cancel()
        autoCommunicationJob = null
        autoCommunicationStarted = false
        _isCravingSimulationRunning.value = false
        _simulationStatus.value = "테스트 대기"
        PhoneMonitoringState.clearLatency()
    }

    /** SensorPoint 리스트를 MPAndroidChart Entry 리스트로 변환한다. */
    fun toMpEntries(points: List<SensorPoint>): List<Entry> =
        points.map { p -> Entry(p.index, p.value) }

    fun updateServerUrl(url: String) {
        _serverUrl.value = url.trim()
        PhoneMonitoringService.start(getApplication<Application>())
        if (autoCommunicationStarted && _serverUrl.value.isNotBlank() && !_isUploadEnabled.value) {
            setUploadEnabled(true)
        }
    }

    fun setUploadEnabled(enabled: Boolean) {
        if (enabled && !MobileAuthRuntime.canUpload()) {
            _uploadStatus.value = "로그인과 생체신호 수집 동의가 필요합니다"
            _isUploadEnabled.value = false
            return
        }
        if (enabled && _serverUrl.value.isBlank()) {
            _uploadStatus.value = "서버 URL이 비어 있습니다"
            _isUploadEnabled.value = false
            return
        }
        _isUploadEnabled.value = enabled
        _uploadStatus.value = if (enabled) "전송 대기 중" else "전송 중지됨"
        PhoneMonitoringService.start(getApplication<Application>())
    }

    fun updatePredictionUrl(url: String) {
        _predictionUrl.value = url.trim()
        PhoneMonitoringService.start(getApplication<Application>())
        if (autoCommunicationStarted && _predictionUrl.value.isNotBlank() && !_isPredictionReceiverEnabled.value) {
            setPredictionReceiverEnabled(true)
        }
    }

    fun setPredictionReceiverEnabled(enabled: Boolean) {
        if (enabled && !MobileAuthRuntime.canReceivePredictions()) {
            _predictionStatus.value = "로그인, 생체신호 수집 및 AI 분석 동의가 필요합니다"
            _isPredictionReceiverEnabled.value = false
            return
        }
        if (enabled && _predictionUrl.value.isBlank()) {
            _predictionStatus.value = "예측 수신 URL이 비어 있습니다"
            _isPredictionReceiverEnabled.value = false
            return
        }
        _isPredictionReceiverEnabled.value = enabled
        if (enabled) {
            _predictionStatus.value = "백그라운드 수신 대기 중"
            PhoneMonitoringService.start(getApplication<Application>())
        } else {
            predictionReceiverJob?.cancel()
            predictionReceiverJob = null
            _predictionStatus.value = "예측 수신 중지됨"
        }
    }

    fun updateStateCheckResponse(questionNumber: Int, value: Int) {
        if (questionNumber !in 1..stateCheckQuestions.size || !StateCheckScoring.isValidResponse(value)) return
        _stateCheckResponses.update { current -> current + (questionNumber to value) }
    }

    fun submitStateCheckResponses() {
        val responsesByQuestion = _stateCheckResponses.value
        val result = StateCheckScoring.buildResult(
            questions = stateCheckQuestions,
            responsesByQuestion = responsesByQuestion,
            timestampMs = System.currentTimeMillis(),
            triggerClass = _latestPrediction.value?.cravingClass
        ) ?: return

        allStateCheckResults.add(result)
        _latestStateCheckResult.value = result
        _isStateCheckRequired.value = false
        PhoneMonitoringState.markStateCheckSubmitted(result.timestampMs)
        viewModelScope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    if (OptionalAuqPolicy.shouldPostAssessment(OptionalAuqChoice.COMPLETE)) {
                        conversationSessionManager.postAssessment(authenticatedOwnerId(), result)
                    }
                }
            }.onSuccess {
                openBufferedConversation("AUQ를 저장했습니다. 대화를 이어갈 수 있습니다")
            }.onFailure { error ->
                _chatStatus.value = "AUQ 저장 실패: ${safeSessionError(error)}"
            }
        }
    }

    fun chooseTalkNow() {
        if (_isChatSending.value) return
        _isTalkChoiceRequired.value = false
        _isChatSending.value = true
        _chatStatus.value = "대화를 준비하고 있습니다"
        viewModelScope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    conversationSessionManager.start(
                        ownerId = authenticatedOwnerId(),
                        sessionType = "alert_checkin",
                        triggerAlertId = _latestPrediction.value?.alertId
                    )
                }
            }.onSuccess { session ->
                bufferedSessionPrompt = session.assistantText
                _conversationPhase.value = session.interactionPhase ?: "safety_check"
                _sessionReportStatus.value = session.reportStatus
                _sessionInactivityTimeoutSeconds.value = session.inactivityTimeoutSeconds
                _isAuqChoiceRequired.value = true
                _chatStatus.value = "AUQ는 선택 사항입니다"
            }.onFailure {
                _chatStatus.value = "세션 시작 실패: ${safeSessionError(it)}"
                _isTalkChoiceRequired.value = true
            }
            _isChatSending.value = false
        }
    }

    fun chooseTalkLater() {
        _isTalkChoiceRequired.value = false
        _chatStatus.value = "원할 때 홈에서 다시 대화를 시작할 수 있습니다"
    }

    fun chooseAuqForm() {
        _isAuqChoiceRequired.value = false
        _stateCheckResponses.value = emptyMap()
        _isStateCheckRequired.value = true
    }

    fun skipAuqAndTalk() {
        if (!OptionalAuqPolicy.shouldPostAssessment(OptionalAuqChoice.SKIP)) {
            _isAuqChoiceRequired.value = false
            openBufferedConversation("AUQ를 건너뛰었습니다. 모든 대화 기능은 그대로 사용할 수 있습니다")
        }
    }

    fun openChat() {
        if (_isChatVisible.value) return
        viewModelScope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    conversationSessionManager.ensure(authenticatedOwnerId())
                }
            }.onSuccess { session ->
                bufferedSessionPrompt = session.assistantText
                _conversationPhase.value = session.interactionPhase ?: "safety_check"
                _sessionReportStatus.value = session.reportStatus
                _sessionInactivityTimeoutSeconds.value = session.inactivityTimeoutSeconds
                if (session.assistantText != null) {
                    _isAuqChoiceRequired.value = true
                } else {
                    openBufferedConversation("기존 대화를 이어갑니다")
                }
            }.onFailure { _chatStatus.value = "세션 시작 실패: ${safeSessionError(it)}" }
        }
    }

    fun closeChat() {
        _isChatVisible.value = false
        PhoneMonitoringState.closeIntervention()
    }

    fun applyChatReadTimeoutMinutes(minutes: Int): Boolean {
        if (!ChatReadTimeoutPolicy.isValid(minutes)) {
            _chatTimeoutSettingStatus.value = "1~1,440분 사이의 값을 입력하세요"
            return false
        }
        interventionPreferences.edit()
            .putInt(PREF_CHAT_READ_TIMEOUT_MINUTES, minutes)
            .apply()
        _chatReadTimeoutMinutes.value = minutes
        _chatTimeoutSettingStatus.value = "채팅 응답 제한시간을 ${minutes}분으로 저장했습니다"
        return true
    }

    fun resetInterventionState() {
        interventionEpoch += 1L
        chatRequestJob?.cancel()
        chatRequestJob = null
        PhoneMonitoringState.closeIntervention()
        PhoneMonitoringState.resetStateCheckCooldown()
        _isStateCheckRequired.value = false
        _isTalkChoiceRequired.value = false
        _isAuqChoiceRequired.value = false
        _stateCheckResponses.value = emptyMap()
        _latestStateCheckResult.value = null
        allStateCheckResults.clear()
        _isChatVisible.value = false
        _chatMessages.value = emptyList()
        _chatStatus.value = "필요할 때 대화를 시작할 수 있습니다"
        _isChatSending.value = false
        _conversationPhase.value = "safety_check"
        _sessionReportStatus.value = "not_started"
        _sessionInactivityTimeoutSeconds.value = null
        bufferedSessionPrompt = null
        seenInterventionIds.clear()
    }

    fun sendChatMessage(text: String) {
        val message = text.trim()
        if (message.isBlank() || _isChatSending.value) return
        _chatMessages.update { list -> list + ChatMessage(ChatSender.USER, message) }
        _isChatSending.value = true
        _chatStatus.value = "응답 요청 중"

        val requestEpoch = interventionEpoch
        chatRequestJob = viewModelScope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    conversationSessionManager.postMessage(
                        ownerId = authenticatedOwnerId(),
                        content = message,
                        readTimeoutMs = ChatReadTimeoutPolicy.toMillis(_chatReadTimeoutMinutes.value)
                    )
                }
            }

            if (requestEpoch != interventionEpoch) return@launch

            result.onSuccess { response ->
                _conversationPhase.value = response.phase
                _sessionReportStatus.value = response.reportStatus
                response.inactivityTimeoutSeconds?.let { _sessionInactivityTimeoutSeconds.value = it }
                val newInterventions = response.activeInterventions
                    .filter { seenInterventionIds.add(it.id) }
                    .map { it.content }
                val assistantMessage = listOfNotNull(
                    response.assistantText.takeIf(String::isNotBlank),
                    newInterventions.joinToString("\n\n").takeIf(String::isNotBlank)
                ).distinct().joinToString("\n\n")
                if (assistantMessage.isNotBlank()) {
                    _chatMessages.update { list -> list + ChatMessage(ChatSender.BOT, assistantMessage) }
                }
                _chatStatus.value = when (response.phase) {
                    "abandoned" -> "활동이 없어 세션이 종료되었습니다"
                    "completed" -> "대화를 완료했습니다"
                    else -> "응답 완료"
                }
            }.onFailure { error ->
                _chatMessages.update { list ->
                    list + ChatMessage(
                        ChatSender.BOT,
                        if (error.isNetworkTimeout()) {
                            "응답 제한시간이 지났습니다. 메시지는 보존되었으며 필요하면 직접 다시 전송해 주세요."
                        } else {
                            "응답 요청에 실패했습니다. 서버 연결을 확인해 주세요."
                        }
                    )
                }
                _chatStatus.value = if (error.isNetworkTimeout()) {
                    "채팅 시간 초과: ${_chatReadTimeoutMinutes.value}분"
                } else if (error is ApiHttpException && error.statusCode == 409) {
                    "다른 메시지를 처리 중입니다. 잠시 후 직접 다시 보내 주세요"
                } else {
                    "채팅 실패: ${safeSessionError(error)}"
                }
            }

            _isChatSending.value = false
            chatRequestJob = null
        }
    }

    fun finishConversationManually() {
        if (_isChatSending.value) return
        _isChatSending.value = true
        _chatStatus.value = "대화 종료 중"
        viewModelScope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    conversationSessionManager.finish(authenticatedOwnerId())
                }
            }.onSuccess { finished ->
                interventionEpoch += 1L
                chatRequestJob?.cancel()
                _conversationPhase.value = finished?.interactionPhase ?: "completed"
                _sessionReportStatus.value = finished?.reportStatus ?: "not_started"
                _sessionInactivityTimeoutSeconds.value = finished?.inactivityTimeoutSeconds
                _chatStatus.value = "대화를 완료했습니다"
                _isChatVisible.value = false
                PhoneMonitoringState.closeIntervention()
            }.onFailure { error ->
                _chatStatus.value = "대화 종료 실패: ${safeSessionError(error)}"
            }
            _isChatSending.value = false
        }
    }

    fun startCravingSimulation() {
        if (cravingSimulationJob?.isActive == true) return
        cravingSimulationJob = viewModelScope.launch(Dispatchers.IO) {
            _isCravingSimulationRunning.value = true
            try {
                listOf(0, 1).forEachIndexed { index, cravingClass ->
                    val prediction = CravingPrediction(
                        cravingClass = cravingClass,
                        timestampMs = System.currentTimeMillis(),
                        rawBody = """{"class":$cravingClass,"source":"local_simulation"}"""
                    )
                    handlePrediction(
                        prediction = prediction,
                        sourceLabel = "로컬 테스트"
                    )
                    _simulationStatus.value = "모델 미사용 테스트 단계 $cravingClass 입력"
                    if (index < 2) delay(10_000L)
                }
                _simulationStatus.value = "테스트 완료: 로컬 0-1 입력"
            } finally {
                _isCravingSimulationRunning.value = false
            }
        }
    }

    fun stopCravingSimulation() {
        cravingSimulationJob?.cancel()
        cravingSimulationJob = null
        _isCravingSimulationRunning.value = false
        _simulationStatus.value = "테스트 중지됨"
    }

    fun uploadLatencySnapshot(): List<Pair<Long, Float>> = PhoneMonitoringState.uploadLatencySnapshot()

    fun predictionLatencySnapshot(): List<Pair<Long, Float>> = PhoneMonitoringState.predictionLatencySnapshot()

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
                _predictionStatus.value = "자동 수신 보류: 예측 수신 URL이 비어 있음"
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

    private fun handlePrediction(
        prediction: CravingPrediction,
        sourceLabel: String,
        sendToWatch: Boolean = true
    ) {
        val canNotify = MobileAuthRuntime.state.value.canNotify
        val action = AlertActionPolicy.resolve(prediction)
        val alertClaimed = canNotify && PhoneMonitoringState.registerAlertAction(prediction, action)
        PhoneMonitoringState.publishPrediction(prediction)
        if (NotificationPresentationPolicy.shouldPresent(canNotify, alertClaimed)) handleCravingAlert(action)
        val sentToWatch = sendToWatch && predictionSender.sendPrediction(
            prediction = prediction,
            suppressAlertPresentation = NotificationPresentationPolicy.suppressWatchPresentation(
                canNotify = canNotify,
                interventionActive = PhoneMonitoringState.isInterventionActive.value
            )
        )
        _predictionStatus.value = if (!sendToWatch) {
            "$sourceLabel 완료, 폰에서만 표시"
        } else if (sentToWatch) {
            "$sourceLabel 수신, 워치 전달 완료"
        } else {
            "$sourceLabel 수신, 워치 미연결"
        }
    }

    /** Camera-derived predictions intentionally never enter the phone-to-watch sender. */
    fun handleCameraPrediction(prediction: CravingPrediction) {
        handlePrediction(
            prediction,
            sourceLabel = "카메라 rPPG 예측",
            sendToWatch = RppgPredictionRoutingPolicy.relayToWatch
        )
    }

    private fun recordPredictionForUi(prediction: CravingPrediction) {
        appendCravingClassPoint(prediction)
        val actionVersion = PhoneMonitoringState.alertActionVersion.value
        if (
            AlertActionPolicy.resolve(prediction) == AlertAction.REQUIRED &&
            actionVersion > observedAlertActionVersion
        ) {
            observedAlertActionVersion = actionVersion
            _stateCheckResponses.value = emptyMap()
            _isChatVisible.value = false
            _isStateCheckRequired.value = false
            _isAuqChoiceRequired.value = false
            _isTalkChoiceRequired.value = true
        }
    }

    private fun handleCravingAlert(action: AlertAction) {
        when (action) {
            AlertAction.RECOMMEND -> alertNotifier.showClassOneAlert()
            AlertAction.REQUIRED -> {
                alertNotifier.openStateCheckScreen()
                alertNotifier.showClassTwoAlert(launchScreen = false)
            }
            AlertAction.NONE, AlertAction.COOLDOWN -> Unit
        }
    }

    private fun Throwable.isNetworkTimeout(): Boolean =
        this is SocketTimeoutException || cause?.isNetworkTimeout() == true

    private fun openBufferedConversation(status: String) {
        interventionEpoch += 1L
        chatRequestJob?.cancel()
        chatRequestJob = null
        _isChatSending.value = false
        _chatStatus.value = status
        val prompt = bufferedSessionPrompt
        if (!prompt.isNullOrBlank()) {
            _chatMessages.value = listOf(ChatMessage(ChatSender.BOT, prompt))
        }
        bufferedSessionPrompt = null
        _isChatVisible.value = true
        PhoneMonitoringState.activateIntervention()
    }

    /** 1초마다 최신 10초 window를 만들어 서버에 POST한다. */
    private fun startServerUploadLoop() {
        viewModelScope.launch {
            while (true) {
                delay(SERVER_UPLOAD_INTERVAL_MS)
                if (
                    !_isUploadEnabled.value ||
                    !MobileAuthRuntime.canUpload() ||
                    PhoneMonitoringState.isCameraPauseActive.value
                ) continue

                val url = _serverUrl.value
                val payload = pendingUpload ?: buildServerPayload()
                if (payload == null) {
                    _uploadStatus.value = "전송 대기: $lastPayloadSkipReason"
                    continue
                }

                PhoneMonitoringState.rememberUpload(payload)
                _uploadStatus.value = "전송 중: ${payload.samples.size} samples"
                val result = runCatching {
                    withContext(Dispatchers.IO) {
                        serverUploader.postWindowAuthenticated(apiClient, url, payload)
                    }
                }.getOrElse { e ->
                    if (e is AuthenticationRequiredException || (e is ApiHttpException && e.statusCode == 401)) {
                        handleAuthenticationFailure()
                    }
                    UploadResult(false, -1, e.message ?: "unknown error")
                }
                PhoneMonitoringState.recordUploadLatency(payload)

                if (result.success) {
                    windowIdStore.markCompleted(uploadKey(payload))
                    pendingUpload = null
                } else {
                    pendingUpload = payload
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
                        apiClient.executeAuthenticatedStream { token ->
                            serverUploader.listenPredictions(
                                url = url,
                                accessToken = token,
                                shouldContinue = {
                                    _isPredictionReceiverEnabled.value &&
                                        MobileAuthRuntime.canReceivePredictions() &&
                                        activeJob?.isActive == true
                                }
                            ) { prediction ->
                                if (PhoneMonitoringState.isCameraPauseActive.value) return@listenPredictions
                                val receivedAtMs = System.currentTimeMillis()
                                PhoneMonitoringState.recordPredictionLatency(prediction, receivedAtMs)
                                handlePrediction(prediction, sourceLabel = "서버 예측")
                            }
                        }
                    }
                }

                if (!_isPredictionReceiverEnabled.value) break

                val failure = result.exceptionOrNull()
                if (failure is AuthenticationRequiredException || (failure is ApiHttpException && failure.statusCode == 401)) {
                    handleAuthenticationFailure()
                    break
                }
                val error = failure?.message ?: "stream closed"
                _predictionStatus.value = "SSE 재연결 대기: $error"
                delay(PREDICTION_RECONNECT_DELAY_MS)
            }
        }
    }

    override fun onCleared() {
        autoCommunicationJob?.cancel()
        predictionReceiverJob?.cancel()
        cravingSimulationJob?.cancel()
        chatRequestJob?.cancel()
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
        val session = PhoneMonitoringState.currentSession()
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
            clientWindowId = windowIdStore.getOrCreate(uploadKey(session.sessionId, uploadSequence)),
            sessionId = session.sessionId,
            sessionStartedAtMs = session.startedAtMs,
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

    private fun uploadKey(payload: ServerWindowPayload): String =
        uploadKey(payload.sessionId, payload.sequence)

    private fun uploadKey(sessionId: String, sequence: Long): String =
        "view-model:$sessionId:$sequence"

    private fun handleAuthenticationFailure() {
        MobileAuthRuntime.signOut()
        apiClient.clearSession()
        _isUploadEnabled.value = false
        _isPredictionReceiverEnabled.value = false
        PhoneMonitoringService.stop(getApplication())
    }

    private fun authenticatedOwnerId(): String =
        MobileAuthRuntime.state.value.user?.id ?: throw AuthenticationRequiredException()

    private fun safeSessionError(error: Throwable): String = when (error) {
        is ApiHttpException -> "HTTP ${error.statusCode}"
        is AuthenticationRequiredException -> "로그인이 필요합니다"
        else -> error.message?.take(120) ?: "unknown error"
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
