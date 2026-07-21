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
 *   서버 전송은 최근 20초 원시 샘플을 10초마다 POST한다.
 */
package com.example.healthsensor

import android.app.Application
import android.content.Context
import android.media.MediaRecorder
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.github.mikephil.charting.data.Entry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.SocketTimeoutException
import java.io.File
import java.util.UUID

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
    const val MIN_RESPONSE = 0
    const val MAX_RESPONSE = 6

    fun isValidResponse(value: Int): Boolean = value in MIN_RESPONSE..MAX_RESPONSE

    fun correctedScore(rawScore: Int, reverseScored: Boolean): Int {
        require(isValidResponse(rawScore)) { "response must be between 0 and 6" }
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
    val timestampMs: Long = System.currentTimeMillis(),
    val clientMessageId: String? = null
)

enum class ChatSender {
    USER,
    BOT
}

data class PendingChatRetry(
    val clientMessageId: String,
    val content: String,
    val inputModality: String = "text",
    val attemptsRemaining: Int
)

object ChatRetryPolicy {
    fun pending(
        error: Throwable,
        clientMessageId: String,
        content: String,
        inputModality: String = "text"
    ): PendingChatRetry? = (error as? DialogueRequestException)
        ?.takeIf { it.retryable && it.attemptsRemaining > 0 }
        ?.let { PendingChatRetry(clientMessageId, content, inputModality, it.attemptsRemaining) }
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
    private val AUTO_COMMUNICATION_DELAY_MS = 20_000L
    private val serverConfig = ServerConfig.load(application).also { PhoneMonitoringState.ensureConfig(it) }
    private val apiClient = MobileApiProvider.get(application)
    private val predictionSender = PhonePredictionSender(application)
    private val alertNotifier = CravingAlertNotifier(application)
    private var autoCommunicationStarted = false
    private var autoCommunicationJob: Job? = null
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

    private val watchConnectionMonitor = WatchConnectionMonitor(application) {
        _isReceiving.value = false
    }
    val watchConnectionState: StateFlow<WatchConnectionState> = watchConnectionMonitor.state

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

    private val _pendingChatRetry = MutableStateFlow<PendingChatRetry?>(null)
    val pendingChatRetry: StateFlow<PendingChatRetry?> = _pendingChatRetry

    private val _chatReadTimeoutMinutes = MutableStateFlow(
        ChatReadTimeoutPolicy.storedOrDefault(
            interventionPreferences.getInt(
                PREF_CHAT_READ_TIMEOUT_MINUTES,
                ChatReadTimeoutPolicy.DEFAULT_MINUTES
            )
        )
    )
    val chatReadTimeoutMinutes: StateFlow<Int> = _chatReadTimeoutMinutes

    private val sessionApi = AuthenticatedSessionApi(apiClient)
    private val conversationSessionManager = ConversationSessionManager(
        api = sessionApi,
        store = SharedPreferencesConversationSessionStore(application),
        timeoutMs = { ChatReadTimeoutPolicy.toMillis(_chatReadTimeoutMinutes.value).toLong() }
    )

    private val _chatTimeoutSettingStatus = MutableStateFlow("")
    val chatTimeoutSettingStatus: StateFlow<String> = _chatTimeoutSettingStatus

    private val _conversationPhase = MutableStateFlow("free_dialogue")
    val conversationPhase: StateFlow<String> = _conversationPhase

    private val _sessionReportStatus = MutableStateFlow("not_started")
    val sessionReportStatus: StateFlow<String> = _sessionReportStatus

    private val _sessionInactivityTimeoutSeconds = MutableStateFlow<Int?>(null)
    val sessionInactivityTimeoutSeconds: StateFlow<Int?> = _sessionInactivityTimeoutSeconds

    private val _isVoiceRecording = MutableStateFlow(false)
    val isVoiceRecording: StateFlow<Boolean> = _isVoiceRecording

    private val _isVoiceTranscribing = MutableStateFlow(false)
    val isVoiceTranscribing: StateFlow<Boolean> = _isVoiceTranscribing

    private val _voiceDraft = MutableStateFlow<String?>(null)
    val voiceDraft: StateFlow<String?> = _voiceDraft

    private val _voiceStatus = MutableStateFlow("")
    val voiceStatus: StateFlow<String> = _voiceStatus

    private var voiceRecorder: MediaRecorder? = null
    private var voiceRecordingFile: File? = null

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
                    conversationSessionManager.startWithCreationState(
                        ownerId = authenticatedOwnerId(),
                        sessionType = "alert_checkin",
                        triggerAlertId = _latestPrediction.value?.alertId
                    )
                }
            }.onSuccess { (session, created) ->
                bufferedSessionPrompt = session.assistantText
                _conversationPhase.value = session.interactionPhase ?: "free_dialogue"
                _sessionReportStatus.value = session.reportStatus
                _sessionInactivityTimeoutSeconds.value = session.inactivityTimeoutSeconds
                if (created) {
                    _isAuqChoiceRequired.value = true
                    _chatStatus.value = "AUQ는 선택 사항입니다"
                } else {
                    openBufferedConversation("기존 대화를 이어갑니다")
                }
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
                    conversationSessionManager.ensureWithCreationState(authenticatedOwnerId())
                }
            }.onSuccess { (session, created) ->
                bufferedSessionPrompt = session.assistantText
                _conversationPhase.value = session.interactionPhase ?: "free_dialogue"
                _sessionReportStatus.value = session.reportStatus
                _sessionInactivityTimeoutSeconds.value = session.inactivityTimeoutSeconds
                if (created) {
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
        _conversationPhase.value = "free_dialogue"
        _sessionReportStatus.value = "not_started"
        _sessionInactivityTimeoutSeconds.value = null
        _pendingChatRetry.value = null
        bufferedSessionPrompt = null
        seenInterventionIds.clear()
    }

    fun sendChatMessage(text: String, inputModality: String = "text") {
        val message = text.trim()
        if (message.isBlank() || _isChatSending.value || inputModality !in setOf("text", "voice")) return
        val clientMessageId = UUID.randomUUID().toString()
        _chatMessages.update {
            list -> list + ChatMessage(ChatSender.USER, message, clientMessageId = clientMessageId)
        }
        _pendingChatRetry.value = null
        dispatchChatMessage(clientMessageId, message, inputModality)
    }

    fun retryChatMessage() {
        val pending = _pendingChatRetry.value ?: return
        if (_isChatSending.value || pending.attemptsRemaining <= 0) return
        _pendingChatRetry.value = null
        dispatchChatMessage(pending.clientMessageId, pending.content, pending.inputModality)
    }

    private fun dispatchChatMessage(clientMessageId: String, message: String, inputModality: String) {
        _isChatSending.value = true
        _chatStatus.value = "응답 요청 중"

        val requestEpoch = interventionEpoch
        chatRequestJob = viewModelScope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    conversationSessionManager.postMessage(
                        ownerId = authenticatedOwnerId(),
                        clientMessageId = clientMessageId,
                        content = message,
                        inputModality = inputModality,
                        readTimeoutMs = ChatReadTimeoutPolicy.toMillis(_chatReadTimeoutMinutes.value)
                    )
                }
            }

            if (requestEpoch != interventionEpoch) return@launch

            result.onSuccess { response ->
                _pendingChatRetry.value = null
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
                val dialogueError = error as? DialogueRequestException
                _pendingChatRetry.value = ChatRetryPolicy.pending(error, clientMessageId, message, inputModality)
                _chatStatus.value = if (dialogueError != null) {
                    if (dialogueError.retryable) {
                        "응답 생성에 실패했습니다. 같은 메시지를 한 번 다시 시도할 수 있습니다"
                    } else {
                        "응답 생성에 다시 실패했습니다. 잠시 후 새 메시지로 시도해 주세요"
                    }
                } else if (error.isNetworkTimeout()) {
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

    fun startVoiceRecording() {
        if (_isVoiceRecording.value || _isVoiceTranscribing.value) return
        if (!MobileAuthRuntime.state.value.canUseVoice) {
            _voiceStatus.value = "음성 입력 동의가 필요합니다"
            return
        }
        val output = runCatching {
            File.createTempFile("voice_", ".m4a", getApplication<Application>().cacheDir)
        }.getOrElse {
            _voiceStatus.value = "녹음 파일을 준비할 수 없습니다"
            return
        }
        val recorder = MediaRecorder()
        runCatching {
            recorder.setAudioSource(MediaRecorder.AudioSource.MIC)
            recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            recorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            recorder.setAudioSamplingRate(16_000)
            recorder.setAudioEncodingBitRate(64_000)
            recorder.setMaxDuration(30_000)
            recorder.setOutputFile(output.absolutePath)
            recorder.setOnInfoListener { _, what, _ ->
                if (what == MediaRecorder.MEDIA_RECORDER_INFO_MAX_DURATION_REACHED) stopVoiceRecording()
            }
            recorder.prepare()
            recorder.start()
        }.onSuccess {
            voiceRecorder = recorder
            voiceRecordingFile = output
            _isVoiceRecording.value = true
            _voiceStatus.value = "녹음 중 · 최대 30초"
        }.onFailure {
            recorder.release()
            output.delete()
            _voiceStatus.value = "녹음을 시작할 수 없습니다"
        }
    }

    fun stopVoiceRecording() {
        val recorder = voiceRecorder ?: return
        val audio = voiceRecordingFile
        voiceRecorder = null
        voiceRecordingFile = null
        _isVoiceRecording.value = false
        val stopped = runCatching { recorder.stop() }.isSuccess
        recorder.release()
        if (!stopped || audio == null || !audio.isFile || audio.length() == 0L) {
            audio?.delete()
            _voiceStatus.value = "음성이 녹음되지 않았습니다"
            return
        }
        _isVoiceTranscribing.value = true
        _voiceStatus.value = "음성을 글로 바꾸는 중"
        viewModelScope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    val session = conversationSessionManager.ensure(authenticatedOwnerId())
                    sessionApi.transcribe(session.id, audio)
                }
            }.onSuccess { result ->
                _voiceDraft.value = result.text
                _voiceStatus.value = "인식 결과를 확인한 뒤 전송해 주세요"
            }.onFailure { error ->
                _voiceStatus.value = when (error) {
                    is ApiHttpException -> when (error.statusCode) {
                        403 -> "음성 입력 동의를 확인해 주세요"
                        413 -> "녹음이 너무 큽니다"
                        415 -> "지원하지 않는 음성 형식입니다"
                        422 -> "말소리를 찾지 못했습니다"
                        503 -> "현재 음성 인식을 사용할 수 없습니다"
                        504 -> "음성 인식 시간이 초과되었습니다"
                        else -> "음성 인식에 실패했습니다"
                    }
                    else -> "음성 인식에 실패했습니다"
                }
            }
            audio.delete()
            _isVoiceTranscribing.value = false
        }
    }

    fun consumeVoiceDraft() {
        _voiceDraft.value = null
    }

    fun finishConversationManually() {
        if (_isChatSending.value) return
        _isChatSending.value = true
        _chatStatus.value = "대화 종료 중"
        _pendingChatRetry.value = null
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
     * 센서 취득이 시작된 뒤 20초가 지나면 통신을 자동으로 켠다.
     *
     * 첫 20초는 서버 모델에 필요한 window를 확보하는 준비 구간이다. URL이 비어 있으면
     * 사용자가 나중에 입력할 수 있도록 상태만 보류로 바꾸고, updateServerUrl/updatePredictionUrl에서
     * 이어서 시작한다.
     */
    private fun scheduleAutoCommunicationStart() {
        if (autoCommunicationJob != null || autoCommunicationStarted) return

        _uploadStatus.value = "취득 시작 감지: 20초 후 자동 전송"
        _predictionStatus.value = "취득 시작 감지: 20초 후 자동 수신"
        autoCommunicationJob = viewModelScope.launch {
            delay(AUTO_COMMUNICATION_DELAY_MS)
            autoCommunicationStarted = true

            if (_serverUrl.value.isNotBlank()) {
                if (!_isUploadEnabled.value) setUploadEnabled(true)
                _uploadStatus.value = "자동 전송 시작: 취득 20초 경과"
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
        if (!PhoneMonitoringState.publishPrediction(prediction)) {
            _predictionStatus.value = "$sourceLabel 수신, 더 최신인 결과를 유지"
            return
        }
        val canNotify = MobileAuthRuntime.state.value.canNotify
        val action = AlertActionPolicy.resolve(prediction)
        val alertClaimed = canNotify && PhoneMonitoringState.registerAlertAction(prediction, action)
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

    override fun onCleared() {
        watchConnectionMonitor.close()
        runCatching { voiceRecorder?.stop() }
        voiceRecorder?.release()
        voiceRecorder = null
        voiceRecordingFile?.delete()
        voiceRecordingFile = null
        autoCommunicationJob?.cancel()
        cravingSimulationJob?.cancel()
        chatRequestJob?.cancel()
        super.onCleared()
    }

    private fun authenticatedOwnerId(): String =
        MobileAuthRuntime.state.value.user?.id ?: throw AuthenticationRequiredException()

    private fun safeSessionError(error: Throwable): String = when (error) {
        is ApiHttpException -> "HTTP ${error.statusCode}"
        is AuthenticationRequiredException -> "로그인이 필요합니다"
        else -> error.message?.take(120) ?: "unknown error"
    }

}
